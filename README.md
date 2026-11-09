# 星汐 Hoshio

星汐是一個原生 Android AI 陪伴與聊天 App，手機和電腦（透過內建 Web 伺服器，用瀏覽器開啟）都能使用。

## 下載

到 [Releases → 星汐 最新構建](../../releases/tag/latest-build) 下載 APK：
- 一般手機選 `arm64-v8a`
- 不確定就選 `universal`

每次推送程式碼到 `master`，GitHub Actions 會自動重新編譯（見 `.github/workflows/build-hoshio.yml`）。

## 規劃中的功能

- 記憶
- 螢幕觀察
- 手機狀態與地理位置感知

## 來源與授權

星汐基於 [RikkaHub](https://github.com/rikkahub/rikkahub)（作者 re-ovo 及貢獻者）修改而來，不是 RikkaHub 官方版本。
本專案沿用 RikkaHub 的 [GNU AGPL-3.0](LICENSE) 授權：如果你散布修改後的版本，必須同時公開對應的原始碼並保留原作者的版權聲明。

RikkaHub 原始說明：[繁體中文](README_ZH_TW.md) · [简体中文](README_ZH_CN.md)
