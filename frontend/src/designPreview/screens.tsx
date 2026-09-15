import { Link } from 'react-router-dom'
import type { ReactNode } from 'react'
import { COPY, type PreviewLang } from './copy'
import { PreviewHeader } from './PreviewHeader'
import {
  EMPLOYER,
  JOB_DETAIL,
  JOB_RESULTS,
  PERSON,
  RECENT_APPLICATIONS,
  RECENT_RESUMES,
  RECENT_SEARCHES,
  STATS,
  SYNTHETIC_FIXTURE_NOTICE,
} from './fixture'
import { jobsTabReturnPath } from '../jobs/jobsTab'

function IconCheck() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 10.5 8 14l8-8" />
    </svg>
  )
}

function IconDoc() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M5 2.5h7l3 3v12H5a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" />
      <path d="M12 2.5V6h3" />
    </svg>
  )
}

function IconSearch() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <circle cx="9" cy="9" r="5.5" />
      <path d="M13.5 13.5 17 17" />
    </svg>
  )
}

function IconTrack() {
  return (
    <svg className="ds-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M4 14.5 8 10l3 3 5-6.5" />
      <path d="M3.5 16.5h13" />
    </svg>
  )
}

type DashCopy = (typeof COPY)[PreviewLang]['dash']

function dashboardHello(lang: PreviewLang): string {
  return COPY[lang].dash.hello.replace('{name}', PERSON.name)
}

function DashboardShell({
  lang,
  onLangChange,
  children,
  showActions = true,
  note,
}: {
  lang: PreviewLang
  onLangChange: (next: PreviewLang) => void
  children: ReactNode
  showActions?: boolean
  note?: ReactNode
}) {
  const t = COPY[lang].dash
  return (
    <div className="ds-dash">
      <PreviewHeader
        lang={lang}
        onLangChange={onLangChange}
        active="console"
        signedIn
        previewUserName={PERSON.name}
      />
      <main className="ds-shell">
        <header className="ds-page-head">
          <div>
            <h1 className="ds-h1">{t.title}</h1>
            <p className="ds-helper">{dashboardHello(lang)}</p>
            {note}
          </div>
          {showActions ? (
            <div className="ds-actions">
              <Link to="/jobs" className="ds-btn ds-btn-primary">{t.findJobs}</Link>
              <Link to="/resumes/builder" className="ds-btn ds-btn-secondary">{t.createResume}</Link>
            </div>
          ) : null}
        </header>
        {children}
      </main>
    </div>
  )
}

function StatRow({
  t,
  today,
  last7,
  saved,
  applied,
  quiet,
}: {
  t: DashCopy
  today: number
  last7: number
  saved: number
  applied: number
  quiet?: boolean
}) {
  const items = [
    { label: t.today, value: today },
    { label: t.last7, value: last7 },
    { label: t.saved, value: saved },
    { label: t.applied, value: applied },
  ]
  return (
    <ul className={`ds-stat-row${quiet ? ' is-quiet' : ''}`} aria-label={t.stats}>
      {items.map((item) => (
        <li key={item.label}>
          <span className="ds-stat-label">{item.label}</span>
          <span className="ds-stat-value">{item.value}</span>
        </li>
      ))}
    </ul>
  )
}

