import { useEffect, useMemo, useRef } from 'react'
import { useAuth } from '../auth/AuthContext'

export type SessionTicket = {
  /** The account the request was started for. */
  userId: number | null
  /** False once the screen is gone or another account (or nobody) is signed in. */
  isCurrent: () => boolean
}

/**
 * Lets an async action check, after every await, that it still belongs to the account
 * that started it. A response that arrives after logout or an account switch is dropped
 * instead of updating the screen or writing into another account's cache.
 */
export function useSessionGuard(): { capture: () => SessionTicket } {
  const userId = useAuth().user?.userId ?? null
  const userRef = useRef(userId)
  userRef.current = userId
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  return useMemo(
    () => ({
      capture: () => {
        const start = userRef.current
        return { userId: start, isCurrent: () => mounted.current && userRef.current === start }
      },
    }),
    [],
  )
}
