import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/AppLayout'
import { ProtectedRoute } from './components/ProtectedRoute'
import { useAuth } from './auth/AuthContext'
import { loginPathForAnonymousFeature, PROFILE_FEEDBACK_RETURN_TO } from './auth/returnTo'
import { DashboardPage } from './pages/DashboardPage'
import { HomeOrDashboard } from './pages/HomePage'
import { JobsPage } from './pages/JobsPage'
import { JobCatalogDetailPage } from './pages/JobCatalogDetailPage'
import { LoginPage } from './pages/LoginPage'
import { ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'
import { ResumeBuilderEditorPage } from './pages/ResumeBuilderEditorPage'
import { ResumeBuilderListPage } from './pages/ResumeBuilderListPage'
import { ResumeQualityScorePage } from './pages/ResumeQualityScorePage'
import { ResumeStructurePage } from './pages/ResumeStructurePage'
import { ResumesPage } from './pages/ResumesPage'
import { TailorPage } from './pages/TailorPage'

/**
 * Old /feedback URLs used to be a public page. Site ratings now live on the
 * profile, so this only redirects -- it never renders a standalone feedback UI.
 */
function LegacyFeedbackRedirect() {
  const { isAuthenticated } = useAuth()
  if (isAuthenticated) {
    return <Navigate to={PROFILE_FEEDBACK_RETURN_TO} replace />
  }
  return <Navigate to={loginPathForAnonymousFeature(PROFILE_FEEDBACK_RETURN_TO)} replace />
}

const DesignPreviewApp = import.meta.env.DEV
  ? lazy(() => import('./designPreview/DesignPreviewApp'))
  : null

/** V3 prototype, kept beside V1/V2 rather than replacing it so the two can be
    screenshotted at the same viewport for comparison. */
const DesignV3App = import.meta.env.DEV
  ? lazy(() => import('./designPreviewV3/DesignV3App'))
  : null

function DevDesignPreview() {
  if (!DesignPreviewApp) {
    return null
  }
  return (
    <Suspense fallback={null}>
      <DesignPreviewApp />
    </Suspense>
  )
}

function DevDesignV3() {
  if (!DesignV3App) {
    return null
  }
  return (
    <Suspense fallback={null}>
      <DesignV3App />
    </Suspense>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      {/* Compatibility only -- never a page. Old bookmarks redirect to the
          signed-in profile card (or to login that returns there). */}
      <Route path="/feedback" element={<LegacyFeedbackRedirect />} />
      {import.meta.env.DEV ? (
        <Route path="/__design/*" element={<DevDesignPreview />} />
      ) : null}
      {import.meta.env.DEV ? (
        <Route path="/__design-v3/*" element={<DevDesignV3 />} />
      ) : null}
      <Route element={<AppLayout />}>
        {/* Public: job search/list/detail need no account, so a visitor can browse
            real listings before ever signing up. Saving, "applied", and everything
            else stay behind the login wall below. */}
        <Route path="/jobs" element={<JobsPage />} />
        <Route path="/jobs/:catalogJobId" element={<JobCatalogDetailPage />} />
        {/* Public marketing homepage for anonymous visitors. Signed-in users
            are sent to /dashboard so Home and the personal console stay distinct. */}
        <Route path="/" element={<HomeOrDashboard />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/resumes" element={<ResumesPage />} />
          <Route path="/resumes/builder" element={<ResumeBuilderListPage />} />
          <Route path="/resumes/builder/:id" element={<ResumeBuilderEditorPage />} />
          <Route path="/resumes/:id/structure" element={<ResumeStructurePage />} />
          <Route path="/resumes/:id/quality-score" element={<ResumeQualityScorePage />} />
          <Route path="/tailor" element={<TailorPage />} />
          <Route path="/profile" element={<ProfilePage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