function RecentActivityPair({
  t,
  searches,
  resumes,
}: {
  t: DashCopy
  searches: typeof RECENT_SEARCHES | readonly { id: string; keywords: string; lastSearchedAt: string }[]
  resumes: typeof RECENT_RESUMES | readonly { id: string; fileName: string; updated: string }[]
}) {
  const showSearches = searches.length > 0
  const showResumes = resumes.length > 0
  if (!showSearches && !showResumes) {
    return null
  }
  return (
    <div className={`ds-pair${showSearches && showResumes ? '' : ' is-single'}`}>
      {showSearches ? (
        <section className="ds-panel">
          <div className="ds-section-head">
            <h2 className="ds-h2">{t.recentSearches}</h2>
            <Link to="/jobs" className="ds-link">{t.viewAll}</Link>
          </div>
          <ul className="ds-activity">
            {searches.map((item) => (
              <li key={item.id}>
                <div>
                  <p className="ds-h3">{item.keywords}</p>
                  <p className="ds-helper">{item.lastSearchedAt}</p>
                </div>
                <Link to={`/jobs?q=${encodeURIComponent(item.keywords)}`} className="ds-link">{t.replaySearch}</Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {showResumes ? (
        <section className="ds-panel">
          <div className="ds-section-head">
            <h2 className="ds-h2">{t.recentResumes}</h2>
            <Link to="/resumes" className="ds-link">{t.viewAll}</Link>
          </div>
          <ul className="ds-activity">
            {resumes.map((resume) => (
              <li key={resume.id}>
                <div>
                  <p className="ds-h3">{resume.fileName}</p>
                  <p className="ds-helper">{resume.updated}</p>
                </div>
                <Link to="/resumes" className="ds-link">{t.openResume}</Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  )
}

export function DashboardNewScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].dash
  return (
    <DashboardShell lang={lang} onLangChange={onLangChange} showActions={false}>
        <section className="ds-start" aria-label={t.start}>
          <div className="ds-start-block">
            <h2 className="ds-h2">{t.startResume}</h2>
            <p className="ds-helper">{t.startResumeHint}</p>
            <div className="ds-start-actions">
              <Link to="/resumes/builder" className="ds-btn ds-btn-primary">{t.createResume}</Link>
              <Link to="/resumes" className="ds-btn ds-btn-secondary">{t.uploadResume}</Link>
            </div>
          </div>
          <div className="ds-start-block">
            <h2 className="ds-h2">{t.startJobs}</h2>
            <p className="ds-helper">{t.startJobsHint}</p>
            <div className="ds-start-actions">
              <Link to="/jobs" className="ds-btn ds-btn-secondary">{t.findJobs}</Link>
            </div>
          </div>
        </section>

        <StatRow t={t} today={0} last7={0} saved={0} applied={0} quiet />
        <RecentActivityPair t={t} searches={[]} resumes={[]} />
    </DashboardShell>
  )
}

export function DashboardDataScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].dash
  return (
    <DashboardShell
      lang={lang}
      onLangChange={onLangChange}
      note={<p className="ds-demo-flag">{t.demo}</p>}
    >
        <StatRow t={t} today={STATS.todayApplied} last7={STATS.last7Applied} saved={STATS.saved} applied={STATS.applied} />

        <section className="ds-panel">
          <div className="ds-section-head">
            <h2 className="ds-h2">{t.recentApplied}</h2>
            <Link to={jobsTabReturnPath('APPLIED')} className="ds-link">{t.viewAll}</Link>
          </div>
          <ul className="ds-activity">
            {RECENT_APPLICATIONS.map((job) => (
              <li key={job.id}>
                <div>
                  <p className="ds-h3">{job.title}</p>
                  <p className="ds-helper">{job.company} · {job.appliedOn}</p>
                </div>
                <Link to={jobsTabReturnPath('APPLIED')} className="ds-link">{t.viewApplied}</Link>
              </li>
            ))}
          </ul>
        </section>

        <RecentActivityPair t={t} searches={RECENT_SEARCHES} resumes={RECENT_RESUMES} />
    </DashboardShell>
  )
}

export function DashboardLoadingScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].dash
  return (
    <DashboardShell lang={lang} onLangChange={onLangChange}>
        <div className="ds-loading" aria-busy="true" aria-live="polite">
          <span className="ds-sr">{t.loading}</span>
          <div className="ds-skeleton" />
          <div className="ds-skeleton" />
          <div className="ds-skeleton is-short" />
        </div>
    </DashboardShell>
  )
}

export function DashboardErrorScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].dash
  return (
    <DashboardShell lang={lang} onLangChange={onLangChange}>
        <div className="ds-alert" role="alert">
          <p>{t.error}</p>
          <button type="button" className="ds-link">{t.retry}</button>
        </div>
    </DashboardShell>
  )
}

export function HomeScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].home
  return (
    <>
      <PreviewHeader lang={lang} onLangChange={onLangChange} active="home" />
      <main className="ds-shell">
        <section className="ds-home-hero">
          <h1 className="ds-hero-title">{t.title}</h1>
          <p className="ds-helper">{t.subtitle}</p>
          <div className="ds-home-ctas">
            <span className="ds-btn ds-btn-primary">{t.primary}</span>
            <span className="ds-btn ds-btn-secondary">{t.secondary}</span>
          </div>
        </section>

        <ul className="ds-home-features">
          <li className="ds-home-feature">
            <div className="ds-home-feature-icon is-jobs"><IconSearch /></div>
            <h2 className="ds-h2">{t.cap1t}</h2>
            <p className="ds-helper">{t.cap1b}</p>
            <div className="ds-home-feature-actions">
              <span className="ds-link">{t.cap1a}</span>
            </div>
          </li>
          <li className="ds-home-feature">
            <div className="ds-home-feature-icon is-resume"><IconDoc /></div>
            <h2 className="ds-h2">{t.cap2t}</h2>
            <p className="ds-helper">{t.cap2b}</p>
            <div className="ds-home-feature-actions">
              <span className="ds-link">{t.cap2a}</span>
              <span className="ds-link is-quiet">{t.cap2s}</span>
            </div>
          </li>
          <li className="ds-home-feature">
            <div className="ds-home-feature-icon is-track"><IconTrack /></div>
            <h2 className="ds-h2">{t.cap3t}</h2>
            <p className="ds-helper">{t.cap3b}</p>
            <div className="ds-home-feature-actions">
              <span className="ds-link">{t.cap3a}</span>
              <span className="ds-link is-quiet">{t.cap3s}</span>
            </div>
          </li>
        </ul>
      </main>
    </>
  )
}


