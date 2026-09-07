import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/AppLayout'
import { ProtectedRoute } from './components/ProtectedRoute'
import { DashboardPage } from './pages/DashboardPage'
import { JobsPage } from './pages/JobsPage'
import { LoginPage } from './pages/LoginPage'
import { ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'
import { ResumeQualityScorePage } from './pages/ResumeQualityScorePage'
import { ResumeStructurePage } from './pages/ResumeStructurePage'
import { ResumesPage } from './pages/ResumesPage'
import { TailorPage } from './pages/TailorPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route element={<AppLayout />}>
        {/* Public: job search/list/detail need no account, so a visitor can browse
            real listings before ever signing up. Saving, "applied", and everything
            else stay behind the login wall below. */}
        <Route path="/jobs" element={<JobsPage />} />
        {/* The root path is ALWAYS just a redirect to /jobs -- for every visitor,
            signed in or not. It is never a page of its own (nothing renders here, so
            nothing can 401 here either), and it never varies by auth state -- a
            signed-in user reaches their Dashboard through the nav bar's own link
            (/dashboard), never by landing on "/". */}
        <Route path="/" element={<Navigate to="/jobs" replace />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/resumes" element={<ResumesPage />} />
          <Route path="/resumes/:id/structure" element={<ResumeStructurePage />} />
          <Route path="/resumes/:id/quality-score" element={<ResumeQualityScorePage />} />
          <Route path="/tailor" element={<TailorPage />} />
          <Route path="/profile" element={<ProfilePage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/jobs" replace />} />
    </Routes>
  )
}
