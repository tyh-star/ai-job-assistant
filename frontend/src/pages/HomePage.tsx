import type { ReactNode } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../auth/AuthContext'
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { jobsTabReturnPath } from '../jobs/jobsTab'
import { cx } from '../components/ui/cx'

const PRIMARY_CTA =
  'inline-flex min-h-11 min-w-0 items-center justify-center rounded-md bg-accent px-5 text-sm font-medium text-white hover:bg-brand'
const SECONDARY_CTA =
  'inline-flex min-h-11 min-w-0 items-center justify-center rounded-md border border-line bg-white px-5 text-sm font-medium text-ink hover:bg-slate-50'

/** Signed-in visitors should land on their console, not the marketing homepage. */
export function HomeOrDashboard() {
  const { isAuthenticated } = useAuth()
  if (isAuthenticated) {
    return <Navigate to="/dashboard" replace />
  }
  return <HomePage />
}

export function HomePage() {
  const { t } = useTranslation()
  const { isAuthenticated } = useAuth()

  function hrefFor(path: string): string {
    return isAuthenticated ? path : loginPathForAnonymousFeature(path)
  }

  return (
    <div className="min-w-0">
      <section className="mx-auto max-w-2xl px-1 pb-6 pt-6 text-center sm:pt-10">
        <h1 className="hero-title">
          {t('home.hero.title')}
        </h1>
        <p className="mx-auto mt-3 max-w-xl text-base text-muted">{t('home.hero.subtitle')}</p>
        <div className="mt-6 flex min-w-0 flex-col items-center justify-center gap-3 sm:flex-row">
          <Link to="/jobs" className={PRIMARY_CTA}>{t('home.hero.findJobs')}</Link>
          <Link to={hrefFor('/resumes/builder')} className={SECONDARY_CTA}>{t('home.hero.buildResume')}</Link>
        </div>
      </section>

      <ul className="mx-auto mt-8 grid min-w-0 max-w-4xl gap-6 sm:grid-cols-3">
        <Feature
          icon={<SearchIcon />}
          iconClass="bg-sky-100 text-brand"
          title={t('home.features.findTitle')}
          body={t('home.features.findBody')}
        >
          <Link to="/jobs" className="text-sm font-medium text-brand hover:underline">
            {t('home.features.findAction')}
          </Link>
        </Feature>
        <Feature
          icon={<ResumeIcon />}
          iconClass="bg-indigo-100 text-indigo-800"
          title={t('home.features.resumeTitle')}
          body={t('home.features.resumeBody')}
        >
          <Link to={hrefFor('/resumes/builder')} className="text-sm font-medium text-brand hover:underline">
            {t('home.features.resumeBuild')}
          </Link>
          <Link to={hrefFor('/resumes')} className="text-sm text-muted hover:text-ink hover:underline">
            {t('home.features.resumeScore')}
          </Link>
        </Feature>
        <Feature
          icon={<TrackIcon />}
          iconClass="bg-slate-200/80 text-brand-deep"
          title={t('home.features.trackTitle')}
          body={t('home.features.trackBody')}
        >
          <Link to={hrefFor('/tailor')} className="text-sm font-medium text-brand hover:underline">
            {t('home.features.trackTailor')}
          </Link>
          <Link to={hrefFor(jobsTabReturnPath('SAVED'))} className="text-sm text-muted hover:text-ink hover:underline">
            {t('home.features.trackManage')}
          </Link>
        </Feature>
      </ul>
    </div>
  )
}

function Feature({
  icon,
  iconClass,
  title,
  body,
  children,
}: {
  icon: ReactNode
  iconClass: string
  title: string
  body: string
  children: ReactNode
}) {
  return (
    <li className="home-feature">
      <div className={cx('home-feature-icon', iconClass)}>{icon}</div>
      <h2 className="section-title mt-3">{title}</h2>
      <p className="mt-2 text-sm text-muted">{body}</p>
      <div className="mt-3 flex min-w-0 flex-wrap gap-x-4 gap-y-2">{children}</div>
    </li>
  )
}

function SearchIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
      <circle cx="9" cy="9" r="5.5" />
      <path d="M13.5 13.5 17 17" strokeLinecap="round" />
    </svg>
  )
}

function ResumeIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
      <path d="M5 2.5h7l3 3v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-14a1 1 0 0 1 1-1Z" />
      <path d="M12 2.5V6h3M7 10h6M7 13h4" />
    </svg>
  )
}

function TrackIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true">
      <path d="M4 14.5 8 10l3 3 5-6.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M3.5 16.5h13" strokeLinecap="round" />
    </svg>
  )
}
