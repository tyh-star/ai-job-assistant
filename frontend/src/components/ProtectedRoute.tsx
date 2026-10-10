import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { loginPathAfterSessionExpiry, loginPathForAnonymousFeature } from '../auth/returnTo'
import { hasSessionExpiredMark } from '../auth/session'

export function ProtectedRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    // Carries the visitor back to this exact path (query string included) once they
    // log in -- see LoginPage's own use of returnTo. A session that just expired says
    // so, instead of looking like a first visit.
    const currentPath = `${location.pathname}${location.search}`
    const target = hasSessionExpiredMark()
      ? loginPathAfterSessionExpiry(currentPath)
      : loginPathForAnonymousFeature(currentPath)
    return <Navigate to={target} replace />
  }
  return <Outlet />
}
