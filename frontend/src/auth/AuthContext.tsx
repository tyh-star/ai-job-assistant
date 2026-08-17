import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { login as loginApi, register as registerApi } from '../api/auth'
import type { StoredUser } from '../api/types'
import { clearSession, getStoredUser, getToken, saveSession } from './session'

interface AuthContextValue {
  user: StoredUser | null
  isAuthenticated: boolean
  login: (email: string, password: string) => Promise<void>
  register: (name: string, email: string, password: string) => Promise<void>
  logout: () => void
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

  const value = useMemo(
    () => ({
      user,
      isAuthenticated: user !== null,
      login,
      register,
      logout,
    }),
    [user, login, register, logout],
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
