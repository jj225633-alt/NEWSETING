import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import enUSCommon from "./locales/en-US/common.json";
import enUSInput from "./locales/en-US/input.json";
import enUSMarkdown from "./locales/en-US/markdown.json";
import enUSMessage from "./locales/en-US/message.json";
import enUSPage from "./locales/en-US/page.json";
import zhCNCommon from "./locales/zh-CN/common.json";
import zhCNInput from "./locales/zh-CN/input.json";
import zhCNMarkdown from "./locales/zh-CN/markdown.json";
import zhCNMessage from "./locales/zh-CN/message.json";
import zhCNPage from "./locales/zh-CN/page.json";
import zhTWCommon from "./locales/zh-TW/common.json";
import zhTWInput from "./locales/zh-TW/input.json";
import zhTWMarkdown from "./locales/zh-TW/markdown.json";
import zhTWMessage from "./locales/zh-TW/message.json";
import zhTWPage from "./locales/zh-TW/page.json";

const SUPPORTED_LANGUAGES = ["zh-TW", "zh-CN", "en-US"] as const;

function getInitialLanguage(): (typeof SUPPORTED_LANGUAGES)[number] {
  if (typeof window === "undefined") {
    return "zh-TW";
  }

  const fromStorage = window.localStorage.getItem("lang");
  if (fromStorage === "zh-TW" || fromStorage === "zh-CN" || fromStorage === "en-US") {
    return fromStorage;
  }

  const browserLanguage = window.navigator.language;
  // 星汐：中文一律先用繁體；在設定裡仍可切換成簡體
  return browserLanguage.startsWith("zh") ? "zh-TW" : "en-US";
}

void i18n.use(initReactI18next).init({
  resources: {
    "zh-TW": {
      common: zhTWCommon,
      input: zhTWInput,
      markdown: zhTWMarkdown,
      message: zhTWMessage,
      page: zhTWPage,
    },
    "zh-CN": {
      common: zhCNCommon,
      input: zhCNInput,
      markdown: zhCNMarkdown,
      message: zhCNMessage,
      page: zhCNPage,
    },
    "en-US": {
      common: enUSCommon,
      input: enUSInput,
      markdown: enUSMarkdown,
      message: enUSMessage,
      page: enUSPage,
    },
  },
  lng: getInitialLanguage(),
  fallbackLng: "zh-TW",
  supportedLngs: [...SUPPORTED_LANGUAGES],
  defaultNS: "common",
  ns: ["common", "input", "markdown", "message", "page"],
  interpolation: {
    escapeValue: false,
  },
});

void i18n.on("languageChanged", (language) => {
  if (typeof window !== "undefined") {
    window.localStorage.setItem("lang", language);
  }
});

export default i18n;
