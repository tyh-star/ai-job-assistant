import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { getLocales } from 'expo-localization'
import en from './en.json'
import { loadLanguagePreference, saveLanguagePreference } from './preference'
import zhCN from './zh-CN.json'

export type AppLanguage = 'zh-CN' | 'en'

export function languageFromTag(tag: string | null | undefined): AppLanguage {
  return tag?.toLowerCase().startsWith('en') ? 'en' : 'zh-CN'
}

void i18n.use(initReactI18next).init({
  resources: {
    'zh-CN': { translation: zhCN },
    en: { translation: en },
  },
  lng: languageFromTag(getLocales()[0]?.languageTag),
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false },
})

export function currentLanguage(): AppLanguage {
  return languageFromTag(i18n.language)
}

/** Applies the language chosen in the app, if one was saved; the device locale otherwise. */
export async function restoreLanguage(): Promise<void> {
  try {
    const saved = await loadLanguagePreference()
    if (saved && saved !== i18n.language) {
      await i18n.changeLanguage(saved)
    }
  } catch {
    // Keep the device language when the saved choice cannot be read.
  }
}

/** Switches every screen at once, then remembers the choice for the next launch. */
export async function setAppLanguage(language: AppLanguage): Promise<void> {
  await i18n.changeLanguage(language)
  await saveLanguagePreference(language)
}

export default i18n
