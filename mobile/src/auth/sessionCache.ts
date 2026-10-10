import { useEffect, useRef } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import type { AuthStatus } from './AuthContext'

/**
 * Nothing fetched for one account may be shown to the next one on this phone: on
 * sign-out, or if a different account ever takes over, in-flight requests are
 * cancelled and every cached query and mutation is dropped.
 */
export function useSessionCacheGuard(status: AuthStatus, userId: number | null, queryClient: QueryClient): void {
  const previousUserId = useRef<number | null>(userId)

  useEffect(() => {
    const switched = previousUserId.current !== null && userId !== null && previousUserId.current !== userId
    if (status === 'signedOut' || switched) {
      void queryClient.cancelQueries()
      queryClient.clear()
    }
    previousUserId.current = userId
  }, [status, userId, queryClient])
}
