import * as SecureStore from 'expo-secure-store'
import type { StoredUser } from '../api/types'

const TOKEN_KEY = 'aijob.token'
const USER_KEY = 'aijob.user'

export type Session = { token: string; user: StoredUser }

/** The JWT lives in the Keychain / Keystore, never in plain AsyncStorage. */
export async function loadSession(): Promise<Session | null> {
  const [token, rawUser] = await Promise.all([
    SecureStore.getItemAsync(TOKEN_KEY),
    SecureStore.getItemAsync(USER_KEY),
  ])
  if (!token || !rawUser) {
    return null
  }
  try {
    return { token, user: JSON.parse(rawUser) as StoredUser }
  } catch {
    return null
  }
}

export async function saveSession(session: Session): Promise<void> {
  await SecureStore.setItemAsync(TOKEN_KEY, session.token)
  await SecureStore.setItemAsync(USER_KEY, JSON.stringify(session.user))
}

export async function clearSession(): Promise<void> {
  await Promise.all([SecureStore.deleteItemAsync(TOKEN_KEY), SecureStore.deleteItemAsync(USER_KEY)])
}
