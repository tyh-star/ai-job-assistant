import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useEffect, useId, useRef, useState, type ReactElement } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { LanguageSwitch } from './LanguageSwitch'
import { BrandMark } from './ui/BrandMark'
import { DashboardHeader } from './DashboardHeader'
import { cx } from './ui/cx'

/** Below 1280px the header switches to a compact hamburger instead of wrapping. */
export const COMPACT_NAV_QUERY = '(max-width: 1279px)'

const ICON_CLASS = 'h-4 w-4 shrink-0'
const NAV_LINK_CLASS =
  'flex min-h-11 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3 text-[length:var(--text-nav)]'

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

function ProfileIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <circle cx="10" cy="6.5" r="3" />
      <path d="M3.5 17c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6" />
    </svg>
  )
}

function ResumeToolsIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M5 2.5h7l3 3v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" />
      <path d="M12 2.5V6h3" />
      <path d="M7 10h6M7 13h4" />
    </svg>
  )
}

function HomeIcon() {
  return (
    <svg className={ICON_CLASS} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <path d="M3.5 9.5 10 3.5l6.5 6V16a1 1 0 0 1-1 1h-3.5v-4.5h-4V17H4.5a1 1 0 0 1-1-1V9.5Z" />
    </svg>
  )
}

function ChevronIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M5 8 10 13 15 8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function MenuIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M3.5 5.5h13M3.5 10h13M3.5 14.5h13" strokeLinecap="round" />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M5 5 15 15M15 5 5 15" strokeLinecap="round" />
    </svg>
  )
}

type NavItem = {
  to: string
  key: string
  Icon: () => ReactElement
  end?: boolean
}

function useCompactNav(): boolean {
  const [compact, setCompact] = useState(() => window.matchMedia(COMPACT_NAV_QUERY).matches)

  useEffect(() => {
    const media = window.matchMedia(COMPACT_NAV_QUERY)
    function onChange() {
      setCompact(media.matches)
    }
    onChange()
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])

  return compact
}

/** The dashboard paints its own full-bleed surface, so it opts out of the
    centred content shell the other pages share. */
function isDashboardPath(pathname: string): boolean {
  return pathname === '/dashboard'
}

function isResumeWorkspacePath(pathname: string): boolean {
  return /^\/resumes\/builder\/\d+/.test(pathname)
}

function featureHref(path: string, isAuthenticated: boolean): string {
  return isAuthenticated ? path : loginPathForAnonymousFeature(path)
}

function mainClassName(pathname: string): string {
  if (isResumeWorkspacePath(pathname)) {
    return 'site-workspace'
  }
  return isDashboardPath(pathname) ? 'site-plain site-main--dashboard' : 'site-shell'
}

