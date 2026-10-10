/**
 * The signed-in console at /dashboard.
 *
 * Visual layout comes from target-dashboard.css (TargetDashboard reference).
 * Data comes only from real endpoints:
 *   primary resume     GET /resume-builder/drafts
 *   内容完整             computeDraftReadiness(...) over GET /resume-builder/drafts/{id}
 *   secondary resume   GET /resumes
 *   saved / applied    GET /jobs -> JobResponse.applied
 *
 * 职位 and 最近投递 both read the single GET /jobs call, so they can only fail
 * together. A failed request is never rendered as a zero. Empty states never
 * fall back to the TargetDashboard demo fixture.
 */

import { useEffect, useMemo, useState, type ReactNode, type SVGProps } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listJobs } from '../api/jobs'
import { listResumes } from '../api/resumes'
import { getResumeBuilderDraft, listResumeBuilderDrafts } from '../api/resumeBuilder'
import type {
  JobResponse,
  ResumeBuilderDraftSummary,
  ResumeResponse,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { computeDraftReadiness } from '../resumeBuilder/draftReadiness'
import { displayFileName } from '../resumes/fileName'
import { jobsTabReturnPath } from '../jobs/jobsTab'
import { resolveDashboardDensity } from './dashboardDensity'
import './target-dashboard.css'

function SvgIcon(props: SVGProps<SVGSVGElement>) {
  return <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" {...props} />
}

function BriefcaseIcon() {
  return (
    <SvgIcon>
      <path className="td-iconFill" d="M5.6 8.2h12.8a2 2 0 0 1 2 2v7.3a2 2 0 0 1-2 2H5.6a2 2 0 0 1-2-2v-7.3a2 2 0 0 1 2-2Z" />
      <path d="M8.3 8.1V6.6c0-1 .8-1.7 1.7-1.7h4c.9 0 1.7.8 1.7 1.7v1.5M3.8 12.6h16.4M10.3 12.5v2h3.4v-2" />
    </SvgIcon>
  )
}

function ClockIcon() {
  return (
    <SvgIcon>
      <circle className="td-iconFill" cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.7v4.8l3.2 1.8" />
      <circle className="td-iconDot" cx="12" cy="12" r="1.1" />
    </SvgIcon>
  )
}

function FileIcon() {
  return (
    <SvgIcon>
      <path className="td-iconFill" d="M6.5 3.8h7.2l4 4v12.4H6.5z" />
      <path d="M6.5 3.8h7.2l4 4v12.4H6.5zM13.7 3.8v4h4M9.3 12h5.4M9.3 15.3h4.2" />
    </SvgIcon>
  )
}

function SearchIcon() {
  return (
    <SvgIcon>
      <circle cx="10.6" cy="10.6" r="6.2" />
      <path d="m15.2 15.2 4.4 4.4" />
    </SvgIcon>
  )
}

function Arrow() {
  return <span aria-hidden="true" className="td-arrow">→</span>
}

function IconTile({ children }: { children: ReactNode }) {
  return <span className="td-iconTile">{children}</span>
}

function ResumePreview({ owner }: { owner: string }) {
  return (
    <div className="td-resumePreview" aria-hidden="true">
      <span className="td-resumeFold" />
      <strong>{owner}</strong>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <div className="td-previewCols">
        <span className="td-previewLine td-previewLine--short" />
        <span className="td-previewLine td-previewLine--tiny" />
      </div>
      <span className="td-previewLine" />
      <span className="td-previewLine td-previewLine--wide" />
      <span className="td-previewLine td-previewLine--medium" />
      <span className="td-previewLine td-previewLine--wide" />
      <span className="td-previewLine td-previewLine--medium" />
      <span className="td-previewLine td-previewLine--tiny" />
    </div>
  )
}

type ResumeData = {
  draft: ResumeBuilderDraftSummary | null
  draftReady: boolean | null
  uploads: ResumeResponse[]
}

function byNewest<T extends { updatedAt: string }>(rows: T[]): T[] {
  return rows.slice().sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
}

function appliedTimestamp(job: JobResponse): number {
  const parsed = Date.parse(job.appliedAt ?? job.updatedAt)
  return Number.isNaN(parsed) ? 0 : parsed
}

function useDateFormat() {
  const { t, i18n } = useTranslation()
  return (iso: string | null | undefined) => {
    if (!iso) {
      return ''
    }
    const parsed = new Date(iso)
    if (Number.isNaN(parsed.getTime())) {
      return ''
    }
    const date = parsed.toLocaleDateString(i18n.language, { month: 'short', day: 'numeric' })
    return t('dashboard.workbench.updatedOn', { date })
  }
}

function FailBlock({ message, reload, onRetry }: { message: string; reload: string; onRetry: () => void }) {
  return (
    <div className="td-fail" role="status">
      <p>{message}</p>
      <button type="button" className="td-failGhost" onClick={onRetry}>{reload}</button>
    </div>
  )
}

function QuietEmpty({
  body,
  action,
}: {
  body: string
  action?: { to: string; label: string }
}) {
  return (
    <div className="td-quietEmpty">
      <span className="td-quietEmpty__mark" aria-hidden="true" />
      <p>{body}</p>
      {action ? (
        <Link className="td-viewAll" to={action.to}>
          {action.label} <Arrow />
        </Link>
      ) : null}
    </div>
  )
}

function ResumeCenter({
  data,
  failed,
  onRetry,
  userName,
}: {
  data: ResumeData
  failed: boolean
  onRetry: () => void
  userName: string
}) {
  const { t } = useTranslation()
  const formatDate = useDateFormat()
  const draft = data.draft
  const leadUpload = draft ? null : (data.uploads[0] ?? null)
  const secondary = draft ? (data.uploads[0] ?? null) : (data.uploads[1] ?? null)
  const scoreTarget = data.uploads[0] ? `/resumes/${data.uploads[0].id}/quality-score` : '/resumes'
  const templateLabel = draft
    ? t(`dashboard.workbench.templates.${draft.templateType}`, {
        defaultValue: String(draft.templateType),
      })
    : ''
  const metaParts = [
    draft ? templateLabel : leadUpload?.fileType,
    formatDate(draft ? draft.updatedAt : leadUpload?.updatedAt),
  ].filter(Boolean)

  return (
    <section className="td-card td-resumeCard" aria-labelledby="td-resumes">
      <div className="td-cardHeading">
        <h2 id="td-resumes">{t('dashboard.workbench.resumeCenter')}</h2>
        {failed ? null : (
          <Link to="/resumes">{t('dashboard.workbench.manageAll')} <Arrow /></Link>
        )}
      </div>

      {failed ? (
        <div className="td-failCenter" role="status">
          <IconTile><FileIcon /></IconTile>
          <p>{t('dashboard.workbench.resumeFailed')}</p>
          <button type="button" className="td-failGhost" onClick={onRetry}>
            {t('dashboard.workbench.reload')}
          </button>
        </div>
      ) : draft || leadUpload ? (
        <div className={secondary ? 'td-resumeMain is-paired' : 'td-resumeMain'}>
          <div className="td-primaryResume">
            <ResumePreview owner={userName} />
            <div className="td-resumeInfo">
              <h3>{draft ? draft.title : displayFileName(leadUpload?.fileName)}</h3>
              {metaParts.length > 0 ? (
                <p className="td-muted td-resumeMeta">{metaParts.join(' · ')}</p>
              ) : null}
              {draft && data.draftReady ? (
                <p className="td-complete">
                  <span className="td-check" aria-hidden="true">✓</span>
                  {t('dashboard.workbench.readyStatus')}
                </p>
              ) : null}
              <div className="td-buttons">
                <Link className="td-button td-button--primary" to="/tailor">
                  {t('dashboard.workbench.tailorByJob')}
                </Link>
                <Link
                  className="td-button td-button--secondary"
                  to={draft ? `/resumes/builder/${draft.id}` : '/resumes/builder'}
                >
                  {draft ? t('dashboard.workbench.editResume') : t('dashboard.workbench.createResume')}
                </Link>
              </div>
              <Link className="td-quality" to={scoreTarget}>
                {t('dashboard.workbench.qualityScore')}
              </Link>
            </div>
          </div>
          {secondary ? (
            <>
              <div className="td-divider" />
              <Link className="td-secondaryResume" to="/resumes">
                <IconTile><FileIcon /></IconTile>
                <span>
                  <strong>{displayFileName(secondary.fileName)}</strong>
                  <small>
                    {secondary.fileType}
                    {secondary.updatedAt ? ` · ${formatDate(secondary.updatedAt)}` : ''}
                  </small>
                </span>
              </Link>
            </>
          ) : null}
        </div>
      ) : (
        <QuietEmpty body={t('dashboard.workbench.emptyResumesHint')} />
      )}
    </section>
  )
}

function JobsCard({
  saved,
  failed,
  onRetry,
}: {
  saved: JobResponse[]
  failed: boolean
  onRetry: () => void
}) {
  const { t } = useTranslation()
  const job = saved[0]

  return (
    <section className="td-card td-jobsCard" aria-labelledby="td-jobs">
      <div className="td-rightHeader">
        <div className="td-titleWithIcon">
          <IconTile><BriefcaseIcon /></IconTile>
          <h2 id="td-jobs">{t('dashboard.workbench.jobs')}</h2>
          {!failed && saved.length > 0 ? (
            <span className="td-pill">{t('dashboard.workbench.savedBadge', { n: saved.length })}</span>
          ) : null}
        </div>
        <Link className="td-searchButton" to="/jobs" aria-label={t('dashboard.workbench.findJobs')}>
          <SearchIcon />
          {t('dashboard.workbench.findJobs')}
        </Link>
      </div>

      {failed ? (
        <FailBlock
          message={t('dashboard.workbench.jobsFailed')}
          reload={t('dashboard.workbench.reload')}
          onRetry={onRetry}
        />
      ) : job ? (
        <div className="td-jobArea">
          <p className="td-muted">{t('dashboard.workbench.continueSaved')}</p>
          <div className="td-jobRow">
            <span className="td-jobMark" />
            <div className="td-jobText">
              <strong>{job.title}</strong>
              <span>{job.company}</span>
              <small>{t('dashboard.workbench.savedStatus')}</small>
            </div>
            <Link to={jobsTabReturnPath('SAVED')}>{t('dashboard.workbench.viewJob')}</Link>
          </div>
        </div>
      ) : (
        <QuietEmpty body={t('dashboard.workbench.emptyJobsHint')} />
      )}
    </section>
  )
}

function ApplicationsCard({
  applied,
  failed,
  onRetry,
}: {
  applied: JobResponse[]
  failed: boolean
  onRetry: () => void
}) {
  const { t, i18n } = useTranslation()
  const rows = applied.slice(0, 3)

  function shortDate(job: JobResponse): string {
    const parsed = new Date(job.appliedAt ?? job.updatedAt)
    if (Number.isNaN(parsed.getTime())) {
      return ''
    }
    return parsed.toLocaleDateString(i18n.language, { month: '2-digit', day: '2-digit' })
  }

  return (
    <section className="td-card td-applicationsCard" aria-labelledby="td-history">
      <div className="td-rightHeader td-historyHeader">
        <div className="td-titleWithIcon">
          <IconTile><ClockIcon /></IconTile>
          <h2 id="td-history">{t('dashboard.workbench.recentApplied')}</h2>
        </div>
        {!failed && applied.length > 0 ? (
          <Link className="td-viewAll" to={jobsTabReturnPath('APPLIED')}>
            {t('dashboard.workbench.viewAll')} <Arrow />
          </Link>
        ) : null}
      </div>

      {failed ? (
        <FailBlock
          message={t('dashboard.workbench.jobsFailed')}
          reload={t('dashboard.workbench.reload')}
          onRetry={onRetry}
        />
      ) : rows.length > 0 ? (
        <div className="td-timeline">
          {rows.map((job, index) => (
            <Link className="td-application" key={job.id} to={jobsTabReturnPath('APPLIED')}>
              <div className="td-dateColumn">
                <time dateTime={job.appliedAt ?? undefined}>{shortDate(job)}</time>
                {index < rows.length - 1 ? <span className="td-rail"><i /></span> : null}
              </div>
              <div className="td-applicationText">
                <strong>{job.title}</strong>
                <span>{job.company}</span>
              </div>
            </Link>
          ))}
        </div>
      ) : (
        <QuietEmpty
          body={t('dashboard.workbench.emptyAppliedHint')}
          action={{ to: '/jobs', label: t('dashboard.browseJobs') }}
        />
      )}
    </section>
  )
}

function OnboardPanel() {
  const { t } = useTranslation()
  const steps = [
    {
      title: t('dashboard.workbench.step1Title'),
      body: t('dashboard.workbench.step1Body'),
      icon: <FileIcon />,
      current: true,
      link: null as { to: string; label: string } | null,
    },
    {
      title: t('dashboard.workbench.step2Title'),
      body: t('dashboard.workbench.step2Body'),
      icon: <SearchIcon />,
      current: false,
      link: { to: '/jobs', label: t('dashboard.workbench.findJobs') },
    },
    {
      title: t('dashboard.workbench.step3Title'),
      body: t('dashboard.workbench.step3Body'),
      icon: <BriefcaseIcon />,
      current: false,
      link: { to: '/tailor', label: t('dashboard.workbench.step3Link') },
    },
  ]

  return (
    <section className="td-onboard">
      <div className="td-onboardAside">
        <h2>{t('dashboard.workbench.onboardTitle')}</h2>
        <p>{t('dashboard.workbench.onboardBody')}</p>
        <p className="td-onboardProgress">{t('dashboard.workbench.onboardProgress')}</p>
        <div className="td-onboardBar" aria-hidden="true"><i /></div>
        <Link className="td-button td-button--primary" to="/resumes/builder">
          {t('dashboard.workbench.createOrUpload')}
        </Link>
      </div>
      <ol className="td-onboardSteps">
        {steps.map((step, index) => (
          <li key={step.title} className="td-onboardStep">
            <span className="td-onboardNum" aria-hidden="true">
              {step.icon}
              <b>{index + 1}</b>
            </span>
            <div className="td-onboardBody">
              <h3>
                {step.title}
                {step.current ? (
                  <span className="td-onboardNow">{t('dashboard.workbench.currentStep')}</span>
                ) : null}
              </h3>
              <p>{step.body}</p>
              {step.link ? (
                <Link className="td-viewAll" to={step.link.to}>
                  {step.link.label} <Arrow />
                </Link>
              ) : null}
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}

function LoadingView() {
  const { t } = useTranslation()
  return (
    <div className="td-grid" role="status" aria-label={t('common.loading')}>
      <section className="td-card td-resumeCard">
        <div className="td-cardHeading">
          <h2>{t('dashboard.workbench.resumeCenter')}</h2>
        </div>
        <div className="td-resumeMain">
          <div className="td-primaryResume">
            <div className="td-resumePreview td-resumePreview--skel" />
            <div className="td-resumeInfo">
              <span className="td-skel" style={{ width: '78%', height: 22 }} />
              <span className="td-skel" style={{ width: '48%', height: 14, marginTop: 12 }} />
              <span className="td-skel" style={{ width: '62%', height: 16, marginTop: 18 }} />
              <div className="td-buttons" style={{ marginTop: 17 }}>
                <span className="td-skel td-skel--btn" />
                <span className="td-skel td-skel--btn" />
              </div>
            </div>
          </div>
        </div>
      </section>
      <div className="td-rightColumn">
        <section className="td-card td-jobsCard">
          <div className="td-rightHeader">
            <div className="td-titleWithIcon">
              <IconTile><BriefcaseIcon /></IconTile>
              <h2>{t('dashboard.workbench.jobs')}</h2>
            </div>
          </div>
          <div className="td-jobArea">
            <span className="td-skel" style={{ width: '32%', height: 13, marginBottom: 11 }} />
            <span className="td-skel" style={{ width: '70%', height: 16 }} />
            <span className="td-skel" style={{ width: '46%', height: 13, marginTop: 8 }} />
          </div>
        </section>
        <section className="td-card td-applicationsCard">
          <div className="td-rightHeader td-historyHeader">
            <div className="td-titleWithIcon">
              <IconTile><ClockIcon /></IconTile>
              <h2>{t('dashboard.workbench.recentApplied')}</h2>
            </div>
          </div>
          <div className="td-timeline">
            {[0, 1, 2].map((index) => (
              <div className="td-application" key={index}>
                <div className="td-dateColumn">
                  <span className="td-skel" style={{ width: 78, height: 40, borderRadius: 10 }} />
                </div>
                <div className="td-applicationText">
                  <span className="td-skel" style={{ width: `${54 + index * 8}%`, height: 14 }} />
                  <span className="td-skel" style={{ width: '40%', height: 12 }} />
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}

export function DashboardPage() {
  const { t } = useTranslation()
  const { user } = useAuth()

  const [resumes, setResumes] = useState<ResumeData | null>(null)
  const [jobs, setJobs] = useState<JobResponse[] | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    setLoading(true)

    const [resumeResult, jobResult] = await Promise.allSettled([
      Promise.all([listResumeBuilderDrafts(), listResumes()]),
      listJobs(),
    ])

    if (resumeResult.status === 'fulfilled') {
      const [draftResponse, resumeResponse] = resumeResult.value
      const drafts = byNewest(draftResponse.data)
      const draft = drafts[0] ?? null
      const uploads = byNewest(resumeResponse.data.filter((resume) => resume.origin !== 'TEMPLATE'))

      let draftReady: boolean | null = null
      if (draft) {
        try {
          const detail = await getResumeBuilderDraft(draft.id)
          draftReady = computeDraftReadiness(detail.data.content, detail.data.targetRole ?? '', {
            name: t('resumeBuilder.fields.fullName'),
            contact: t('resumeBuilder.fields.email'),
            target: t('resumeBuilder.fields.targetTitle'),
          }).readyToGenerate
        } catch {
          draftReady = null
        }
      }
      setResumes({ draft, draftReady, uploads })
    } else {
      setResumes(null)
    }

    setJobs(jobResult.status === 'fulfilled' ? jobResult.value.data : null)
    setLoading(false)
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const saved = useMemo(() => (jobs ?? []).filter((job) => !job.applied), [jobs])
  const applied = useMemo(
    () =>
      (jobs ?? [])
        .filter((job) => job.applied)
        .slice()
        .sort((left, right) => appliedTimestamp(right) - appliedTimestamp(left)),
    [jobs],
  )

  const firstName = (user?.name ?? '').trim().split(/\s+/)[0] || (user?.name ?? '')
  const resumesFailed = !loading && resumes === null
  const jobsFailed = !loading && jobs === null
  const everythingFailed = resumesFailed && jobsFailed
  const hasResume = Boolean(resumes?.draft) || (resumes?.uploads.length ?? 0) > 0
  const density = resolveDashboardDensity({
    loading,
    resumesFailed,
    jobsFailed,
    hasResume,
    hasJobs: saved.length > 0,
    hasApplications: applied.length > 0,
  })
  const isEmpty = density === 'empty'

  return (
    <div className="td-page" data-density={density}>
      <main className="td-shell">
        <section className="td-greeting">
          <h1>{t('dashboard.welcome', { name: firstName })}</h1>
          <span className="td-greetingRule" aria-hidden="true" />
          <p>{isEmpty ? t('dashboard.workbench.newSubtitle') : t('dashboard.workbench.subtitle')}</p>
        </section>

        {isEmpty ? <OnboardPanel /> : null}
        {loading ? <LoadingView /> : null}

        {!loading && everythingFailed ? (
          <div className="td-failPage">
            <h2>{t('dashboard.workbench.errorTitle')}</h2>
            <p>{t('dashboard.workbench.errorBody')}</p>
            <button type="button" className="td-button td-button--primary" onClick={() => void load()}>
              {t('dashboard.workbench.reload')}
            </button>
          </div>
        ) : null}

        {!loading && !isEmpty && !everythingFailed ? (
          <div className="td-grid">
            <ResumeCenter
              data={resumes ?? { draft: null, draftReady: null, uploads: [] }}
              failed={resumesFailed}
              onRetry={() => void load()}
              userName={user?.name ?? ''}
            />
            <div className="td-rightColumn">
              <JobsCard saved={saved} failed={jobsFailed} onRetry={() => void load()} />
              <ApplicationsCard applied={applied} failed={jobsFailed} onRetry={() => void load()} />
            </div>
          </div>
        ) : null}
      </main>
    </div>
  )
}
