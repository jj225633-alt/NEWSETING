// 星汐電腦版：開啟手機上 星汐 的網頁版（資料都在手機上，兩邊天生同一份聊天），
// 並可選擇把電腦使用狀態回報給手機上的 AI。
const { app, BrowserWindow, Tray, Menu, ipcMain, shell, safeStorage, nativeImage, Notification } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { ActivityReporter } = require('./activity');
const { ScreenshotResponder } = require('./screenshot');
const { McpBridge, parseCommandServers } = require('./mcp-bridge');
const net = require('net');
const crypto = require('crypto');

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }

const CONFIG_FILE = () => path.join(app.getPath('userData'), 'config.json');
const DEFAULT_CONFIG = {
  addresses: [],          // 依序嘗試，例如 ["http://192.168.1.23:8081", "http://100.101.102.103:8081"]
  password: '',           // 以 Windows 帳戶加密後儲存
  shareActivity: false,
  shareTitles: true,
  allowScreenshot: true,  // AI 可以請求看螢幕；每次仍需人類在電腦上按允許
  excludedApps: [],
  autostart: true,
  bridgeEnabled: false,   // MCP 工具橋接
  bridgePort: 8765,
  bridgeToken: '',
  bridgeJson: '',         // 使用者貼上的設定（可能含金鑰），加密儲存
  deviceName: os.hostname(),
};

let config = { ...DEFAULT_CONFIG };
let mainWindow = null;
let setupWindow = null;
let tray = null;
let activeAddress = null;
let token = null;
let tokenExpiresAt = 0;
let trayStatus = '';
let quitting = false;

function loadConfig() {
  try {
    const raw = JSON.parse(fs.readFileSync(CONFIG_FILE(), 'utf8'));
    const password = raw.passwordEnc && safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(Buffer.from(raw.passwordEnc, 'base64')) : '';
    const bridgeJson = raw.bridgeJsonEnc && safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(Buffer.from(raw.bridgeJsonEnc, 'base64')) : '';
    config = { ...DEFAULT_CONFIG, ...raw, password, bridgeJson };
    delete config.passwordEnc; delete config.bridgeJsonEnc;
  } catch { config = { ...DEFAULT_CONFIG }; }
}

function saveConfig(next) {
  config = { ...config, ...next };
  if (!config.bridgeToken) config.bridgeToken = crypto.randomBytes(24).toString('hex');
  const { password, bridgeJson, ...rest } = config;
  const stored = { ...rest };
  if (password && safeStorage.isEncryptionAvailable()) stored.passwordEnc = safeStorage.encryptString(password).toString('base64');
  if (bridgeJson && safeStorage.isEncryptionAvailable()) stored.bridgeJsonEnc = safeStorage.encryptString(bridgeJson).toString('base64');
  fs.mkdirSync(path.dirname(CONFIG_FILE()), { recursive: true });
  fs.writeFileSync(CONFIG_FILE(), JSON.stringify(stored, null, 2));
  app.setLoginItemSettings({ openAtLogin: !!config.autostart, args: ['--hidden'] });
}

function normalizeAddress(value) {
  let v = String(value || '').trim();
  if (!v) return null;
  if (!/^https?:\/\//i.test(v)) v = 'http://' + v;
  try { const u = new URL(v); return `${u.protocol}//${u.host}`; } catch { return null; }
}

async function reachable(address, timeoutMs = 3000) {
  try {
    const res = await fetch(address + '/', { signal: AbortSignal.timeout(timeoutMs) });
    return res.ok;
  } catch { return false; }
}

async function pickAddress() {
  for (const address of config.addresses) {
    if (await reachable(address)) return address;
  }
  return null;
}

async function getToken(address, force = false) {
  if (!force && token && Date.now() < tokenExpiresAt - 60_000) return token;
  if (!config.password) return null;
  const res = await fetch(address + '/api/auth/token', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: config.password }), signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) { token = null; return null; }
  const body = await res.json();
  token = body.token; tokenExpiresAt = body.expiresAt || Date.now() + 3600_000;
  return token;
}

async function sendActivity(report) {
  const address = activeAddress || await pickAddress();
  if (!address) return false;
  activeAddress = address;
  for (const force of [false, true]) {
    const t = await getToken(address, force);
    if (!t) return false;
    const res = await fetch(address + '/api/hoshio/pc/activity', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t },
      body: JSON.stringify(report), signal: AbortSignal.timeout(8000),
    }).catch(() => null);
    if (!res) { activeAddress = null; return false; }
    if (res.status === 401) continue;
    return res.ok;
  }
  return false;
}

async function getAuth(force = false) {
  const address = activeAddress || await pickAddress();
  if (!address) return null;
  activeAddress = address;
  const t = await getToken(address, force).catch(() => null);
  return t ? { address, token: t } : null;
}

const reporter = new ActivityReporter(() => config, sendActivity, (status) => { trayStatus = status; updateTray(); });

