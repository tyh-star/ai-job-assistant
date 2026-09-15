import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  clearJobCatalogApplied,
  getJobCatalogJob,
  markJobCatalogApplied,
  saveJobCatalogJob,
} from '../api/jobCatalog'
import { deleteJob } from '../api/jobs'
import type { JobCatalogDetail } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { loginPathForAnonymousFeature } from '../auth/returnTo'
import { Alert } from '../components/Alert'
import { JobDescriptionRenderer } from '../components/JobDescriptionRenderer'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { LoadingSkeleton } from '../components/ui/LoadingSkeleton'
import { catalogDetailLoadError, safeHttpUrl } from '../jobCatalog/catalogDetailError'
import { catalogJobIdFromParam, catalogJobPath, isSpecifiedCatalogFacet } from '../jobCatalog/catalogJobPath'
import { countryLabel } from '../jobCatalog/countryLabel'
import { jobsSearchListPath } from '../jobCatalog/searchScroll'

function BookmarkIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M5 3.5A1.5 1.5 0 0 1 6.5 2h7A1.5 1.5 0 0 1 15 3.5v13.2l-5-2.7-5 2.7V3.5Z" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M4 10.5 8 14l8-8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

export function JobCatalogDetailPage() {
  const { t, i18n } = useTranslation()
  const { isAuthenticated } = useAuth()
  const navigate = useNavigate()
  const rawId = useParams().catalogJobId
  const catalogJobId = catalogJobIdFromParam(rawId)
  const detailPath = catalogJobPath(catalogJobId)
  const backToSearch = jobsSearchListPath()

  const [job, setJob] = useState<JobCatalogDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<ReturnType<typeof catalogDetailLoadError> | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [applyBusy, setApplyBusy] = useState(false)
  const [applyError, setApplyError] = useState('')
  const [pendingApply, setPendingApply] = useState(false)
  const [confirmApply, setConfirmApply] = useState(false)
  const [detailUnavailable, setDetailUnavailable] = useState(false)

  const load = useCallback(async () => {
    if (!catalogJobId) {
      setLoadError({ kind: 'gone' })
      setLoading(false)
      return
    }
    setLoading(true)
    setLoadError(null)
    try {
      const response = await getJobCatalogJob(catalogJobId)
      setJob(response.data)
      setDetailUnavailable(false)
    } catch (error) {
      setJob(null)
      setLoadError(catalogDetailLoadError(error))
    } finally {
      setLoading(false)
    }
  }, [catalogJobId])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === 'visible' && pendingApply) {
        setConfirmApply(true)
        setPendingApply(false)
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onVisible)
    }
  }, [pendingApply])

  function requireLogin() {
    navigate(loginPathForAnonymousFeature(detailPath))
  }

  async function onSave() {
    if (!isAuthenticated) {
      requireLogin()
      return
    }
    setSaving(true)
    setSaveError('')
    try {
      const response = await saveJobCatalogJob(catalogJobId)
      setJob((current) =>
        current
          ? { ...current, saved: true, personalJobId: response.data.jobId }
          : current,
      )
    } catch (error) {
      if (apiErrorKey(error) === 'errors.jobDetailUnavailable') {
        setDetailUnavailable(true)
      } else {
        setSaveError(t('jobCatalog.saveFailed'))
      }
    } finally {
      setSaving(false)
    }
  }

  async function onUnsave() {
    if (!isAuthenticated) {
      requireLogin()
      return
    }
    const personalJobId = job?.personalJobId
    if (!personalJobId) {
      return
    }
    setSaving(true)
    setSaveError('')
    try {
      await deleteJob(personalJobId)
      setJob((current) =>
        current
          ? { ...current, saved: false, personalJobId: null, applied: false, appliedAt: null }
          : current,
      )
    } catch {
      setSaveError(t('jobCatalog.unsaveFailed'))
    } finally {
      setSaving(false)
    }
  }

  function onApplyLinkClicked() {
    if (isAuthenticated) {
      setPendingApply(true)
    }
  }

  async function onConfirmApplied() {
    if (!isAuthenticated) {
      requireLogin()
      return
    }
    setApplyBusy(true)
    setApplyError('')
    try {
      const response = await markJobCatalogApplied(catalogJobId)
      setJob((current) =>
        current
          ? {
              ...current,
              saved: true,
              personalJobId: response.data.jobId,
              applied: true,
              appliedAt: response.data.job.appliedAt,
            }
          : current,
      )
      setConfirmApply(false)
    } catch (error) {
      if (apiErrorKey(error) === 'errors.jobDetailUnavailable') {
        setDetailUnavailable(true)
      } else {
        setApplyError(t('jobCatalog.appliedFailed'))
      }
    } finally {
      setApplyBusy(false)
    }
  }

  async function onRevokeApplied() {
    if (!isAuthenticated) {
      requireLogin()
      return
    }
    setApplyBusy(true)
    setApplyError('')
    try {
      await clearJobCatalogApplied(catalogJobId)
      setJob((current) =>
        current ? { ...current, applied: false, appliedAt: null } : current,
      )
    } catch {
      setApplyError(t('jobCatalog.appliedFailed'))
    } finally {
      setApplyBusy(false)
    }
  }

  const originalUrl = safeHttpUrl(job?.applyUrl ?? job?.canonicalUrl)
  const locationLine = [job?.locationText, job?.countryCode ? countryLabel(job.countryCode, i18n.language) : '']
    .filter(Boolean)
    .join(' · ')
  const showRemote = isSpecifiedCatalogFacet(job?.remoteType)
  const showEmployment = isSpecifiedCatalogFacet(job?.employmentType)

  function loadErrorMessage() {
    if (!loadError) {
      return ''
    }
    if (loadError.kind === 'gone') {
      return t('jobCatalog.detailGone')
    }
    if (loadError.kind === 'rateLimited' && loadError.retryAfter) {
      return t('jobCatalog.detailRateLimitedWithRetry', { retryAfter: loadError.retryAfter })
    }
    if (loadError.kind === 'rateLimited') {
      return t('jobCatalog.detailRateLimited')
    }
    return t('jobCatalog.detailTemporarilyUnavailable')
  }

  function applyButton(className: string) {
    if (!originalUrl) {
      return null
    }
    return (
      <a
        className={className}
        href={originalUrl}
        target="_blank"
        rel="noreferrer"
        onClick={onApplyLinkClicked}
      >
        {t('jobCatalog.applyOnOriginal')}
      </a>
    )
  }

  function saveButton(className = 'w-full') {
    if (job?.saved) {
      return (
        <Button variant="secondary" className={className} disabled={saving || !job.personalJobId} onClick={() => void onUnsave()}>
          {saving ? t('common.saving') : t('jobCatalog.unsave')}
        </Button>
      )
    }
    return (
      <Button variant="secondary" className={className} disabled={saving || detailUnavailable} onClick={() => void onSave()}>
        {saving ? t('jobCatalog.saving') : t('jobCatalog.save')}
      </Button>
    )
  }

  function appliedButton(className = 'w-full sm:w-auto') {
    if (job?.applied) {
      return (
        <Button variant="ghost" className={className} disabled={applyBusy} onClick={() => void onRevokeApplied()}>
          {t('jobCatalog.appliedRevoke')}
        </Button>
      )
    }
    return (
      <Button variant="secondary" className={className} disabled={applyBusy || detailUnavailable} onClick={() => void onConfirmApplied()}>
        {t('jobs.confirmApplied')}
      </Button>
    )
  }

  function tailorLinks() {
    if (!job?.saved || !job.personalJobId) {
      return null
    }
    return (
      <div className="flex flex-col gap-2">
        <a className="text-sm text-brand underline" href={`/tailor?jobId=${job.personalJobId}`}>
          {t('jobCatalog.analyzeMatch')}
        </a>
        <a className="text-sm text-brand underline" href={`/tailor?jobId=${job.personalJobId}`}>
          {t('jobCatalog.generateSuggestions')}
        </a>
      </div>
    )
  }

  function statusBadges() {
    return (
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        {job?.status === 'EXPIRED' ? <Badge tone="warning">{t('jobCatalog.expiredBadge')}</Badge> : null}
        {job?.saved ? (
          <Badge tone="accent" icon={<BookmarkIcon />}>{t('jobCatalog.savedBadge')}</Badge>
        ) : null}
        {job?.applied ? (
          <Badge tone="success" icon={<CheckIcon />}>{t('jobCatalog.appliedBadge')}</Badge>
        ) : null}
        {detailUnavailable ? (
          <Badge tone="danger">{t('jobCatalog.detailUnavailableBadge')}</Badge>
        ) : null}
      </div>
    )
  }

  function facetBadges() {
    if (!job) {
      return null
    }
    return (
      <div className="mt-3 flex flex-wrap gap-1.5">
        {showRemote ? <Badge>{t(`jobCatalog.remote.${job.remoteType}`)}</Badge> : null}
        {showEmployment ? <Badge>{t(`jobCatalog.employment.${job.employmentType}`)}</Badge> : null}
        {job.postedAt ? <Badge>{`${t('jobCatalog.postedAt')}: ${job.postedAt.slice(0, 10)}`}</Badge> : null}
      </div>
    )
  }

  function metaRow(label: string, value: ReactNode) {
    return (
      <div>
        <dt className="text-muted">{label}</dt>
        <dd className="break-words text-ink">{value}</dd>
      </div>
    )
  }

  const primaryApplyClass =
    'inline-flex min-h-11 w-full items-center justify-center rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-brand'

  return (
    <div className="pb-28 lg:pb-0">
      <Link className="text-sm text-brand underline" to={backToSearch}>
        {t('jobCatalog.backToSearch')}
      </Link>

      {loading ? (
        <div className="mt-6">
          <LoadingSkeleton rows={6} label={t('jobCatalog.detailLoading')} />
        </div>
      ) : null}

      {loadError ? (
        <div className="mt-6 max-w-xl">
          <Alert message={loadErrorMessage()} onRetry={loadError.kind === 'gone' ? undefined : () => void load()} />
          <Link className="mt-4 inline-block text-sm text-brand underline" to={backToSearch}>
            {t('jobCatalog.backToSearch')}
          </Link>
        </div>
      ) : null}

      {job && !loading ? (
        <>
          <header className="mt-4 min-w-0">
            <h1 className="page-title">{job.title}</h1>
            <p className="mt-2 text-lg text-ink">{job.companyName}</p>
            {locationLine ? <p className="mt-1 text-sm text-muted">{locationLine}</p> : null}
            <div className="mt-3">{statusBadges()}</div>
            {facetBadges()}
          </header>

          <div className="mt-6 flex min-w-0 flex-col gap-3 sm:flex-row sm:flex-wrap">
            {applyButton(primaryApplyClass)}
            {saveButton('w-full sm:w-auto')}
            {appliedButton()}
          </div>
          <div className="mt-3 lg:hidden">{tailorLinks()}</div>

          {detailUnavailable ? (
            <div className="mt-4"><Alert message={t('errors.jobDetailUnavailable')} /></div>
          ) : null}
          {saveError ? <div className="mt-4"><Alert message={saveError} /></div> : null}
          {applyError ? <div className="mt-4"><Alert message={applyError} /></div> : null}
          {confirmApply ? (
            <div className="mt-4 rounded-lg border border-warning/40 bg-warning-soft p-4 text-sm text-warning">
              <p>{t('jobCatalog.appliedConfirmPrompt')}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <Button size="sm" disabled={applyBusy} onClick={() => void onConfirmApplied()}>
                  {t('jobCatalog.appliedConfirmYes')}
                </Button>
                <Button size="sm" variant="secondary" disabled={applyBusy} onClick={() => setConfirmApply(false)}>
                  {t('jobCatalog.appliedConfirmNo')}
                </Button>
              </div>
            </div>
          ) : null}

          <div className="mt-8 grid min-w-0 gap-6 lg:grid-cols-[minmax(0,65%)_minmax(16rem,35%)] lg:items-start">
            <div className="min-w-0">
              {job.descriptionText?.trim() ? (
                <JobDescriptionRenderer
                  text={job.descriptionText}
                  fallbackTitle={t('jobCatalog.jobDescription')}
                />
              ) : null}
            </div>

            <aside className="hidden min-w-0 lg:block">
              <Card className="lg:sticky lg:top-24">
                <div className="space-y-3">
                  {applyButton(primaryApplyClass)}
                  {saveButton()}
                  {appliedButton('w-full')}
                  <dl className="space-y-2 text-sm">
                    {metaRow(t('jobCatalog.saveStatus'), job.saved ? t('jobCatalog.savedBadge') : t('jobCatalog.notSaved'))}
                    {metaRow(t('jobCatalog.appliedStatus'), job.applied ? t('jobCatalog.appliedBadge') : t('jobCatalog.notApplied'))}
                    {locationLine ? metaRow(t('jobCatalog.location'), locationLine) : null}
                    {showRemote ? metaRow(t('jobCatalog.remoteType'), t(`jobCatalog.remote.${job.remoteType}`)) : null}
                    {showEmployment ? metaRow(t('jobCatalog.employmentType'), t(`jobCatalog.employment.${job.employmentType}`)) : null}
                    {job.postedAt ? metaRow(t('jobCatalog.postedAt'), job.postedAt.slice(0, 10)) : null}
                    {job.source ? metaRow(t('jobCatalog.source'), job.source) : null}
                  </dl>
                  {tailorLinks()}
                </div>
              </Card>
            </aside>
          </div>
        </>
      ) : null}

      {job && !loading ? (
        <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-card/95 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur lg:hidden">
          <div className="mx-auto flex w-full max-w-[var(--layout-max)] gap-2">
            <div className="flex-1">{saveButton()}</div>
            <div className="flex-1">{applyButton(primaryApplyClass)}</div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
