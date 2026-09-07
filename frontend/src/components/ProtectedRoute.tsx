import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { loginPathWithReturnTo } from '../auth/returnTo'

export function ProtectedRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  if (!isAuthenticated) {
    // Carries the visitor back to this exact path (query string included) once they
    // log in -- see LoginPage's own use of returnTo.
    const currentPath = `${location.pathname}${location.search}`
    return <Navigate to={loginPathWithReturnTo(currentPath)} replace />
  }
  return <Outlet />
}
