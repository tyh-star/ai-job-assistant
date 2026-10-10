import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { COPY, type PreviewLang } from './copy'
import { PERSON } from './fixture'

const COMPACT_NAV_QUERY = '(max-width: 1279px)'

function BrandIcon() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <rect x="2.5" y="7" width="15" height="10" rx="1.5" />
      <path d="M7 7V5.2A1.2 1.2 0 0 1 8.2 4h3.6A1.2 1.2 0 0 1 13 5.2V7" />
      <path d="M2.5 10.5h15" />
      <path d="M14.2 3.2 15 1.8l.8 1.4 1.5.2-1.1 1.1.3 1.5-1.5-.8-1.5.8.3-1.5-1.1-1.1Z" fill="currentColor" stroke="none" />
    </svg>
  )
}

function ChevronIcon() {
  return (
    <svg className="ds-nav-chevron" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M5 8 10 13 15 8" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  )
}

function MenuIcon() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M3.5 5.5h13M3.5 10h13M3.5 14.5h13" />
    </svg>
  )
}

function CloseIcon() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M5 5 15 15M15 5 5 15" />
    </svg>
  )
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

function previewHomePath(search: string): string {
  const chrome = new URLSearchParams(search).get('chrome')
  return chrome === '0' ? '/__design/home?chrome=0' : '/__design/home'
}

