import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { configureApiClient } from '../api/client'
import { authApi, usersApi } from '../api/endpoints'
import type { StoredUser, UserResponse } from '../api/types'
import { clearSession, loadSession, saveSession } from './session'

export type AuthStatus = 'restoring' | 'signedOut' | 'signedIn'

/** Why the app signed out on its own, for the login screen to explain. */
export type SignOutReason = 'expired' | 'passwordChanged' | 'accountDeleted' | 'sessionStoreFailed'

/** The server accepted a new email and token, but this phone could not store them. */
export class SessionStoreError extends Error {
  constructor() {
    super('The new session could not be stored on this device')
    this.name = 'SessionStoreError'
  }
}

type AuthContextValue = {
  status: AuthStatus
  user: StoredUser | null
  signOutReason: SignOutReason | null
  acknowledgeSignOutReason: () => void
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  /** Keeps the stored user in step with a fresh GET /users/me. */
  syncUser: (user: UserResponse) => void
  updateName: (name: string) => Promise<UserResponse>
  /** Stores the new token before anything uses it; throws SessionStoreError if it cannot. */
  updateEmail: (currentPassword: string, newEmail: string) => Promise<UserResponse>
  /** Signs out on success: the server has revoked every earlier token for this account. */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
  /** Signs out on success. */
  deleteAccount: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function toStoredUser(user: UserResponse): StoredUser {
  return { userId: user.id, name: user.name, email: user.email, role: user.role }
}

function sameUser(a: StoredUser, b: StoredUser): boolean {
  return a.userId === b.userId && a.name === b.name && a.email === b.email && a.role === b.role
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('restoring')
  const [user, setUser] = useState<StoredUser | null>(null)
  const [signOutReason, setSignOutReason] = useState<SignOutReason | null>(null)
  // The API client reads the token synchronously on every request.
  const tokenRef = useRef<string | null>(null)
  // While an email change swaps tokens, a 401 answering the old token is expected.
  const swappingToken = useRef(false)

  const signOutLocally = useCallback(async (reason: SignOutReason | null) => {
    tokenRef.current = null
    setSignOutReason(reason)
    setUser(null)
    setStatus('signedOut')
    try {
      await clearSession()
    } catch {
      // The in-memory session is already gone; nothing stored is used without a token.
    }
  }, [])

  useEffect(() => {
    configureApiClient({
      getToken: () => tokenRef.current,
      onUnauthorized: (tokenUsed) => {
        if (tokenRef.current === null || swappingToken.current || tokenUsed !== tokenRef.current) {
          return
        }
        void signOutLocally('expired')
      },
    })
  }, [signOutLocally])

  useEffect(() => {
    let cancelled = false
    loadSession()
      .catch(() => null)
      .then((session) => {
        if (cancelled) {
          return
        }
        tokenRef.current = session?.token ?? null
        setUser(session?.user ?? null)
        setStatus(session ? 'signedIn' : 'signedOut')
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const data = await authApi.login({ email: email.trim(), password })
    const stored: StoredUser = {
      userId: data.userId,
      name: data.name,
      email: data.email,
      role: data.role,
    }
    await saveSession({ token: data.token, user: stored })
    tokenRef.current = data.token
    setSignOutReason(null)
    setUser(stored)
    setStatus('signedIn')
  }, [])

  const register = useCallback(
    async (name: string, email: string, password: string) => {
      await authApi.register({ name: name.trim(), email: email.trim(), password })
      await login(email, password)
    },
    [login],
  )

  const logout = useCallback(() => signOutLocally(null), [signOutLocally])

  const acknowledgeSignOutReason = useCallback(() => setSignOutReason(null), [])

  const syncUser = useCallback((fresh: UserResponse) => {
    const next = toStoredUser(fresh)
    setUser((current) => {
      if (!current || current.userId !== next.userId || sameUser(current, next)) {
        return current
      }
      const token = tokenRef.current
      if (token) {
        void saveSession({ token, user: next }).catch(() => undefined)
      }
      return next
    })
  }, [])

  const updateName = useCallback(async (name: string) => {
    const data = await usersApi.updateName({ name })
    const next = toStoredUser(data)
    const token = tokenRef.current
    if (token) {
      try {
        await saveSession({ token, user: next })
      } catch {
        // The name is saved on the server; the profile refetches it on next launch.
      }
    }
    setUser(next)
    return data
  }, [])

  const updateEmail = useCallback(
    async (currentPassword: string, newEmail: string) => {
      swappingToken.current = true
      try {
        const data = await usersApi.updateEmail({ currentPassword, newEmail })
        const next = toStoredUser(data.user)
        try {
          await saveSession({ token: data.token, user: next })
        } catch {
          // The old token no longer works on the server and the new one is not safely
          // stored, so the only honest state is signed out.
          await signOutLocally('sessionStoreFailed')
          throw new SessionStoreError()
        }
        tokenRef.current = data.token
        setUser(next)
        return data.user
      } finally {
        swappingToken.current = false
      }
    },
    [signOutLocally],
  )

  const changePassword = useCallback(
    async (currentPassword: string, newPassword: string) => {
      await usersApi.changePassword({ currentPassword, newPassword })
      await signOutLocally('passwordChanged')
    },
    [signOutLocally],
  )

  const deleteAccount = useCallback(async () => {
    await usersApi.deleteMe()
    await signOutLocally('accountDeleted')
  }, [signOutLocally])

  const value = useMemo(
    () => ({
      status,
      user,
      signOutReason,
      acknowledgeSignOutReason,
      login,
      register,
      logout,
      syncUser,
      updateName,
      updateEmail,
      changePassword,
      deleteAccount,
    }),
    [
      status,
      user,
      signOutReason,
      acknowledgeSignOutReason,
      login,
      register,
      logout,
      syncUser,
      updateName,
      updateEmail,
      changePassword,
      deleteAccount,
    ],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return value
}
