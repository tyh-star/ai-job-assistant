/**
 * The signed-in console's own top navigation, used ONLY on /dashboard.
 *
 * Ported from the approved workbench design: text-only nav with a hairline
 * under the current section (never a filled pill), a 中文/EN toggle, and an
 * avatar + name dropdown that holds 个人中心 and 退出登录 instead of spelling
 * both out in the bar. Every other route keeps AppLayout's shared header, which
 * this component does not touch.
 *
 * Routes and copy come from the real app: nav.* i18n keys, the same paths the
 * shared header links to, and AuthContext.logout().
 */

import { useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import './dashboardHeader.css'

/* ------------------------------------------------------------------ icons */

function IconBrand() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.55" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M7.7 7.2V5.9c0-1 .8-1.9 1.9-1.9h4.8c1 0 1.9.8 1.9 1.9v1.3" />
      <path d="M5.1 8.1h13.8c.9 0 1.6.7 1.6 1.6v8.2c0 1-.8 1.8-1.8 1.8H5.3c-1 0-1.8-.8-1.8-1.8V9.7c0-.9.7-1.6 1.6-1.6Z" />
      <path d="M3.7 12.3c2.7 1.2 5.5 1.8 8.3 1.8s5.6-.6 8.3-1.8M10.4 12.8h3.2v2.3h-3.2z" />
    </svg>
  )
}

function IconChevron() {
  return (
    <svg width="14" height="14" viewBox="0 0 20 20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6.5 8 10 11.5 13.5 8" />
    </svg>
  )
}

function IconUser() {
  return (
    <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="10" cy="6.75" r="3" />
      <path d="M3.75 16.75c0-3.45 2.8-5.75 6.25-5.75s6.25 2.3 6.25 5.75" />
    </svg>
  )
}

function IconLogout() {
  return (
    <svg width="15" height="15" viewBox="0 0 20 20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 6.25V4.5a1.25 1.25 0 0 0-1.25-1.25h-6A1.25 1.25 0 0 0 3.5 4.5v11a1.25 1.25 0 0 0 1.25 1.25h6A1.25 1.25 0 0 0 12 15.5v-1.75" />
      <path d="M8 10h8.5M14 7.5 16.5 10 14 12.5" />
    </svg>
  )
}

function IconMenu() {
  return (
    <svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
      <path d="M3.25 5.5h13.5M3.25 10h13.5M3.25 14.5h13.5" />
    </svg>
  )
}

function IconClose() {
  return (
    <svg width="19" height="19" viewBox="0 0 20 20" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" aria-hidden="true">
      <path d="M5 5l10 10M15 5 5 15" />
    </svg>
  )
}

/* ------------------------------------------------------------------ parts */

/** Closes a popup on Escape (restoring focus) and on an outside click. */
function useDismiss(
  open: boolean,
  close: () => void,
  wrapRef: React.RefObject<HTMLElement | null>,
  triggerRef: React.RefObject<HTMLButtonElement | null>,
) {
  useEffect(() => {
    if (!open) {
      return
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
        triggerRef.current?.focus()
      }
    }
    function onPointer(event: MouseEvent) {
      if (!wrapRef.current?.contains(event.target as Node)) {
        close()
      }
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointer)
    }
  }, [open, close, wrapRef, triggerRef])
}

