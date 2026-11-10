// 電腦活動回報：每 5 秒讀一次前景視窗與閒置時間，合併成「使用某程式的時間段」，
// 每分鐘送到手機上的 星汐（POST /api/hoshio/pc/activity）。手機連不上時先暫存，最多保留 6 小時。
const { spawn } = require('child_process');
const os = require('os');

// 用 PowerShell 呼叫 Win32 API；不需要安裝任何原生模組。
const PS_SCRIPT = String.raw`
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class OrbisWin {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [StructLayout(LayoutKind.Sequential)] public struct LII { public uint cbSize; public uint dwTime; }
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LII p);
  public static uint Idle() { var l = new LII(); l.cbSize = (uint)Marshal.SizeOf(l); GetLastInputInfo(ref l); return unchecked((uint)Environment.TickCount - l.dwTime) / 1000; }
}
"@
[Console]::OutputEncoding = [Text.Encoding]::UTF8
while ($true) {
  $h = [OrbisWin]::GetForegroundWindow()
  $sb = New-Object Text.StringBuilder 512
  [void][OrbisWin]::GetWindowText($h, $sb, 512)
  $procId = 0
  [void][OrbisWin]::GetWindowThreadProcessId($h, [ref]$procId)
  $name = ''; $desc = ''
  try { $p = Get-Process -Id $procId -ErrorAction Stop; $name = $p.ProcessName; try { $desc = $p.MainModule.FileVersionInfo.FileDescription } catch {} } catch {}
  $o = @{ proc = $name; desc = $desc; title = $sb.ToString(); idle = [OrbisWin]::Idle() } | ConvertTo-Json -Compress
  # 電腦版關閉或當掉時輸出管道會斷開，這時直接結束，不留下背景程序。
  try { [Console]::Out.WriteLine($o); [Console]::Out.Flush() } catch { exit }
  if ([Console]::Out -eq $null) { exit }
  Start-Sleep -Seconds 5
}
`;

const IDLE_AWAY_SECONDS = 5 * 60;      // 超過 5 分鐘沒操作視為離開
const MAX_PENDING_MS = 6 * 3600 * 1000; // 手機連不上時最多暫存 6 小時
const REPORT_EVERY_MS = 60 * 1000;
const LOCK_PROCESSES = new Set(['lockapp', 'logonui']);

class ActivityReporter {
  /**
   * @param {() => object} getConfig 目前設定
   * @param {(report: object) => Promise<boolean>} send 送出回報，成功回傳 true
   * @param {(status: string) => void} onStatus 狀態文字（顯示在系統匣）
   */
  constructor(getConfig, send, onStatus) {
    this.getConfig = getConfig;
    this.send = send;
    this.onStatus = onStatus;
    this.current = null;   // { app, title, start, last }
    this.latest = null;    // 最近一次讀到的狀態
    this.pending = [];
    this.child = null;
    this.timer = null;
    this.buffer = '';
  }

  start() {
    if (this.child) return;
    const encoded = Buffer.from(PS_SCRIPT, 'utf16le').toString('base64');
    this.child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded],
      { windowsHide: true });
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk) => {
      this.buffer += chunk;
      let index;
      while ((index = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, index).trim();
        this.buffer = this.buffer.slice(index + 1);
        if (line.startsWith('{')) { try { this.sample(JSON.parse(line)); } catch { /* 忽略壞行 */ } }
      }
    });
    this.child.on('exit', () => { this.child = null; });
    this.timer = setInterval(() => this.flush(), REPORT_EVERY_MS);
    this.onStatus('電腦活動分享中');
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (this.child) this.child.kill();
    this.child = null;
    this.closeCurrent(Date.now());
    this.flush().finally(() => this.onStatus('電腦活動分享已暫停'));
  }

  appName(sample) {
    const name = (sample.desc || '').trim() || (sample.proc || '').trim();
    return name || '未知程式';
  }

  sample(sample) {
    const now = Date.now();
    const config = this.getConfig();
    const proc = (sample.proc || '').toLowerCase();
    const excluded = (config.excludedApps || []).map((s) => s.trim().toLowerCase()).filter(Boolean);
    const locked = LOCK_PROCESSES.has(proc);
    const away = locked || sample.idle >= IDLE_AWAY_SECONDS;
    const hidden = excluded.some((e) => proc === e || this.appName(sample).toLowerCase().includes(e));
    this.latest = { app: hidden ? '（不分享的程式）' : this.appName(sample),
      title: hidden || !config.shareTitles ? null : (sample.title || null), idleSeconds: sample.idle || 0, locked };

    if (away || hidden || !proc) { this.closeCurrent(now); return; }
    const app = this.latest.app;
    const title = this.latest.title;
    if (this.current && this.current.app === app && this.current.title === title) { this.current.last = now; return; }
    this.closeCurrent(now);
    this.current = { app, title, start: now, last: now };
  }

  closeCurrent(now) {
    const c = this.current;
    this.current = null;
    if (!c) return;
    const end = Math.min(now, c.last + 10_000);
    if (end - c.start >= 30_000) this.pending.push({ app: c.app, title: c.title, start: c.start, end });
  }

  async flush() {
    const now = Date.now();
    // 進行中的段落也先切一段送出，避免手機端長時間看不到。
    if (this.current && now - this.current.start >= 60_000) {
      const c = this.current;
      this.pending.push({ app: c.app, title: c.title, start: c.start, end: c.last });
      this.current = { ...c, start: c.last };
    }
    this.pending = this.pending.filter((s) => s.end >= now - MAX_PENDING_MS);
    const segments = this.pending.slice(0, 1000);
    const report = { device: this.getConfig().deviceName || os.hostname(), reportedAt: now, current: this.latest, segments };
    try {
      if (await this.send(report)) {
        this.pending = this.pending.slice(segments.length);
        this.onStatus('電腦活動分享中 · 已同步');
      } else {
        this.onStatus(`電腦活動分享中 · 手機未連線（暫存 ${this.pending.length} 段）`);
      }
    } catch {
      this.onStatus(`電腦活動分享中 · 手機未連線（暫存 ${this.pending.length} 段）`);
    }
  }
}

module.exports = { ActivityReporter };
