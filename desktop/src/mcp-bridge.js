// MCP 工具橋接：在電腦上執行「指令型」MCP 伺服器（npx / uvx / node / python …，stdio 傳輸），
// 再以 Streamable HTTP 提供給手機上的 星汐。每個伺服器一個網址：http://<電腦>:<port>/mcp/<名稱>，
// 需要 Bearer 權杖。這些程式以你的 Windows 帳戶權限執行，只加入你信任的工具。
const http = require('http');
const { spawn } = require('child_process');
const crypto = require('crypto');

const REQUEST_TIMEOUT_MS = 120_000;
const PROTOCOL_VERSION = '2025-06-18';

class StdioServer {
  constructor(name, spec, onStatus) {
    this.name = name;
    this.spec = spec;
    this.onStatus = onStatus;
    this.child = null;
    this.buffer = '';
    this.nextId = 1;
    this.waiting = new Map();   // 子程序 id → { resolve, reject, timer }
    this.initResult = null;
    this.status = 'stopped';
    this.lastError = '';
    this.stderr = [];
    this.restarts = 0;
    this.stopped = false;
  }

  setStatus(status, error = '') {
    this.status = status;
    if (error) this.lastError = error;
    this.onStatus();
  }

  async start() {
    this.stopped = false;
    this.setStatus('starting');
    const env = { ...process.env, ...(this.spec.env || {}) };
    // Windows 上 npx/uvx 是 .cmd，需要經過 shell 執行；參數自行加引號，含空白的路徑才不會被拆開。
    const win = process.platform === 'win32';
    const line = [this.spec.command, ...(this.spec.args || [])].map(quoteWindowsArg).join(' ');
    this.child = win
      ? spawn(line, { env, cwd: this.spec.cwd || undefined, windowsHide: true, shell: true })
      : spawn(this.spec.command, this.spec.args || [], { env, cwd: this.spec.cwd || undefined });
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk) => this.onData(chunk));
    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', (chunk) => {
      this.stderr.push(...String(chunk).split(/\r?\n/).filter(Boolean));
      this.stderr = this.stderr.slice(-20);
    });
    this.child.on('error', (e) => this.setStatus('error', `無法啟動：${e.message}`));
    this.child.on('exit', (code) => {
      this.child = null;
      this.initResult = null;
      for (const w of this.waiting.values()) { clearTimeout(w.timer); w.reject(new Error('server_exited')); }
      this.waiting.clear();
      if (this.stopped) { this.setStatus('stopped'); return; }
      const tail = this.stderr.slice(-3).join(' / ');
      this.setStatus('error', `已結束（代碼 ${code}）${tail ? '：' + tail : ''}`);
      // 自動重啟，間隔逐次拉長，最多等 5 分鐘。
      const delay = Math.min(300_000, 5_000 * 2 ** Math.min(this.restarts++, 6));
      setTimeout(() => { if (!this.stopped && !this.child) this.start().catch(() => {}); }, delay);
    });
    try {
      // 由橋接代替手機完成一次 initialize，之後手機的 initialize 直接用快取回覆。
      this.initResult = await this.call('initialize', {
        protocolVersion: PROTOCOL_VERSION, capabilities: {}, clientInfo: { name: 'orbis-desktop-bridge', version: '1' },
      }, 60_000);
      this.send({ jsonrpc: '2.0', method: 'notifications/initialized' });
      this.restarts = 0;
      this.setStatus('running');
    } catch (e) {
      this.setStatus('error', `初始化失敗：${e.message}${this.stderr.length ? '；' + this.stderr.slice(-2).join(' / ') : ''}`);
    }
  }

  stop() {
    this.stopped = true;
    if (this.child) {
      // shell 模式下要連同子程序一起結束。
      if (process.platform === 'win32') spawn('taskkill', ['/pid', String(this.child.pid), '/T', '/F'], { windowsHide: true });
      else this.child.kill();
    }
  }

  send(message) {
    if (!this.child) throw new Error('server_not_running');
    this.child.stdin.write(JSON.stringify(message) + '\n');
  }

  call(method, params, timeoutMs = REQUEST_TIMEOUT_MS) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++;
      const timer = setTimeout(() => { this.waiting.delete(id); reject(new Error('timeout')); }, timeoutMs);
      this.waiting.set(id, { resolve, reject, timer, raw: false });
      try { this.send({ jsonrpc: '2.0', id, method, params }); } catch (e) { clearTimeout(timer); this.waiting.delete(id); reject(e); }
    });
  }

  /** 轉送手機的一則 JSON-RPC 請求，回傳完整的回應物件（保留原本的 id）。 */
  forward(message) {
    return new Promise((resolve) => {
      const id = this.nextId++;
      const timer = setTimeout(() => {
        this.waiting.delete(id);
        resolve({ jsonrpc: '2.0', id: message.id, error: { code: -32001, message: 'Request timed out on desktop bridge' } });
      }, REQUEST_TIMEOUT_MS);
      this.waiting.set(id, { raw: true, timer, resolve: (reply) => resolve({ ...reply, id: message.id }), reject: () => {} });
      try { this.send({ ...message, id }); } catch {
        clearTimeout(timer); this.waiting.delete(id);
        resolve({ jsonrpc: '2.0', id: message.id, error: { code: -32002, message: 'MCP server is not running on the desktop' } });
      }
    });
  }

  onData(chunk) {
    this.buffer += chunk;
    let index;
    while ((index = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, index).trim();
      this.buffer = this.buffer.slice(index + 1);
      if (!line.startsWith('{')) continue; // 有些伺服器會把記錄印到 stdout
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      if (msg.id !== undefined && msg.method === undefined) {
        const w = this.waiting.get(msg.id);
        if (!w) continue;
        clearTimeout(w.timer); this.waiting.delete(msg.id);
        if (w.raw) w.resolve(msg);
        else if (msg.error) w.reject(new Error(msg.error.message || 'error'));
        else w.resolve(msg.result);
      } else if (msg.id !== undefined && msg.method) {
        // 伺服器反向請求（roots、sampling、elicitation）：手機端不支援，給最安全的回覆。
        const result = msg.method === 'roots/list' ? { result: { roots: [] } }
          : msg.method === 'ping' ? { result: {} }
          : { error: { code: -32601, message: 'Not supported by 星汐 desktop bridge' } };
        try { this.send({ jsonrpc: '2.0', id: msg.id, ...result }); } catch { /* ignore */ }
      }
      // 其他通知（進度、記錄、清單變更）略過。
    }
  }
}