/** The resume-tools dropdown: the same three destinations the shared nav uses. */
function ResumeToolsMenu() {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  useDismiss(open, () => setOpen(false), wrapRef, triggerRef)

  const items = [
    { label: t('nav.myResumes'), to: '/resumes' },
    { label: t('nav.resumeBuilder'), to: '/resumes/builder' },
    { label: t('nav.qualityScore'), to: '/resumes' },
  ]

  return (
    <div className="wbNav__group" ref={wrapRef}>
      <button
        ref={triggerRef}
        type="button"
        className="wbNav__trigger"
        aria-expanded={open}
        aria-haspopup="menu"
        aria-controls={menuId}
        onClick={() => setOpen((v) => !v)}
      >
        {t('nav.resumeTools')}
        <span className="wbNav__chev"><IconChevron /></span>
      </button>
      {open ? (
        <div id={menuId} role="menu" aria-label={t('nav.resumeTools')} className="wbNav__menu wbNav__menu--under">
          {items.map((item) => (
            <Link key={item.label} role="menuitem" to={item.to} onClick={() => setOpen(false)}>
              {item.label}
            </Link>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function AccountMenu({ name, onLogout }: { name: string; onLogout: () => void }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  useDismiss(open, () => setOpen(false), wrapRef, triggerRef)

  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0] ?? '')
    .join('')
    .toUpperCase()

  return (
    <div className="wbNav__acct" ref={wrapRef}>
      <button
        ref={triggerRef}
        type="button"
        className="wbNav__acctTrigger"
        aria-expanded={open}
        aria-haspopup="menu"
        aria-controls={menuId}
        aria-label={`${name} — ${t('nav.profile')}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="wbNav__avatar" aria-hidden="true">{initials}</span>
        <span className="wbNav__acctName">{name}</span>
        <IconChevron />
      </button>
      {open ? (
        <div id={menuId} role="menu" aria-label={t('nav.profile')} className="wbNav__menu wbNav__menu--right">
          <Link role="menuitem" to="/profile" onClick={() => setOpen(false)}>
            <IconUser />
            {t('nav.profile')}
          </Link>
          <span className="wbNav__sep" />
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              onLogout()
            }}
          >
            <IconLogout />
            {t('nav.logout')}
          </button>
        </div>
      ) : null}
    </div>
  )
}

/* ----------------------------------------------------------------- header */

export function DashboardHeader({ onLogout }: { onLogout: () => void }) {
  const { t, i18n } = useTranslation()
  const { user } = useAuth()
  const [stuck, setStuck] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const burgerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  const current = i18n.resolvedLanguage ?? i18n.language
  const name = user?.name ?? ''

  useEffect(() => {
    function onScroll() {
      setStuck(window.scrollY > 8)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (!menuOpen) {
      return
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMenuOpen(false)
        burgerRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [menuOpen])

  const drawerLinks = [
    { label: t('nav.dashboard'), to: '/dashboard' },
    { label: t('nav.jobs'), to: '/jobs' },
    { label: t('nav.myResumes'), to: '/resumes' },
    { label: t('nav.resumeBuilder'), to: '/resumes/builder' },
    { label: t('nav.tailor'), to: '/tailor' },
  ]

  return (
    <header className="wbNav" data-stuck={stuck ? 'true' : 'false'}>
      <div className="wbNav__inner">
        <Link to="/dashboard" className="wbNav__brand">
          <span className="wbNav__mark" aria-hidden="true"><IconBrand /></span>
          {t('app.name')}
        </Link>

        <nav className="wbNav__links" aria-label={t('nav.menu')}>
          <NavLink to="/dashboard" className={({ isActive }) => (isActive ? 'is-current' : undefined)}>
            {t('nav.dashboard')}
          </NavLink>
          <NavLink to="/jobs" className={({ isActive }) => (isActive ? 'is-current' : undefined)}>
            {t('nav.jobs')}
          </NavLink>
          <ResumeToolsMenu />
          <NavLink to="/tailor" className={({ isActive }) => (isActive ? 'is-current' : undefined)}>
            {t('nav.tailor')}
          </NavLink>
        </nav>

        <div className="wbNav__actions">
          <span className="wbNav__lang" role="group" aria-label={t('nav.menu')}>
            <button
              type="button"
              aria-pressed={current.startsWith('zh')}
              onClick={() => void i18n.changeLanguage('zh-CN')}
            >
              {t('nav.languageZh')}
            </button>
            <button
              type="button"
              aria-pressed={current.startsWith('en')}
              onClick={() => void i18n.changeLanguage('en')}
            >
              {t('nav.languageEn')}
            </button>
          </span>

          <AccountMenu name={name} onLogout={onLogout} />

          <button
            ref={burgerRef}
            type="button"
            className="wbNav__burger"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            aria-label={menuOpen ? t('nav.closeMenu') : t('nav.openMenu')}
            onClick={() => setMenuOpen((v) => !v)}
          >
            {menuOpen ? <IconClose /> : <IconMenu />}
          </button>
        </div>
      </div>

      {menuOpen ? (
        <div id={menuId} className="wbNav__drawer">
          <nav aria-label={t('nav.menu')}>
            {drawerLinks.map((link) => (
              <Link key={link.to + link.label} to={link.to} onClick={() => setMenuOpen(false)}>
                {link.label}
              </Link>
            ))}
          </nav>
          <div className="wbNav__drawerFoot">
            <p className="wbNav__drawerName">{name}</p>
            <Link to="/profile" onClick={() => setMenuOpen(false)}>{t('nav.profile')}</Link>
            <button
              type="button"
              onClick={() => {
                setMenuOpen(false)
                onLogout()
              }}
            >
              {t('nav.logout')}
            </button>
          </div>
        </div>
      ) : null}
    </header>
  )
}