export function PreviewHeader({
  lang,
  onLangChange,
  active,
  signedIn = false,
  previewUserName,
}: {
  lang: PreviewLang
  onLangChange: (next: PreviewLang) => void
  active: 'home' | 'console' | 'jobs' | 'resumes' | 'tailor'
  signedIn?: boolean
  previewUserName?: string
}) {
  const { t, i18n } = useTranslation()
  const { user, isAuthenticated, logout } = useAuth()
  const location = useLocation()
  const previewName = previewUserName ?? PERSON.name
  const sessionUser = signedIn ? { name: previewName } : user
  const authenticated = signedIn || isAuthenticated
  const compact = useCompactNav()
  const [menuOpen, setMenuOpen] = useState(false)
  const [toolsOpen, setToolsOpen] = useState(false)
  const menuId = useId()
  const toolsId = useId()
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)
  const toolsRef = useRef<HTMLDivElement>(null)
  const toolsButtonRef = useRef<HTMLButtonElement>(null)
  const tailorLabel = COPY[lang].nav.tailor
  const homeHref = previewHomePath(location.search)
  const resumesActive = active === 'resumes' || location.pathname.startsWith('/resumes')

  const resumeItems = [
    { to: '/resumes', key: 'nav.myResumes' },
    { to: '/resumes/builder', key: 'nav.resumeBuilder' },
    { to: '/resumes', key: 'nav.qualityScore' },
  ]

  function featureHref(path: string): string {
    return authenticated ? path : loginPathForAnonymousFeature(path)
  }

  useEffect(() => {
    setMenuOpen(false)
    setToolsOpen(false)
  }, [location.pathname, location.search, compact, i18n.language])

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

  function setLanguage(next: PreviewLang) {
    void i18n.changeLanguage(next === 'zh' ? 'zh-CN' : 'en')
    onLangChange(next)
  }

  const languageSwitch = (
    <span className="ds-lang" role="group" aria-label={t('nav.languageZh') + ' / EN'}>
      <button type="button" aria-pressed={lang === 'zh'} onClick={() => setLanguage('zh')}>
        {t('nav.languageZh')}
      </button>
      <button type="button" aria-pressed={lang === 'en'} onClick={() => setLanguage('en')}>
        {t('nav.languageEn')}
      </button>
    </span>
  )

  function navClass(isActive: boolean) {
    return `ds-nav-link${isActive ? ' is-active' : ''}`
  }

  const primaryLinks = (
    <>
      {authenticated ? (
        <Link to="/dashboard" className={navClass(active === 'console')} aria-current={active === 'console' ? 'page' : undefined}>
          {t('nav.dashboard')}
        </Link>
      ) : (
        <Link to={homeHref} className={navClass(active === 'home')} aria-current={active === 'home' ? 'page' : undefined}>
          {t('nav.home')}
        </Link>
      )}
      <Link to="/jobs" className={navClass(active === 'jobs')} aria-current={active === 'jobs' ? 'page' : undefined}>
        {t('nav.jobs')}
      </Link>
    </>
  )

  const tailorLink = (
    <Link to={featureHref('/tailor')} className={navClass(active === 'tailor')} aria-current={active === 'tailor' ? 'page' : undefined}>
      {tailorLabel}
    </Link>
  )

  function isResumeItemActive(to: string, key: string) {
    if (key === 'nav.resumeBuilder') {
      return location.pathname.startsWith('/resumes/builder')
    }
    if (key === 'nav.qualityScore') {
      return location.pathname.includes('quality-score')
    }
    return location.pathname === to || (resumesActive && location.pathname.startsWith('/resumes') && !location.pathname.startsWith('/resumes/builder'))
  }

  function resumeMenuItems(stacked: boolean) {
    return resumeItems.map(({ to, key }) => {
      const itemActive = isResumeItemActive(to, key)
      return (
        <Link
          key={key}
          role={stacked ? undefined : 'menuitem'}
          to={featureHref(to)}
          className={`${stacked ? 'ds-nav-link ds-nav-link-stack' : 'ds-menu-item'}${itemActive ? ' is-active' : ''}`}
          aria-current={itemActive ? 'page' : undefined}
          onClick={() => {
            setToolsOpen(false)
            setMenuOpen(false)
          }}
        >
          {t(key)}
        </Link>
      )
    })
  }

  const toolsControl = compact ? null : (
    <div className="ds-nav-tools" ref={toolsRef}>
      <button
        ref={toolsButtonRef}
        type="button"
        className={navClass(resumesActive)}
        aria-current={resumesActive ? 'page' : undefined}
        aria-expanded={toolsOpen}
        aria-haspopup="menu"
        aria-controls={toolsId}
        onClick={() => setToolsOpen((open) => !open)}
      >
        {t('nav.resumeTools')}
        <ChevronIcon />
      </button>
      {toolsOpen ? (
        <div id={toolsId} role="menu" aria-label={t('nav.resumeTools')} className="ds-menu">
          {resumeMenuItems(false)}
        </div>
      ) : null}
    </div>
  )

  const authControls = authenticated ? (
    <>
      {sessionUser ? (
        <Link
          to="/profile"
          className={navClass(location.pathname === '/profile')}
          aria-label={`${sessionUser.name}, ${t('nav.profile')}`}
        >
          <span className="ds-user-name">{sessionUser.name}</span>
          <span className="ds-nav-meta">{t('nav.profile')}</span>
        </Link>
      ) : null}
      <button type="button" className="ds-nav-text" onClick={handleLogout}>
        {t('nav.logout')}
      </button>
    </>
  ) : (
    <>
      <Link to="/login" className="ds-nav-text">
        {t('nav.login')}
      </Link>
      <Link to="/register" className="ds-nav-register">
        {t('nav.register')}
      </Link>
    </>
  )

  return (
    <header className="ds-header">
      <div className="ds-header-inner">
        <div className="ds-header-start">
          <Link to={authenticated ? '/dashboard' : homeHref} className="ds-brand">
            <span className="ds-brand-mark" aria-hidden="true"><BrandIcon /></span>
            <span className="ds-brand-name">{t('app.name')}</span>
          </Link>
          {!compact ? (
            <nav className="ds-header-nav" aria-label={t('nav.menu')}>
              {primaryLinks}
              {toolsControl}
              {tailorLink}
            </nav>
          ) : null}
        </div>
        <div className="ds-header-actions">
          {languageSwitch}
          {!compact ? authControls : null}
          {compact ? (
            <button
              ref={menuButtonRef}
              type="button"
              className="ds-menu-toggle"
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
          className="ds-compact-menu"
        >
          <div className="ds-header-inner ds-compact-menu-inner">
            <nav className="ds-compact-nav" aria-label={t('nav.menu')}>
              {primaryLinks}
              <p className="ds-compact-label">{t('nav.resumeTools')}</p>
              {resumeMenuItems(true)}
              {tailorLink}
            </nav>
            <div className="ds-compact-auth">{authControls}</div>
          </div>
        </div>
      ) : null}
    </header>
  )
}
