// 用 Electron 內建的影像處理把 192px 的 App 圖示放大成安裝程式需要的 256px。
// 執行：npx electron scripts/make-icon.js
const { app, nativeImage } = require('electron');
const fs = require('fs');
const path = require('path');
app.whenReady().then(() => {
  const src = nativeImage.createFromPath(path.join(__dirname, '..', 'src', 'icon.png'));
  const out = src.resize({ width: 256, height: 256, quality: 'best' });
  fs.writeFileSync(path.join(__dirname, '..', 'build', 'icon.png'), out.toPNG());
  console.log('build/icon.png 256x256 written');
  app.quit();
});
