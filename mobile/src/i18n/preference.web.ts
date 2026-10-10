/** Web is a development preview only; see session.web.ts. */
const LANGUAGE_KEY = 'aijob.mobile.language'

export type StoredLanguage = 'zh-CN' | 'en'

export async function loadLanguagePreference(): Promise<StoredLanguage | null> {
  const value = globalThis.localStorage?.getItem(LANGUAGE_KEY)
  return value === 'zh-CN' || value === 'en' ? value : null
}

export async function saveLanguagePreference(language: StoredLanguage): Promise<void> {
  globalThis.localStorage?.setItem(LANGUAGE_KEY, language)
}
