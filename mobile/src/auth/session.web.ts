import type { StoredUser } from '../api/types'

/**
 * Web is not a shipping target (the API's CORS list only covers the website). This
 * exists so the screens can be previewed in a desktop browser during development;
 * native builds always use session.ts and SecureStore.
 */
const TOKEN_KEY = 'aijob.mobile.token'
const USER_KEY = 'aijob.mobile.user'

export type Session = { token: string; user: StoredUser }

export async function loadSession(): Promise<Session | null> {
  const token = globalThis.localStorage?.getItem(TOKEN_KEY)
  const rawUser = globalThis.localStorage?.getItem(USER_KEY)
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
  globalThis.localStorage?.setItem(TOKEN_KEY, session.token)
  globalThis.localStorage?.setItem(USER_KEY, JSON.stringify(session.user))
}

export async function clearSession(): Promise<void> {
  globalThis.localStorage?.removeItem(TOKEN_KEY)
  globalThis.localStorage?.removeItem(USER_KEY)
}