class McpBridge {
  constructor(onChange) {
    this.onChange = onChange;
    this.servers = new Map();
    this.httpServer = null;
    this.port = 0;
    this.token = '';
  }

  statusList() {
    return [...this.servers.values()].map((s) => ({ name: s.name, status: s.status, error: s.lastError, command: s.spec.command }));
  }

  /**
   * @param {{port: number, token: string, servers: Record<string, {command: string, args?: string[], env?: object, cwd?: string, enabled?: boolean}>}} options
   */
  async apply(options) {
    this.token = options.token;
    const wanted = Object.entries(options.servers || {}).filter(([, s]) => s && s.command && s.enabled !== false);
    const wantedNames = new Set(wanted.map(([n]) => n));
    for (const [name, server] of this.servers) {
      if (!wantedNames.has(name)) { server.stop(); this.servers.delete(name); }
    }
    for (const [name, spec] of wanted) {
      const existing = this.servers.get(name);
      if (existing && JSON.stringify(existing.spec) === JSON.stringify(spec)) continue;
      if (existing) existing.stop();
      const server = new StdioServer(name, spec, () => this.onChange());
      this.servers.set(name, server);
      server.start().catch(() => {});
    }
    if (wanted.length === 0) { await this.closeHttp(); this.onChange(); return; }
    if (!this.httpServer || this.port !== options.port) {
      await this.closeHttp();
      await this.listen(options.port);
    }
    this.onChange();
  }

  async stopAll() {
    for (const s of this.servers.values()) s.stop();
    this.servers.clear();
    await this.closeHttp();
  }

  closeHttp() {
    return new Promise((resolve) => {
      if (!this.httpServer) return resolve();
      this.httpServer.close(() => resolve());
      this.httpServer = null;
    });
  }