const screenshots = new ScreenshotResponder(() => config, getAuth, () => reporter.latest);

function syncReporter() {
  if (config.shareActivity && config.password) reporter.start(); else reporter.stop();
  if (config.allowScreenshot !== false && config.password) screenshots.start(); else screenshots.stop();
}

function createMainWindow(show = true) {
  mainWindow = new BrowserWindow({
    width: 1200, height: 820, minWidth: 420, minHeight: 500, show,
    title: '星汐', autoHideMenuBar: true, backgroundColor: '#101114', icon: path.join(__dirname, 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  // 只在 星汐 視窗裡瀏覽手機上的網頁；其他連結交給預設瀏覽器。
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const allowed = config.addresses.some((a) => url.startsWith(a)) || url.startsWith('file:');
    if (!allowed) { event.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); }
  });
  mainWindow.on('close', (event) => {
    if (!quitting) { event.preventDefault(); mainWindow.hide(); }
  });
  loadOrbis();
}

async function loadOrbis() {
  if (!mainWindow) return;
  mainWindow.loadFile(path.join(__dirname, 'offline.html'), { query: { state: 'connecting' } });
  const address = await pickAddress();
  activeAddress = address;
  if (address) { mainWindow.loadURL(address + '/'); if (bridgeApplied) registerBridge().catch(() => {}); }
  else mainWindow.loadFile(path.join(__dirname, 'offline.html'), { query: { state: 'offline' } });
  updateTray();
}

function openSetup() {
  if (setupWindow) { setupWindow.show(); setupWindow.focus(); return; }
  setupWindow = new BrowserWindow({
    width: 560, height: 760, title: '星汐電腦版設定', autoHideMenuBar: true, backgroundColor: '#101114', icon: path.join(__dirname, 'icon.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, preload: path.join(__dirname, 'preload.js') },
  });
  setupWindow.loadFile(path.join(__dirname, 'setup.html'));
  setupWindow.on('closed', () => { setupWindow = null; });
}

function showMain() {
  if (!mainWindow) createMainWindow(true);
  mainWindow.show(); mainWindow.focus();
}

function updateTray() {
  if (!tray) return;
  const connected = activeAddress ? `已連線：${activeAddress}` : '未連上手機';
  tray.setToolTip(`星汐\n${connected}${trayStatus ? '\n' + trayStatus : ''}`);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '開啟 星汐', click: showMain },
    { label: connected, enabled: false },
    { label: trayStatus || '電腦活動分享未開啟', enabled: false },
    { type: 'separator' },
    { label: '分享電腦活動給 AI', type: 'checkbox', checked: !!config.shareActivity, enabled: !!config.password,
      click: (item) => { saveConfig({ shareActivity: item.checked }); syncReporter(); updateTray(); } },
    { label: '允許 AI 請求看螢幕（每次會先問你）', type: 'checkbox', checked: config.allowScreenshot !== false, enabled: !!config.password,
      click: (item) => { saveConfig({ allowScreenshot: item.checked }); syncReporter(); updateTray(); } },
    { label: mcpBridge.servers.size
        ? 'MCP 橋接：' + mcpBridge.statusList().filter((x) => x.status === 'running').length + '/' + mcpBridge.servers.size + ' 個執行中'
        : 'MCP 橋接未啟用', enabled: false },
    { label: '重新連線', click: () => { activeAddress = null; loadOrbis(); } },
    { label: '設定…', click: openSetup },
    { type: 'separator' },
    { label: '結束', click: () => { quitting = true; reporter.stop(); screenshots.stop(); mcpBridge.stopAll(); app.quit(); } },
  ]));
}

// ===== MCP 工具橋接 =====
let bridgeSkipped = [];
let bridgeError = '';
let bridgeRegistered = '';
let bridgeApplied = false; // 套用過一次後才向手機登記，避免啟動時送出空清單把手機上的橋接工具刪掉
const mcpBridge = new McpBridge(() => { updateTray(); });

/** 電腦連到手機時使用的本機 IP（家用 Wi-Fi 或 Tailscale），手機就用這個位址連回來。 */
function localAddressToward(address) {
  return new Promise((resolve) => {
    try {
      const u = new URL(address);
      const socket = net.connect({ host: u.hostname, port: Number(u.port || 80), timeout: 3000 }, () => {
        const ip = socket.localAddress; socket.destroy(); resolve(ip ? ip.replace(/^::ffff:/, '') : null);
      });
      socket.on('error', () => resolve(null));
      socket.on('timeout', () => { socket.destroy(); resolve(null); });
    } catch { resolve(null); }
  });
}

async function applyBridge() {
  bridgeError = ''; bridgeSkipped = [];
  let servers = {};
  if (config.bridgeEnabled && config.bridgeJson.trim()) {
    try { const parsed = parseCommandServers(config.bridgeJson); servers = parsed.servers; bridgeSkipped = parsed.skipped; }
    catch (e) { bridgeError = 'JSON 無法解析：' + e.message; }
  }
  try { await mcpBridge.apply({ port: Number(config.bridgePort) || 8765, token: config.bridgeToken, servers }); }
  catch (e) { bridgeError = '無法在連接埠 ' + config.bridgePort + ' 啟動橋接：' + e.message; }
  bridgeApplied = true;
  await registerBridge().catch(() => {});
}