export function AppLayout() {
  const { t } = useTranslation()
  const { user, isAuthenticated, logout } = useAuth()
  const location = useLocation()
  const compact = useCompactNav()
  const [scrolled, setScrolled] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [toolsOpen, setToolsOpen] = useState(false)
  const menuId = useId()
  const toolsId = useId()
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const toolsRef = useRef<HTMLDivElement>(null)
  const toolsButtonRef = useRef<HTMLButtonElement>(null)

  const resumeItems = [
    { to: '/resumes', key: 'nav.myResumes' },
    { to: '/resumes/builder', key: 'nav.resumeBuilder' },
    { to: '/resumes', key: 'nav.qualityScore' },
  ]

  useEffect(() => {
    function onScroll() {
      setScrolled(window.scrollY > 4)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    setMenuOpen(false)
    setToolsOpen(false)
  }, [location.pathname, location.search, compact])

  useEffect(() => {
    if (!toolsOpen) {
      return
    }
    const first = toolsRef.current?.querySelector<HTMLElement>('[role="menuitem"]')
    first?.focus()

    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setToolsOpen(false)
        toolsButtonRef.current?.focus()
      }
    }
    function onPointer(event: MouseEvent) {
      if (!toolsRef.current?.contains(event.target as Node)) {
        setToolsOpen(false)
      }
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointer)
    }
  }, [toolsOpen])

  useEffect(() => {
    if (!menuOpen) {
      return
    }
    const panel = menuRef.current
    const first = panel?.querySelector<HTMLElement>('a[href], button:not([disabled])')
    first?.focus()

    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMenuOpen(false)
        menuButtonRef.current?.focus()
        return
      }
      if (event.key !== 'Tab' || !panel) {
        return
      }
      const items = [...panel.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')]
      if (items.length === 0) {
        return
      }
      const firstItem = items[0]
      const lastItem = items[items.length - 1]
      if (event.shiftKey && document.activeElement === firstItem) {
        event.preventDefault()
        lastItem.focus()
      } else if (!event.shiftKey && document.activeElement === lastItem) {
        event.preventDefault()
        firstItem.focus()
      }
    }

    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [menuOpen])

  function handleLogout() {
    logout()
    window.location.assign('/')
  }

  const home: NavItem = { to: '/', key: 'nav.home', Icon: HomeIcon, end: true }
  const dashboard: NavItem = { to: '/dashboard', key: 'nav.dashboard', Icon: DashboardIcon }
  const jobs: NavItem = { to: '/jobs', key: 'nav.jobs', Icon: JobsIcon }
  const primaryLinks: NavItem[] = isAuthenticated ? [dashboard, jobs] : [home, jobs]
  const resumesActive = location.pathname.startsWith('/resumes')

  function navClass(isActive: boolean, stacked: boolean) {
    return cx(
      stacked ? 'flex min-h-11 min-w-0 items-center gap-2 rounded-lg px-3 py-2.5 text-[length:var(--text-nav)]' : NAV_LINK_CLASS,
      isActive ? 'bg-accent-soft font-medium text-brand' : 'text-muted hover:bg-slate-100 hover:text-ink',
    )
  }

  function renderPrimaryLinks(layout: 'row' | 'stack') {
    const stacked = layout === 'stack'
    return primaryLinks.map(({ to, key, Icon, end }) => (
      <NavLink key={to} to={to} end={end} className={({ isActive }) => navClass(isActive, stacked)}>
        <Icon />
        {t(key)}
      </NavLink>
    ))
  }

  function renderResumeLinks(layout: 'menu' | 'stack') {
    return resumeItems.map(({ to, key }) => {
      const href = featureHref(to, isAuthenticated)
      if (layout === 'menu') {
        return (
          <Link
            key={key}
            role="menuitem"
            to={href}
            className="flex min-h-11 items-center whitespace-nowrap px-3 text-[length:var(--text-nav)] text-ink hover:bg-accent-soft"
            onClick={() => setToolsOpen(false)}
          >
            {t(key)}
          </Link>
        )
      }
      return (
        <Link
          key={key}
          to={href}
          className="flex min-h-11 items-center rounded-lg px-3 text-[length:var(--text-nav)] text-muted hover:bg-slate-100 hover:text-ink"
        >
          {t(key)}
        </Link>
      )
    })
  }

  function renderTailor(layout: 'row' | 'stack') {
    const stacked = layout === 'stack'
    const href = featureHref('/tailor', isAuthenticated)
    return (
      <NavLink
        to={href}
        className={({ isActive }) => navClass(isActive && !href.startsWith('/login'), stacked)}
      >
        <TailorIcon />
        {t('nav.tailor')}
      </NavLink>
    )
  }

  function renderAuthControls(layout: 'row' | 'stack') {
    const stacked = layout === 'stack'
    if (isAuthenticated) {
      return (
        <>
          {user ? (
            <NavLink
              to="/profile"
              aria-label={`${user.name}, ${t('nav.profile')}`}
              className={({ isActive }) =>
                cx(
                  stacked
                    ? 'flex min-h-11 items-center gap-2 rounded-lg border border-line bg-card px-3 py-2.5 text-[length:var(--text-nav)]'
                    : 'flex min-h-11 shrink-0 items-center gap-2 whitespace-nowrap rounded-full border border-line bg-card px-3 text-[length:var(--text-nav)]',
                  isActive ? 'border-accent bg-accent-soft text-brand' : 'text-ink hover:border-accent/40',
                )
              }
            >
              <ProfileIcon />
              <span className={cx('min-w-0 truncate', stacked ? 'inline' : 'hidden sm:inline')}>{user.name}</span>
              <span className={cx('text-muted', stacked ? 'inline' : 'hidden sm:inline')}>{t('nav.profile')}</span>
            </NavLink>
          ) : null}
          <button
            type="button"
            className={
              stacked
                ? 'min-h-11 rounded-lg px-3 py-2.5 text-left text-[length:var(--text-nav)] text-muted hover:bg-slate-100 hover:text-ink'
                : 'min-h-11 shrink-0 whitespace-nowrap rounded-full px-3 text-[length:var(--text-nav)] text-muted hover:bg-slate-100 hover:text-ink'
            }
            onClick={handleLogout}
          >
            {t('nav.logout')}
          </button>
        </>
      )
    }
    return (
      <>
        <Link
          to="/login"
          className={
              stacked
              ? 'flex min-h-11 items-center rounded-lg px-3 py-2.5 text-[length:var(--text-nav)] text-muted hover:bg-slate-100 hover:text-ink'
              : 'inline-flex min-h-11 shrink-0 items-center whitespace-nowrap rounded-full px-3 text-[length:var(--text-nav)] text-muted hover:bg-slate-100 hover:text-ink'
          }
        >
          {t('nav.login')}
        </Link>
        <Link
          to="/register"
          className={
              stacked
              ? 'inline-flex min-h-11 items-center justify-center rounded-lg bg-accent px-4 py-2 text-[length:var(--text-nav)] font-medium text-white hover:bg-brand'
              : 'inline-flex min-h-11 shrink-0 items-center whitespace-nowrap rounded-full bg-accent px-4 text-[length:var(--text-nav)] font-medium text-white hover:bg-brand'
          }
        >
          {t('nav.register')}
        </Link>
      </>
    )
  }

  const logoTo = isAuthenticated ? '/dashboard' : '/'

  /* The console has its own navigation (see DashboardHeader). It is swapped in
     only for that one route; every other page keeps the shared header below. */
  if (isDashboardPath(location.pathname)) {
    return (
      <div className="min-w-0 overflow-x-hidden">
        <DashboardHeader onLogout={handleLogout} />
        <main className={mainClassName(location.pathname)}>
          <Outlet />
        </main>
      </div>
    )
  }

  return (
    <div className="min-h-screen min-w-0 overflow-x-hidden">
      <header className="site-header" data-scrolled={scrolled ? 'true' : 'false'}>
        <div className="site-header-inner">
          <Link to={logoTo} className="shrink-0">
            <BrandMark size="sm" />
          </Link>
          {!compact ? (
            <nav className="site-header-nav" aria-label={t('nav.menu')}>
              {renderPrimaryLinks('row')}
              <div className="relative" ref={toolsRef}>
                <button
                  ref={toolsButtonRef}
                  type="button"
                  className={navClass(resumesActive, false)}
                  aria-expanded={toolsOpen}
                  aria-haspopup="menu"
                  aria-controls={toolsId}
                  onClick={() => setToolsOpen((open) => !open)}
                >
                  <ResumeToolsIcon />
                  {t('nav.resumeTools')}
                  <ChevronIcon />
                </button>
                {toolsOpen ? (
                  <div
                    id={toolsId}
                    role="menu"
                    aria-label={t('nav.resumeTools')}
                    className="absolute left-0 top-full z-50 mt-1 min-w-44 rounded-lg border border-line bg-card py-1 shadow-card"
                  >
                    {renderResumeLinks('menu')}
                  </div>
                ) : null}
              </div>
              {renderTailor('row')}
            </nav>
          ) : (
            <div />
          )}
          <div className="site-header-actions">
            <LanguageSwitch />
            {!compact ? renderAuthControls('row') : null}
            {compact ? (
              <button
                ref={menuButtonRef}
                type="button"
                className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-ink hover:bg-slate-100"
                aria-label={menuOpen ? t('nav.closeMenu') : t('nav.openMenu')}
                aria-expanded={menuOpen}
                aria-controls={menuId}
                onClick={() => setMenuOpen((open) => !open)}
              >
                {menuOpen ? <CloseIcon /> : <MenuIcon />}
              </button>
            ) : null}
          </div>
        </div>
        {compact && menuOpen ? (
          <div
            ref={menuRef}
            id={menuId}
            role="dialog"
            aria-modal="true"
            aria-label={t('nav.menu')}
            className="border-t border-line bg-card px-4 py-3"
          >
            <nav className="flex min-w-0 flex-col gap-1">
              {renderPrimaryLinks('stack')}
              <p className="px-3 pt-2 text-xs font-medium uppercase tracking-wide text-muted">{t('nav.resumeTools')}</p>
              {renderResumeLinks('stack')}
              {renderTailor('stack')}
            </nav>
            <div className="mt-3 flex min-w-0 flex-col gap-1 border-t border-line pt-3">
              {renderAuthControls('stack')}
            </div>
          </div>
        ) : null}
      </header>
      <main className={mainClassName(location.pathname)}>
        <Outlet />
      </main>
    </div>
  )
}
