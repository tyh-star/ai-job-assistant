import * as SecureStore from 'expo-secure-store'

const LANGUAGE_KEY = 'aijob.language'

export type StoredLanguage = 'zh-CN' | 'en'

/** Only the UI language is kept here; it is not sensitive and never includes the token. */
export async function loadLanguagePreference(): Promise<StoredLanguage | null> {
  const value = await SecureStore.getItemAsync(LANGUAGE_KEY)
  return value === 'zh-CN' || value === 'en' ? value : null
}

export async function saveLanguagePreference(language: StoredLanguage): Promise<void> {
  await SecureStore.setItemAsync(LANGUAGE_KEY, language)
}
