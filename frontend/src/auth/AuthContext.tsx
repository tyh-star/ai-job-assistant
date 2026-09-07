import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import {
  login as loginApi,
  register as registerApi,
  updateEmail as updateEmailApi,
  updateName as updateNameApi,
} from '../api/auth'
import { SESSION_EXPIRED_EVENT } from '../api/client'
import type { StoredUser } from '../api/types'
import { clearSession, getStoredUser, getToken, saveSession } from './session'

interface AuthContextValue {
  user: StoredUser | null
  isAuthenticated: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
  /** Updates the stored name and this context's state immediately -- no re-login needed. */
  updateName: (name: string) => Promise<void>
  /**
   * Verifies `currentPassword` server-side, then replaces the stored token AND user
   * with the fresh ones the server returns -- the old token stops working the moment
   * this resolves, so the frontend must never keep using it.
   */
  updateEmail: (currentPassword: string, newEmail: string) => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<StoredUser | null>(() =>
    getToken() ? getStoredUser() : null,
  )

  const login = useCallback(async (email: string, password: string) => {
    const { data } = await loginApi({ email, password })
    const stored: StoredUser = {
      userId: data.userId,
      name: data.name,
      email: data.email,
      role: data.role,
    }
    saveSession(data.token, stored)
    setUser(stored)
  }, [])

  const register = useCallback(
    async (name: string, email: string, password: string) => {
      await registerApi({ name, email, password })
      await login(email, password)
    },
    [login],
  )

  const logout = useCallback(() => {
    clearSession()
    setUser(null)
  }, [])

  // The axios interceptor clears localStorage directly (it has no React context of
  // its own to update) whenever any request comes back 401 -- this is what keeps this
  // context's `user` state, and therefore the nav bar and every isAuthenticated gate,
  // in sync with that even when the interceptor does NOT also navigate away (e.g. a
  // stale token 401ing while already on the public /jobs page).
  useEffect(() => {
    function handleSessionExpired() {
      setUser(null)
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired)
  }, [])

  const updateName = useCallback(async (name: string) => {
    const { data } = await updateNameApi({ name })
    const next: StoredUser = {
      userId: data.id,
      name: data.name,
      email: data.email,
      role: data.role,
    }
    // Name changes don't touch the token (see UpdateUserNameRequest's backend javadoc)
    // -- only the stored user needs replacing, so the nav bar updates immediately
    // without forcing a fresh login.
    const token = getToken()
    if (token) {
      saveSession(token, next)
    }
    setUser(next)
  }, [])

  const updateEmail = useCallback(async (currentPassword: string, newEmail: string) => {
    const { data } = await updateEmailApi({ currentPassword, newEmail })
    const next: StoredUser = {
      userId: data.user.id,
      name: data.user.name,
      email: data.user.email,
      role: data.user.role,
    }
    // The old token's subject no longer resolves to this account (see JwtUtil's
    // javadoc) -- replace it with the fresh one atomically with the user, never leave
    // a moment where the stored token and stored user disagree.
    saveSession(data.token, next)
    setUser(next)
  }, [])

  const value = useMemo(
    () => ({
      user,
      isAuthenticated: user !== null,
      login,
      register,
      logout,
      updateName,
      updateEmail,
    }),
    [user, login, register, logout, updateName, updateEmail],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}
