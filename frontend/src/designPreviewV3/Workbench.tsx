/**
 * V3 signed-in surface: the workbench.
 *
 * Desktop is a 7/12 + 5/12 split. The resume center on the left is the focal
 * module; on the right, 职位 (find jobs + the saved job to continue with) sits
 * above 最近投递 (a quiet three-item timeline). No statistics grid, no activity
 * feed, no filters, no charts, no decorative filler. The first-run state keeps
 * its own single centred panel and is not affected by any of this.
 *
 * Chrome is unchanged: the same 64px white top navigation the marketing site
 * uses, collapsing to a hamburger below 900px.
 *
 * Content contract -- every string maps to something the repo actually has:
 *   primary resume    GET /resume-builder/drafts -> title, templateType, updatedAt
 *   内容完整            computeDraftReadiness(...).readyToGenerate
 *   secondary resume  GET /resumes -> fileName, fileType, updatedAt
 *   saved / applied   GET /jobs -> JobResponse.applied (false / true)
 *   job rows          GET /jobs -> title, company, appliedAt
 *   管理全部            /resumes
 *   简历改写            /tailor
 *   编辑简历            /resumes/builder/:id
 *   查看简历质量评分    /resumes/:id/quality-score
 *   查找职位 / 查看职位 / 查看全部   /jobs
 *   个人中心 / 退出登录 /profile (GET /users/me), AuthContext.logout()
 * Nothing implies a capability that does not exist: no interview or offer
 * stage, no deadline, no match score, no trend, no completion rate, no goals,
 * no tasks, no notifications.
 */

import { useEffect, useId, useRef, useState } from 'react'
import {
  appliedJobs,
  COPY,
  latestUpload,
  primaryDraft,
  ROUTES,
  savedJobs,
  WORKBENCH_FIXTURE,
  type FixtureDraft,
  type FixtureJob,
  type FixtureUpload,
  type Lang,
} from './content'
import {
  IconArrow,
  IconBrand,
  IconCheck,
  IconChevron,
  IconClose,
  IconDoc,
  IconLogout,
  IconMenu,
  IconSearch,
  IconUser,
} from './icons'

export type WorkbenchState = 'new' | 'data' | 'loading' | 'error' | 'partial'

type App = (typeof COPY)['zh']['app']

type Props = {
  state: WorkbenchState
  lang: Lang
  onLang: (next: Lang) => void
  onExit: () => void
}