export function JobsScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].jobs
  return (
    <>
      <PreviewHeader lang={lang} onLangChange={onLangChange} active="jobs" />
      <main className="ds-shell">
        <header className="ds-page-head">
          <h1 className="ds-h1">{t.title}</h1>
        </header>
        <form className="ds-search-bar" onSubmit={(event) => event.preventDefault()}>
          <div className="ds-field">
            <label htmlFor="ds-q">{t.searchLabel}</label>
            <input id="ds-q" className="ds-input" defaultValue={t.searchValue} readOnly />
          </div>
          <div className="ds-field">
            <label htmlFor="ds-city">{t.location}</label>
            <input id="ds-city" className="ds-input" defaultValue={t.locationValue} readOnly />
          </div>
          <div className="ds-field">
            <label htmlFor="ds-mode">{t.workMode}</label>
            <select id="ds-mode" className="ds-select" defaultValue="Hybrid" disabled>
              <option>Hybrid</option>
            </select>
          </div>
          <span className="ds-btn ds-btn-primary">{t.submit}</span>
        </form>
        <p className="ds-helper" style={{ marginBottom: '1rem' }}>{t.results} · {SYNTHETIC_FIXTURE_NOTICE}</p>
        <div>
          {JOB_RESULTS.map((job) => (
            <article key={job.id} className="ds-job-row">
              <h2 className="ds-h3">{job.title}</h2>
              <p className="ds-job-meta">
                <span>{job.company}</span>
                <span>{job.location}</span>
                <span>{job.posted}</span>
              </p>
              <div className="ds-job-tags">
                <span className="ds-badge">{job.workMode}</span>
                <span className="ds-badge">{job.employment}</span>
                {job.saved ? <span className="ds-badge ds-badge-accent">{t.saved}</span> : null}
                {job.applied ? <span className="ds-badge ds-badge-success">{t.applied}</span> : null}
              </div>
            </article>
          ))}
        </div>
      </main>
    </>
  )
}

