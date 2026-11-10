// AI 想看電腦畫面時：向手機長輪詢取得請求 → 問人類 → 同意才截圖並上傳。
const { desktopCapturer, screen, dialog, Notification } = require('electron');

const MAX_WIDTH = 1920;

class ScreenshotResponder {
  /**
   * @param {() => object} getConfig
   * @param {(force?: boolean) => Promise<{address: string, token: string} | null>} getAuth
   * @param {() => object | null} getForeground 最近一次的前景視窗狀態（來自活動回報）
   */
  constructor(getConfig, getAuth, getForeground) {
    this.getConfig = getConfig;
    this.getAuth = getAuth;
    this.getForeground = getForeground;
    this.running = false;
    this.autoAllowUntil = 0;
    this.handled = new Set();
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.loop();
  }

  stop() { this.running = false; }

  async loop() {
    while (this.running) {
      try {
        const auth = await this.getAuth();
        if (!auth) { await sleep(15_000); continue; }
        const res = await fetch(auth.address + '/api/hoshio/pc/requests', {
          headers: { Authorization: 'Bearer ' + auth.token }, signal: AbortSignal.timeout(35_000),
        });
        if (res.status === 401) { await this.getAuth(true); continue; }
        if (!res.ok) { await sleep(15_000); continue; }
        const { requests } = await res.json();
        for (const request of requests || []) {
          if (this.handled.has(request.id)) continue;
          this.handled.add(request.id);
          this.handle(auth, request).catch(() => {});
        }
        if (this.handled.size > 200) this.handled = new Set([...this.handled].slice(-50));
      } catch {
        await sleep(10_000);
      }
    }
  }

  async handle(auth, request) {
    const config = this.getConfig();
    if (config.allowScreenshot === false) return this.upload(auth, request.id, { ok: false, code: 'disabled' });
    const fg = this.getForeground();
    if (fg && fg.app === '（不分享的程式）') return this.upload(auth, request.id, { ok: false, code: 'excluded_app' });

    if (Date.now() >= this.autoAllowUntil) {
      const { response } = await dialog.showMessageBox({
        type: 'question', title: '星汐', noLink: true, defaultId: 0, cancelId: 2,
        message: 'AI 想看你的螢幕',
        detail: `原因：${request.reason || '（未說明）'}\n\n允許後會截下滑鼠所在螢幕的畫面，送到手機上的 星汐 給 AI 看。`,
        buttons: ['允許這一次', '一小時內都允許', '拒絕'],
      });
      if (response === 2) return this.upload(auth, request.id, { ok: false, code: 'refused' });
      if (response === 1) this.autoAllowUntil = Date.now() + 3600_000;
    }

    try {
      const shot = await capture();
      await this.upload(auth, request.id, { ok: true, imageBase64: shot.jpeg.toString('base64'), width: shot.width, height: shot.height });
      if (Notification.isSupported()) new Notification({ title: '星汐', body: '已把螢幕截圖傳給 AI。' }).show();
    } catch {
      await this.upload(auth, request.id, { ok: false, code: 'capture_failed' });
    }
  }

  async upload(auth, id, body) {
    await fetch(`${auth.address}/api/hoshio/pc/screenshot/${encodeURIComponent(id)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.token },
      body: JSON.stringify(body), signal: AbortSignal.timeout(30_000),
    }).catch(() => null);
  }
}

/** 截下滑鼠所在的那個螢幕，寬度超過 1920 時等比縮小，輸出 JPEG。 */
async function capture() {
  const display = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  let width = Math.round(display.size.width * display.scaleFactor);
  let height = Math.round(display.size.height * display.scaleFactor);
  if (width > MAX_WIDTH) { height = Math.round(height * MAX_WIDTH / width); width = MAX_WIDTH; }
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width, height } });
  const source = sources.find((s) => s.display_id === String(display.id)) || sources[0];
  if (!source || source.thumbnail.isEmpty()) throw new Error('capture_failed');
  const size = source.thumbnail.getSize();
  return { jpeg: source.thumbnail.toJPEG(80), width: size.width, height: size.height };
}

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

module.exports = { ScreenshotResponder };