/** Closes a popup on Escape (restoring focus) and on an outside click. */
function useDismiss(
  open: boolean,
  close: () => void,
  wrapRef: React.RefObject<HTMLElement | null>,
  triggerRef?: React.RefObject<HTMLButtonElement | null>,
) {
  useEffect(() => {
    if (!open) {
      return
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
        triggerRef?.current?.focus()
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

/* ------------------------------------------------------------------ header */

function ResumeToolsMenu({ t }: { t: App }) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  useDismiss(open, () => setOpen(false), wrapRef, triggerRef)

  const items = [
    { label: t.myResumes, href: ROUTES.resumes },
    { label: t.buildResume, href: ROUTES.resumeBuilder },
    { label: t.qualityScore, href: ROUTES.resumes },
  ]

  return (
    <div ref={wrapRef} style={{ position: 'relative' }}>
      <button
        ref={triggerRef}
        type="button"
        className="v3-appNav__trigger"
        aria-expanded={open}
        aria-haspopup="menu"
        aria-controls={menuId}
        onClick={() => setOpen((v) => !v)}
      >
        {t.resumeTools}
        <span className="v3-appNav__chev"><IconChevron size={14} /></span>
      </button>
      {open ? (
        <div id={menuId} role="menu" aria-label={t.resumeTools} className="v3-menu v3-menu--under">
          {items.map((item) => (
            <a key={item.label} role="menuitem" href="#top" onClick={() => setOpen(false)}>
              {item.label}
            </a>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function AccountMenu({ t, onExit }: { t: App; onExit: () => void }) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  useDismiss(open, () => setOpen(false), wrapRef, triggerRef)

  return (
    <div className="v3-acct" ref={wrapRef}>
      <button
        ref={triggerRef}
        type="button"
        className="v3-acct__trigger"
        aria-expanded={open}
        aria-haspopup="menu"
        aria-controls={menuId}
        aria-label={`${WORKBENCH_FIXTURE.userName} — ${t.account}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="v3-acct__avatar" aria-hidden="true">{WORKBENCH_FIXTURE.userInitials}</span>
        <span className="v3-acct__name">{WORKBENCH_FIXTURE.userName}</span>
        <IconChevron size={14} />
      </button>
      {open ? (
        <div id={menuId} role="menu" aria-label={t.account} className="v3-menu v3-menu--right">
          <a role="menuitem" href="#top" onClick={() => setOpen(false)}>
            <IconUser size={15} />
            {t.profile}
          </a>
          <span className="v3-menu__sep" />
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              onExit()
            }}
          >
            <IconLogout size={15} />
            {t.logout}
          </button>
        </div>
      ) : null}
    </div>
  )
}

function AppHeader({
  t,
  brand,
  lang,
  onLang,
  onExit,
}: {
  t: App
  brand: string
  lang: Lang
  onLang: (n: Lang) => void
  onExit: () => void
}) {
  const [stuck, setStuck] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const burgerRef = useRef<HTMLButtonElement>(null)
  const menuId = useId()

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

  const mobileLinks = [
    { label: t.dashboard, current: true },
    { label: t.jobs, current: false },
    { label: t.myResumes, current: false },
    { label: t.buildResume, current: false },
    { label: t.qualityScore, current: false },
    { label: t.tailor, current: false },
  ]

  return (
    <header
      className="v3-siteHeader v3-appHeader"
      data-stuck={stuck ? 'true' : 'false'}
    >
      <div className="v3-siteHeader__inner">
        <span className="v3-brand">
          <span className="v3-brand__mark"><IconBrand size={16} /></span>
          {brand}
        </span>

        <nav className="v3-siteNav v3-appNav" aria-label={t.sidebarLabel}>
          <a href="#top" aria-current="page">{t.dashboard}</a>
          <a href="#top">{t.jobs}</a>
          <ResumeToolsMenu t={t} />
          <a href="#top">{t.tailor}</a>
        </nav>

        <div className="v3-siteHeader__actions">
          <span className="v3-lang" role="group" aria-label={t.language}>
            <button type="button" aria-pressed={lang === 'zh'} onClick={() => onLang('zh')}>中文</button>
            <button type="button" aria-pressed={lang === 'en'} onClick={() => onLang('en')}>EN</button>
          </span>
          <AccountMenu t={t} onExit={onExit} />
          <button
            ref={burgerRef}
            type="button"
            className="v3-iconBtn v3-appHeader__burger"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            aria-label={menuOpen ? t.closeMenu : t.openMenu}
            onClick={() => setMenuOpen((v) => !v)}
          >
            {menuOpen ? <IconClose size={18} /> : <IconMenu size={18} />}
          </button>
        </div>
      </div>

      {menuOpen ? (
        <div id={menuId} className="v3-appDrawer">
          <nav aria-label={t.sidebarLabel}>
            {mobileLinks.map((link) => (
              <a
                key={link.label}
                href="#top"
                aria-current={link.current ? 'page' : undefined}
                onClick={() => setMenuOpen(false)}
              >
                {link.label}
              </a>
            ))}
          </nav>
          <div className="v3-appDrawer__group">
            <p className="v3-appDrawer__label">{t.account}</p>
            <div className="v3-appDrawer__user">
              <span className="v3-acct__avatar" aria-hidden="true">{WORKBENCH_FIXTURE.userInitials}</span>
              <b>{WORKBENCH_FIXTURE.userName}</b>
            </div>
            <nav aria-label={t.account}>
              <a href="#top" onClick={() => setMenuOpen(false)}>{t.profile}</a>
              <a
                href="#top"
                onClick={() => {
                  setMenuOpen(false)
                  onExit()
                }}
              >
                {t.logout}
              </a>
            </nav>
          </div>
        </div>
      ) : null}
    </header>
  )
}

/* ------------------------------------------------------------- page pieces */

/** Greeting, one line of context, one primary action. */
function WbHead({
  title,
  subtitle,
  action,
}: {
  title: string
  subtitle: string
  action?: React.ReactNode
}) {
  return (
    <header className="v3-wbHead">
      <div className="v3-wbHead__text">
        <h1>{title}</h1>
        <p>{subtitle}</p>
      </div>
      {action ? <div className="v3-wbHead__action">{action}</div> : null}
    </header>
  )
}


/*
 * Workbench-only duotone glyphs for the three data modules: the same 20px grid
 * and brand blue as ./icons, with a light tint of the stroke colour filling the
 * body and one small solid accent, so they carry weight without turning into
 * illustrations. The shared icon set, the top navigation, the first-run panel,
 * Loading and the error states keep their line icons.
 */
function DuoSvg({ size, children }: { size: number; children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

function DuoBriefcase({ size = 19 }: { size?: number }) {
  return (
    <DuoSvg size={size}>
      <rect x="2.75" y="6" width="14.5" height="10.75" rx="2" fill="currentColor" fillOpacity="0.28" />
      <path d="M7 6V4.6A1.35 1.35 0 0 1 8.35 3.25h3.3A1.35 1.35 0 0 1 13 4.6V6" />
      <path d="M2.75 10.25h14.5" />
      <rect x="8.5" y="9.1" width="3" height="2.3" rx="0.7" fill="currentColor" stroke="none" />
    </DuoSvg>
  )
}

function DuoClock({ size = 19 }: { size?: number }) {
  return (
    <DuoSvg size={size}>
      <circle cx="10" cy="10" r="7.25" fill="currentColor" fillOpacity="0.28" />
      <path d="M10 6.25V10l2.6 1.6" />
      <circle cx="10" cy="10" r="1.15" fill="currentColor" stroke="none" />
    </DuoSvg>
  )
}

function DuoFile({ size = 20 }: { size?: number }) {
  return (
    <DuoSvg size={size}>
      <path
        d="M5.5 2.75h6l3.25 3.25v11a1 1 0 0 1-1 1h-8.25a1 1 0 0 1-1-1v-13.25a1 1 0 0 1 1-1Z"
        fill="currentColor"
        fillOpacity="0.28"
      />
      <path d="M11.5 2.75v2.5a1 1 0 0 0 1 1h2.25" fill="currentColor" fillOpacity="0.55" />
      <path d="M7.5 10.5h5M7.5 13.25h3.25" />
    </DuoSvg>
  )
}

/** Every text action on the page: same size and weight. Only the two "all"
 *  links (管理全部, 查看全部) carry an arrow, as in the reference. */
function WbAct({ label, href = '#top', arrow = true }: { label: string; href?: string; arrow?: boolean }) {
  return (
    <a className="v3-wbAct" href={href}>
      {label}
      {arrow ? <IconArrow size={14} /> : null}
    </a>
  )
}

/** Compact horizontal page head: greeting | short rule | one line of context. */
function WbIntro({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <header className="v3-wbIntro">
      <h1>{title}</h1>
      <span className="v3-wbIntro__rule" aria-hidden="true" />
      <p>{subtitle}</p>
    </header>
  )
}

/** A module whose request failed: short message + reload, nothing else. */
function WbFail({ message, reload, onRetry }: { message: string; reload: string; onRetry: () => void }) {
  return (
    <div className="v3-wbFail" role="status">
      <p>{message}</p>
      <button type="button" className="v3-btn v3-btn--ghost v3-btn--sm" onClick={onRetry}>
        {reload}
      </button>
    </div>
  )
}

/** Miniature of the builder draft's own preview: its name plus text shape. */
function WbDocThumb({ name }: { name: string }) {
  return (
    <div className="v3-wbDoc" aria-hidden="true">
      <p className="v3-wbDoc__name">{name}</p>
      <span className="v3-wbDoc__line" style={{ width: '46%' }} />
      <span className="v3-wbDoc__pair"><i /><i /></span>
      <span className="v3-wbDoc__pair"><i /><i /></span>
      <span className="v3-wbDoc__line" style={{ width: '92%', marginTop: 8 }} />
      <span className="v3-wbDoc__line" style={{ width: '84%' }} />
      <span className="v3-wbDoc__line v3-wbDoc__line--strong" style={{ width: '58%' }} />
      <span className="v3-wbDoc__line" style={{ width: '90%', marginTop: 8 }} />
      <span className="v3-wbDoc__line" style={{ width: '76%' }} />
      <span className="v3-wbDoc__line" style={{ width: '40%' }} />
    </div>
  )
}

/* ---------------------------------------------------------- resume center */

function ResumeCenter({
  t,
  draft,
  upload,
  userName,
  failed,
  onRetry,
}: {
  t: App
  draft: FixtureDraft | undefined
  upload: FixtureUpload | undefined
  userName: string
  failed: boolean
  onRetry: () => void
}) {
  return (
    <section className="v3-wbMod v3-wbMod--resume" aria-labelledby="v3-wb-resumes">
      <div className="v3-wbMod__head">
        <h2 id="v3-wb-resumes" className="v3-wbMod__title">{t.resumeCenter}</h2>
        {failed ? null : <span className="v3-wbMod__end"><WbAct label={t.manageAll} /></span>}
      </div>

      {failed ? (
        /* The module keeps its full height (no layout jump), so the failure is
           one compact group centred in that space rather than pinned to the
           top above an empty panel. No placeholder data, no skeleton. */
        <div className="v3-wbFailCenter" role="status">
          <span className="v3-wbFailCenter__icon" aria-hidden="true"><IconDoc size={20} /></span>
          <p>{t.resumeFailed}</p>
          <button type="button" className="v3-btn v3-btn--ghost v3-btn--sm" onClick={onRetry}>
            {t.reload}
          </button>
        </div>
      ) : draft ? (
        <>
          <div className="v3-wbResume">
            <WbDocThumb name={userName} />
            <div className="v3-wbResume__info">
              <p className="v3-wbResume__title">{draft.title}</p>
              <p className="v3-wbResume__meta">
                {/* the value is the real templateType; only the label is localized */}
                <span className="v3-wbResume__type">{t.templateLabel(draft.templateType)}</span>
                <span aria-hidden="true"> · </span>
                {t.updatedOn(draft.updatedAt)}
              </p>
              {draft.readyToGenerate ? (
                <p className="v3-wbReady">
                  <span className="v3-wbReady__icon" aria-hidden="true"><IconCheck size={13} /></span>
                  {t.readyStatus}
                </p>
              ) : null}
            </div>
            <div className="v3-wbResume__actions">
              <div className="v3-wbResume__buttons">
                {/* the page's only solid blue button */}
                <a className="v3-btn v3-btn--primary" href="#top">{t.tailorByJob}</a>
                <a className="v3-btn v3-btn--ghost v3-btn--brand" href="#top">{t.editResume}</a>
              </div>
              <WbAct label={t.qualityScoreLink} arrow={false} />
            </div>
          </div>

          {upload ? (
            <a className="v3-wbFile" href="#top">
              <span className="v3-wbFile__icon" aria-hidden="true"><DuoFile size={20} /></span>
              <span className="v3-wbFile__text">
                <span className="v3-wbFile__name">{upload.fileName}</span>
                <span className="v3-wbFile__meta">
                  {upload.fileType}
                  <span aria-hidden="true"> · </span>
                  {t.updatedOn(upload.updatedAt)}
                </span>
              </span>
            </a>
          ) : null}
        </>
      ) : (
        <p className="v3-wbEmpty">{t.noResumes}</p>
      )}
    </section>
  )
}

/* -------------------------------------------------------------------- jobs */

/**
 * Find jobs + the one saved job worth continuing with. JobResponse has no
 * saved-at timestamp, so this is the first unapplied row in API order -- never
 * a fabricated recency sort, score or deadline.
 */
function JobsModule({
  t,
  saved,
  failed,
  onRetry,
}: {
  t: App
  saved: FixtureJob[]
  failed: boolean
  onRetry: () => void
}) {
  const job = saved[0]
  return (
    <section className="v3-wbMod v3-wbMod--quiet v3-wbMod--jobs" aria-labelledby="v3-wb-jobs">
      <div className="v3-wbMod__head">
        <span className="v3-wbMod__icon" aria-hidden="true"><DuoBriefcase size={19} /></span>
        <h2 id="v3-wb-jobs" className="v3-wbMod__title">{t.jobsTitle}</h2>
        {!failed && saved.length > 0 ? (
          <span className="v3-wbMod__badge">{t.savedBadge(saved.length)}</span>
        ) : null}
        <span className="v3-wbMod__end v3-wbMod__end--button">
          <a className="v3-btn v3-btn--ghost v3-btn--brand" href="#top">
            <IconSearch size={15} />
            {t.findJobs}
          </a>
        </span>
      </div>

      {failed ? (
        <WbFail message={t.jobsFailed} reload={t.reload} onRetry={onRetry} />
      ) : job ? (
        <>
          <p className="v3-wbLabel">{t.continueSaved}</p>
          <div className="v3-wbSaved">
            <span className="v3-wbSaved__bar" aria-hidden="true" />
            <div className="v3-wbSaved__text">
              <p className="v3-wbSaved__title">{job.title}</p>
              <p className="v3-wbSaved__org">{job.company}</p>
              <p className="v3-wbSaved__status">{t.savedStatus}</p>
            </div>
            <span className="v3-wbSaved__act"><WbAct label={t.viewJob} arrow={false} /></span>
          </div>
        </>
      ) : (
        <p className="v3-wbEmpty">{t.noSaved}</p>
      )}
    </section>
  )
}

/* --------------------------------------------------------- recent history */

/** MM/DD from the real appliedAt ISO date. */
function shortDate(iso: string | null): string {
  return iso ? `${iso.slice(5, 7)}/${iso.slice(8, 10)}` : ''
}

/** Three applied jobs, newest first by appliedAt, as a quiet timeline. */
function HistoryModule({
  t,
  applied,
  failed,
  onRetry,
}: {
  t: App
  applied: FixtureJob[]
  failed: boolean
  onRetry: () => void
}) {
  return (
    <section className="v3-wbMod v3-wbMod--quiet v3-wbMod--history" aria-labelledby="v3-wb-history">
      <div className="v3-wbMod__head">
        <span className="v3-wbMod__icon" aria-hidden="true"><DuoClock size={19} /></span>
        <h2 id="v3-wb-history" className="v3-wbMod__title">{t.recentApplied}</h2>
        {!failed && applied.length > 0 ? (
          <span className="v3-wbMod__end"><WbAct label={t.viewAll} /></span>
        ) : null}
      </div>

      {failed ? (
        <WbFail message={t.jobsFailed} reload={t.reload} onRetry={onRetry} />
      ) : applied.length > 0 ? (
        <ol className="v3-wbTl">
          {applied.slice(0, 3).map((job) => (
            <li key={job.id}>
              <a className="v3-wbTl__row" href="#top">
                <time className="v3-wbTl__date" dateTime={job.appliedAt ?? undefined}>
                  {shortDate(job.appliedAt)}
                </time>
                <span className="v3-wbTl__text">
                  <span className="v3-wbTl__title">{job.title}</span>
                  <span className="v3-wbTl__org">{job.company}</span>
                </span>
              </a>
            </li>
          ))}
        </ol>
      ) : (
        <p className="v3-wbEmpty">{t.noApplied}</p>
      )}
    </section>
  )
}

/** First run: one icon, one sentence, one button, one quiet link. */
function WbStart({ t, onExit }: { t: App; onExit: () => void }) {
  return (
    <div className="v3-wbPanel v3-wbPanel--start">
      <span className="v3-wbStart__icon" aria-hidden="true"><IconDoc size={22} /></span>
      <h2>{t.startTitle}</h2>
      <p>{t.startBody}</p>
      <button type="button" className="v3-btn v3-btn--primary" onClick={onExit}>
        {t.createResume}
      </button>
      <a className="v3-wbStart__alt" href="#top">
        {t.altFindJobs}
        <IconArrow size={14} />
      </a>
    </div>
  )
}

/**
 * Loading keeps the final three-module geometry (same titles, same icon
 * tiles, bodies of the loaded height) so nothing jumps when data lands, and it
 * never shows a count or a zero.
 */
function WbLoading({ t }: { t: App }) {
  return (
    <div className="v3-wbGrid" role="status" aria-label={t.loading}>
      <section className="v3-wbMod v3-wbMod--resume">
        <div className="v3-wbMod__head"><h2 className="v3-wbMod__title">{t.resumeCenter}</h2></div>
        <div className="v3-wbResume">
          <div className="v3-wbDoc v3-wbDoc--skeleton" />
          <div className="v3-wbResume__info">
            <span className="v3-wbSkel" style={{ width: '78%', height: 20 }} />
            <span className="v3-wbSkel" style={{ width: '42%', height: 13, marginTop: 12 }} />
            <span className="v3-wbSkel" style={{ width: '62%', height: 14, marginTop: 18 }} />
          </div>
          <div className="v3-wbResume__actions">
            <div className="v3-wbResume__buttons">
              <span className="v3-wbSkel v3-wbSkel--btn" />
              <span className="v3-wbSkel v3-wbSkel--btn" />
            </div>
          </div>
        </div>
        <div className="v3-wbFile v3-wbFile--skeleton">
          <span className="v3-wbFile__icon" />
          <span className="v3-wbFile__text">
            <span className="v3-wbSkel" style={{ width: 160, height: 14 }} />
            <span className="v3-wbSkel" style={{ width: 110, height: 12, marginTop: 8 }} />
          </span>
        </div>
      </section>

      <div className="v3-wbCol">
        <section className="v3-wbMod v3-wbMod--quiet v3-wbMod--jobs">
          <div className="v3-wbMod__head">
            <span className="v3-wbMod__icon" aria-hidden="true"><DuoBriefcase size={19} /></span>
            <h2 className="v3-wbMod__title">{t.jobsTitle}</h2>
          </div>
          {/* label slot and job-text height are set in CSS per breakpoint so each
              matches the loaded label line and saved-job block it stands in for */}
          <span className="v3-wbSkel v3-wbSkel--label" style={{ width: '32%' }} />
          <div className="v3-wbSaved">
            <span className="v3-wbSaved__bar v3-wbSaved__bar--skeleton" />
            <div className="v3-wbSaved__text v3-wbSaved__text--skeleton">
              <span className="v3-wbSkel" style={{ width: '64%', height: 17, marginTop: 4 }} />
              <span className="v3-wbSkel" style={{ width: '46%', height: 13, marginTop: 11 }} />
              <span className="v3-wbSkel" style={{ width: '34%', height: 12, marginTop: 10 }} />
            </div>
          </div>
        </section>

        <section className="v3-wbMod v3-wbMod--quiet v3-wbMod--history">
          <div className="v3-wbMod__head">
            <span className="v3-wbMod__icon" aria-hidden="true"><DuoClock size={19} /></span>
            <h2 className="v3-wbMod__title">{t.recentApplied}</h2>
          </div>
          <ol className="v3-wbTl">
            {[0, 1, 2].map((index) => (
              <li key={index}>
                <div className="v3-wbTl__row">
                  <span className="v3-wbTl__date v3-wbTl__date--skeleton" />
                  <span className="v3-wbTl__text">
                    <span className="v3-wbSkel" style={{ width: `${54 + index * 8}%`, height: 14 }} />
                    <span className="v3-wbSkel" style={{ width: '40%', height: 12, marginTop: 7 }} />
                  </span>
                </div>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  )
}

/* -------------------------------------------------------------------- page */

export function Workbench({ state, lang, onLang, onExit }: Props) {
  const t = COPY[lang].app
  const brand = COPY[lang].brand
  const f = WORKBENCH_FIXTURE

  const draft = primaryDraft()
  const upload = latestUpload()
  const saved = savedJobs()
  const applied = appliedJobs()

  /* Which request failed is a property of the partial state itself; reading it
     from the URL keeps the router free of a second state name. The route still
     switches on ?partial=1. By default GET /jobs fails -- which takes 职位 and
     最近投递 down TOGETHER, because both are derived from that one call.
     ?block=resume fails the resume calls instead. */
  const partialBlock =
    state === 'partial'
      ? new URLSearchParams(window.location.search).get('block') === 'resume'
        ? 'resume'
        : 'jobs'
      : null

  const isNew = state === 'new'

  return (
    <div className="v3-app" id="top">
      <AppHeader t={t} brand={brand} lang={lang} onLang={onLang} onExit={onExit} />

      <div className="v3-appMain">
        {isNew ? (
          /* First run: unchanged -- its own head and single centred panel. */
          <main className="v3-wb" data-state={state}>
            <WbHead title={t.greeting(f.userFirstName)} subtitle={t.newSubtitle} />
            <WbStart t={t} onExit={onExit} />
          </main>
        ) : (
          <main className="v3-wb v3-wb--desk" data-state={state}>
            <WbIntro title={t.greeting(f.userFirstName)} subtitle={t.dataSubtitle} />

            {state === 'loading' ? <WbLoading t={t} /> : null}

            {/* Every request failed: keep the head, show one short message. */}
            {state === 'error' ? (
              <div className="v3-wbPanel v3-wbFailPage">
                <h2>{t.errorTitle}</h2>
                <p>{t.errorBody}</p>
                <button type="button" className="v3-btn v3-btn--primary" onClick={onExit}>
                  {t.reload}
                </button>
              </div>
            ) : null}

            {state === 'data' || state === 'partial' ? (
              <div className="v3-wbGrid">
                <ResumeCenter
                  t={t}
                  draft={draft}
                  upload={upload}
                  userName={f.userName}
                  failed={partialBlock === 'resume'}
                  onRetry={onExit}
                />
                <div className="v3-wbCol">
                  <JobsModule t={t} saved={saved} failed={partialBlock === 'jobs'} onRetry={onExit} />
                  <HistoryModule t={t} applied={applied} failed={partialBlock === 'jobs'} onRetry={onExit} />
                </div>
              </div>
            ) : null}
          </main>
        )}
      </div>
    </div>
  )
}