async function registerBridge() {
  const auth = await getAuth();
  if (!auth) { bridgeRegistered = '尚未連上手機，連上後會自動登記'; return; }
  const names = config.bridgeEnabled ? [...mcpBridge.servers.keys()] : [];
  const ip = await localAddressToward(auth.address);
  if (!ip) { bridgeRegistered = '找不到這台電腦對手機的位址'; return; }
  const port = Number(config.bridgePort) || 8765;
  const res = await fetch(auth.address + '/api/hoshio/mcp/bridge', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.token },
    body: JSON.stringify({ baseUrl: 'http://' + ip + ':' + port, token: config.bridgeToken, servers: names }),
    signal: AbortSignal.timeout(8000),
  });
  if (res.ok) bridgeRegistered = names.length ? '已登記到手機（http://' + ip + ':' + port + '）' : '已從手機移除橋接工具';
  else {
    const detail = await res.text().then((t) => { try { return JSON.parse(t).error || t; } catch { return t; } }).catch(() => '');
    const hint = res.status === 404 || res.status === 405 || (res.status === 500 && !String(detail).includes('bridge_'))
      ? '：手機上的 星汐 可能還是舊版，請安裝最新 APK' : '';
    bridgeRegistered = '登記失敗（HTTP ' + res.status + '）' + hint + (detail ? '｜' + String(detail).slice(0, 160) : '');
  }
}

ipcMain.handle('bridge:get', () => ({
  enabled: !!config.bridgeEnabled, port: config.bridgePort, json: config.bridgeJson,
  status: mcpBridge.statusList(), skipped: bridgeSkipped, error: bridgeError, registered: bridgeRegistered,
}));
ipcMain.handle('bridge:save', async (_e, input) => {
  if (input.json && input.json.trim()) {
    try { parseCommandServers(input.json); } catch (e) { return { ok: false, message: 'JSON 無法解析：' + e.message }; }
  }
  saveConfig({ bridgeEnabled: !!input.enabled, bridgePort: Number(input.port) || 8765, bridgeJson: String(input.json || '') });
  await applyBridge();
  return { ok: true };
});

ipcMain.handle('config:get', () => ({ ...config, password: config.password ? '********' : '' }));
ipcMain.handle('config:save', async (_e, next) => {
  const addresses = (next.addresses || []).map(normalizeAddress).filter(Boolean);
  const patch = { ...next, addresses };
  if (next.password === '********') delete patch.password; // 沒改密碼
  patch.excludedApps = String(next.excludedApps || '').split(/[\n,，]/).map((s) => s.trim()).filter(Boolean);
  saveConfig(patch);
  token = null; activeAddress = null;
  syncReporter();
  if (!mainWindow) createMainWindow(true); else loadOrbis();
  return true;
});
ipcMain.handle('config:test', async (_e, input) => {
  const results = [];
  for (const raw of input.addresses || []) {
    const address = normalizeAddress(raw);
    if (!address) { results.push({ address: raw, ok: false, message: '網址格式不正確' }); continue; }
    if (!(await reachable(address))) { results.push({ address, ok: false, message: '連不上（手機網頁服務沒開、不同網路，或 Tailscale 未連線）' }); continue; }
    const password = input.password === '********' ? config.password : input.password;
    if (!password) { results.push({ address, ok: true, message: '連得上；未填密碼，無法分享電腦活動' }); continue; }
    try {
      const res = await fetch(address + '/api/auth/token', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }), signal: AbortSignal.timeout(5000) });
      results.push({ address, ok: res.ok, message: res.ok ? '連線與密碼都正確' :
        res.status === 400 ? '連得上，但手機尚未開啟密碼認證' : '連得上，但密碼不正確' });
    } catch { results.push({ address, ok: false, message: '連線中斷' }); }
  }
  return results;
});
ipcMain.handle('app:reconnect', () => { activeAddress = null; loadOrbis(); });
ipcMain.handle('app:setup', () => openSetup());

app.on('second-instance', showMain);
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', (e) => e.preventDefault());

app.whenReady().then(() => {
  app.setAppUserModelId('app.hoshio.desktop');
  loadConfig();
  const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
  tray = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon.resize({ width: 16, height: 16 }));
  tray.on('click', showMain);
  updateTray();
  const hidden = process.argv.includes('--hidden');
  if (config.addresses.length === 0) openSetup();
  else createMainWindow(!hidden);
  syncReporter();
  applyBridge().catch(() => {});
  if (hidden && Notification.isSupported()) {
    new Notification({ title: '星汐 已在背景執行', body: '點系統匣圖示開啟聊天。' }).show();
  }
});