export function JobDetailScreen({ lang, onLangChange }: { lang: PreviewLang; onLangChange: (next: PreviewLang) => void }) {
  const t = COPY[lang].detail
  const job = JOB_DETAIL
  return (
    <>
      <PreviewHeader lang={lang} onLangChange={onLangChange} active="jobs" />
      <main className="ds-shell">
        <p className="ds-link" style={{ marginBottom: '1.5rem' }}>{t.back}</p>
        <div className="ds-detail">
          <article>
            <p className="ds-eyebrow">{job.company}</p>
            <h1 className="ds-h1">{job.title}</h1>
            <p className="ds-helper" style={{ marginTop: '0.75rem' }}>
              {job.location} · {job.workMode} · {job.employment} · {job.posted}
            </p>
            <div className="ds-job-tags" style={{ marginTop: '1rem' }}>
              <span className="ds-badge">{job.workMode}</span>
              <span className="ds-badge">{job.employment}</span>
              <span className="ds-badge">{EMPLOYER.name}</span>
            </div>
            <p className="ds-fixture-note">{SYNTHETIC_FIXTURE_NOTICE}</p>

            <h2 className="ds-h2" style={{ marginTop: '2rem' }}>{t.duties}</h2>
            <ul className="ds-prose">
              {job.responsibilities.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>

            <h2 className="ds-h2" style={{ marginTop: '2rem' }}>{t.requirements}</h2>
            <ul className="ds-prose">
              {job.requirements.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </article>
          <aside className="ds-detail-actions">
            <span className="ds-btn ds-btn-primary">{t.apply}</span>
            <span className="ds-btn ds-btn-secondary">{t.save}</span>
            <span className="ds-link">{t.markApplied}</span>
          </aside>
        </div>
      </main>
    </>
  )
}

export function TokensScreen({ lang }: { lang: PreviewLang }) {
  const t = COPY[lang].tokens
  const colors = [
    ['brand', '#2563eb'],
    ['bg', '#f6f7f8'],
    ['surface', '#ffffff'],
    ['border', '#e5e7eb'],
    ['text', '#111827'],
    ['muted', '#4b5563'],
    ['success', '#067647'],
    ['warning', '#b54708'],
    ['danger', '#b42318'],
  ] as const
  return (
    <main className="ds-shell">
      <h1 className="ds-h1">{t.title}</h1>
      <p className="ds-helper" style={{ marginTop: '0.75rem', maxWidth: '40rem' }}>{t.intro}</p>
      <hr className="ds-rule" />
      <h2 className="ds-h2">Color</h2>
      <div className="ds-token-grid" style={{ marginTop: '1rem' }}>
        {colors.map(([name, hex]) => (
          <div key={name} className="ds-token-swatch">
            <span className="ds-swatch" style={{ background: hex }} />
            <div>
              <p className="ds-h3">{name}</p>
              <p className="ds-helper">{hex}</p>
            </div>
          </div>
        ))}
      </div>
      <hr className="ds-rule" />
      <h2 className="ds-h2">Type</h2>
      <p className="ds-display" style={{ marginTop: '1rem' }}>Display 40 / 600</p>
      <p className="ds-h1">H1 28 / 600</p>
      <p className="ds-h2">H2 20 / 600</p>
      <p className="ds-h3">H3 16 / 600</p>
      <p>Body 16 / 400 — 正文保持常规字重。</p>
      <p className="ds-helper">Helper 14 / 400 — 辅助信息不低于 14px。</p>
      <hr className="ds-rule" />
      <h2 className="ds-h2">Controls</h2>
      <div className="ds-actions" style={{ marginTop: '1rem' }}>
        <span className="ds-btn ds-btn-primary">Primary 44px</span>
        <span className="ds-btn ds-btn-secondary">Secondary</span>
        <span className="ds-link">Text link</span>
        <span className="ds-badge">Badge</span>
        <span className="ds-badge ds-badge-success"><IconCheck /> Success</span>
      </div>
      <p className="ds-helper" style={{ marginTop: '1.5rem' }}>
        Space: 4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 · Radius 8–12 · Shadow 1px hairline
      </p>
    </main>
  )
}
