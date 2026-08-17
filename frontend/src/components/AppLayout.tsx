import { NavLink, Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { LanguageSwitch } from './LanguageSwitch'

const links = [
  { to: '/', key: 'nav.dashboard' },
  { to: '/resumes', key: 'nav.resumes' },
  { to: '/jobs', key: 'nav.jobs' },
  { to: '/tailor', key: 'nav.tailor' },
] as const

export function AppLayout() {
  const { t } = useTranslation()
  const { user, logout } = useAuth()

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <div className="flex items-center gap-8">
            <span className="font-semibold text-slate-900">{t('app.name')}</span>
            <nav className="flex gap-4 text-sm">
              {links.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.to === '/'}
                  className={({ isActive }) =>
                    isActive
                      ? 'font-medium text-slate-900'
                      : 'text-slate-500 hover:text-slate-800'
                  }
                >
                  {t(link.key)}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <LanguageSwitch />
            {user ? (
              <span className="hidden text-sm text-slate-600 sm:inline">{user.name}</span>
            ) : null}
            <button
              type="button"
              className="text-sm text-slate-600 hover:text-slate-900"
              onClick={logout}
            >
              {t('nav.logout')}
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