  listen(port) {
    return new Promise((resolve, reject) => {
      const server = http.createServer((req, res) => this.handle(req, res).catch(() => {
        if (!res.headersSent) { res.writeHead(500); res.end(); }
      }));
      server.on('error', reject);
      server.listen(port, '0.0.0.0', () => { this.httpServer = server; this.port = port; resolve(); });
    });
  }

  authorized(req) {
    const header = req.headers.authorization || '';
    const given = Buffer.from(header.replace(/^Bearer\s+/i, ''));
    const expected = Buffer.from(this.token);
    return this.token.length > 0 && given.length === expected.length && crypto.timingSafeEqual(given, expected);
  }

  async handle(req, res) {
    const match = /^\/mcp\/([^/?#]+)/.exec(req.url || '');
    if (!match) { res.writeHead(404); return res.end(); }
    if (!this.authorized(req)) { res.writeHead(401, { 'WWW-Authenticate': 'Bearer' }); return res.end(); }
    const server = this.servers.get(decodeURIComponent(match[1]));
    if (!server) { res.writeHead(404); return res.end(); }
    if (req.method === 'DELETE') { res.writeHead(200); return res.end(); }
    if (req.method !== 'POST') { res.writeHead(405, { Allow: 'POST, DELETE' }); return res.end(); }

    const body = await readBody(req, 8 * 1024 * 1024);
    let payload;
    try { payload = JSON.parse(body); } catch { res.writeHead(400); return res.end(); }
    const batch = Array.isArray(payload);
    const messages = batch ? payload : [payload];
    const replies = [];
    for (const m of messages) {
      if (m.id === undefined) continue; // 通知（含 notifications/initialized）由橋接自己處理
      if (m.method === 'initialize') {
        // 工具剛啟動（例如 npx 第一次下載、Python 載入套件）時，手機可能比它早連上；先等它就緒，最多 90 秒。
        for (let waited = 0; !server.initResult && server.status === 'starting' && waited < 90_000; waited += 300) {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
        if (!server.initResult) {
          replies.push({ jsonrpc: '2.0', id: m.id, error: { code: -32002, message: `MCP server not ready: ${server.lastError || server.status}` } });
        } else replies.push({ jsonrpc: '2.0', id: m.id, result: server.initResult });
      } else if (m.method === 'ping') {
        replies.push({ jsonrpc: '2.0', id: m.id, result: {} });
      } else if (m.method) {
        replies.push(await server.forward(m));
      }
    }
    if (replies.length === 0) { res.writeHead(202); return res.end(); }
    const out = JSON.stringify(batch ? replies : replies[0]);
    res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(out) });
    res.end(out);
  }
}

/** cmd.exe 參數引號：含空白或特殊字元時用雙引號包起來，內部的雙引號加倍。 */
function quoteWindowsArg(arg) {
  const s = String(arg);
  if (s.length > 0 && !/[\s"&|<>^()%!]/.test(s)) return s;
  return '"' + s.replace(/"/g, '""') + '"';
}

function readBody(req, max) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => { size += c.length; if (size > max) { reject(new Error('too_large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** 從貼上的 JSON 取出指令型伺服器（網址型的請直接在手機匯入）。 */
function parseCommandServers(text) {
  const cleaned = String(text || '')
    .replace(/("(?:\\.|[^"\\])*")|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, (m, str) => str || '')
    .replace(/,(\s*[}\]])/g, '$1');
  const root = JSON.parse(cleaned);
  const map = root.mcpServers || root.servers || (root.mcp && root.mcp.servers) || root;
  const servers = {};
  const skipped = [];
  for (const [name, spec] of Object.entries(map)) {
    if (!spec || typeof spec !== 'object') continue;
    if (spec.command) {
      servers[name] = { command: String(spec.command), args: (spec.args || []).map(String), env: spec.env || {}, cwd: spec.cwd, enabled: spec.disabled !== true };
    } else if (spec.url || spec.serverUrl || spec.httpUrl) {
      skipped.push(`${name}：網址型工具請直接在手機的 MCP 匯入`);
    }
  }
  return { servers, skipped };
}

module.exports = { McpBridge, parseCommandServers };
