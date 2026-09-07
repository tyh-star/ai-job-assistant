import { Link, NavLink, Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { LanguageSwitch } from './LanguageSwitch'

const ICON_CLASS = 'h-4 w-4 shrink-0'

/** A home/grid glyph. Decorative -- the link's own visible text is the accessible label. */
function DashboardIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <rect x="2.5" y="2.5" width="6" height="6" rx="1" />
      <rect x="11.5" y="2.5" width="6" height="6" rx="1" />
      <rect x="2.5" y="11.5" width="6" height="6" rx="1" />
      <rect x="11.5" y="11.5" width="6" height="6" rx="1" />
    </svg>
  )
}

function ResumesIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M5 2.5h7l3 3v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" />
      <path d="M12 2.5V6h3" />
      <path d="M6.5 10.5h7M6.5 13.5h7M6.5 16h4" />
    </svg>
  )
}

function JobsIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <rect x="2.5" y="6" width="15" height="10.5" rx="1.5" />
      <path d="M7 6V4.5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1V6" />
      <path d="M2.5 10.5h15" />
    </svg>
  )
}

function TailorIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M3 17 13 7" />
      <path d="M11.5 5.5 14.5 8.5" />
      <path d="M15.5 2.5v2.5M16.75 3.75h-2.5M4 3l.5 1.5L6 5l-1.5.5L4 7l-.5-1.5L2 5l1.5-.5Z" />
    </svg>
  )
}

/** A person/profile glyph. Decorative -- the link's own visible text is the accessible label. */
function ProfileIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <circle cx="10" cy="6.5" r="3" />
      <path d="M3.5 17c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6" />
    </svg>
  )
}

// `public: true` (just Jobs) is what an anonymous visitor sees; the rest require an
// account and are hidden rather than shown-then-redirected, per the public-landing
// requirement -- an anonymous nav shows only what actually works for a visitor.
const links = [
  { to: '/dashboard', key: 'nav.dashboard', Icon: DashboardIcon, public: false },
  { to: '/resumes', key: 'nav.resumes', Icon: ResumesIcon, public: false },
  { to: '/jobs', key: 'nav.jobs', Icon: JobsIcon, public: true },
  { to: '/tailor', key: 'nav.tailor', Icon: TailorIcon, public: false },
] as const

export function AppLayout() {
  const { t } = useTranslation()
  const { user, isAuthenticated, logout } = useAuth()

  function handleLogout() {
    logout()
    // A full page navigation, not react-router's navigate(): clicking logout from a
    // PROTECTED page races ProtectedRoute's own re-render (which sees isAuthenticated
    // flip to false on the OLD path and redirects to /login?returnTo=... on its own)
    // against this component's navigate('/jobs') -- which one wins is an
    // implementation detail of how the router batches the two, not something this
    // component can rely on. window.location.assign sidesteps the race entirely: it
    // reloads at /jobs from scratch, where AuthProvider re-initializes from the
    // now-cleared localStorage as anonymous, the same way the axios interceptor's own
    // forced navigation already does for an expired/revoked token.
    window.location.assign('/jobs')
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3">
          <div className="flex flex-wrap items-center gap-x-8 gap-y-2">
            <span className="font-semibold text-slate-900">{t('app.name')}</span>
            <nav className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
              {links.filter((link) => link.public || isAuthenticated).map(({ to, key, Icon }) => (
                <NavLink
                  key={to}
                  to={to}
                  className={({ isActive }) =>
                    `flex items-center gap-1.5 ${
                      isActive ? 'font-medium text-slate-900' : 'text-slate-500 hover:text-slate-800'
                    }`
                  }
                >
                  <Icon />
                  {t(key)}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <LanguageSwitch />
            {isAuthenticated ? (
              <>
                {user ? (
                  <NavLink
                    to="/profile"
                    aria-label={t('nav.profile')}
                    className={({ isActive }) =>
                      `flex items-center gap-1.5 text-sm ${
                        isActive ? 'font-medium text-slate-900' : 'text-slate-600 hover:text-slate-900'
                      }`
                    }
                  >
                    <ProfileIcon />
                    <span className="hidden sm:inline">{user.name}</span>
                  </NavLink>
                ) : null}
                <button
                  type="button"
                  className="text-sm text-slate-600 hover:text-slate-900"
                  onClick={handleLogout}
                >
                  {t('nav.logout')}
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="text-sm text-slate-600 hover:text-slate-900">
                  {t('nav.login')}
                </Link>
                <Link to="/register" className="text-sm text-slate-600 hover:text-slate-900">
                  {t('nav.register')}
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
