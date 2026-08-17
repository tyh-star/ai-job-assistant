import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { listJobs } from '../api/jobs'
import { listResumes } from '../api/resumes'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'

export function DashboardPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const [resumeCount, setResumeCount] = useState(0)
  const [jobCount, setJobCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      const [resumes, jobs] = await Promise.all([listResumes(), listJobs()])
      setResumeCount(resumes.data.filter((resume) => resume.origin !== 'TEMPLATE').length)
      setJobCount(jobs.data.length)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div>
      <h1 className="text-2xl font-semibold text-slate-900">{t('dashboard.title')}</h1>
      <p className="mt-1 text-slate-600">{t('dashboard.welcome', { name: user?.name ?? '' })}</p>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-6"><Alert message={error} onRetry={() => void load()} /></div> : null}
      {!loading && !error ? (
        <>
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            <StatCard label={t('dashboard.resumeCount')} value={resumeCount} />
            <StatCard label={t('dashboard.jobCount')} value={jobCount} />
          </div>
          <h2 className="mt-8 text-lg font-medium text-slate-900">{t('dashboard.quickActions')}</h2>
          <div className="mt-3 flex flex-wrap gap-3">
            <Link className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white" to="/resumes">
              {t('dashboard.goResumes')}
            </Link>
            <Link className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm" to="/jobs">
              {t('dashboard.goJobs')}
            </Link>
            <Link className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm" to="/tailor">
              {t('dashboard.goTailor')}
            </Link>
          </div>
        </>
      ) : null}
    </div>
  )
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-6">
      <p className="text-sm text-slate-500">{label}</p>
      <p className="mt-2 text-3xl font-semibold text-slate-900">{value}</p>
    </div>
  )
}
