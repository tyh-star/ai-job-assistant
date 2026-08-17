import i18n from 'i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import zhCN from './locales/zh-CN.json'

function htmlLang(lng: string) {
  return lng.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en'
}

void i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      'zh-CN': { translation: zhCN },
    },
    fallbackLng: 'en',
    supportedLngs: ['en', 'zh-CN'],
    interpolation: { escapeValue: false },
    detection: {
      order: ['localStorage', 'navigator'],
      lookupLocalStorage: 'aijob.lang',
      caches: ['localStorage'],
      convertDetectedLanguage: (lng: string) => htmlLang(lng),
    },
  })
  .then(() => {
    document.documentElement.lang = htmlLang(i18n.resolvedLanguage ?? i18n.language)
  })

i18n.on('languageChanged', (lng) => {
  document.documentElement.lang = htmlLang(lng)
})

export default i18n
