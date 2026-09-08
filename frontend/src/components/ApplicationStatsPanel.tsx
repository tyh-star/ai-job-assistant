import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getJobApplicationStats } from '../api/jobs'
import type { JobApplicationStatsResponse } from '../api/types'
import { Alert } from './Alert'

const RANGES = [7, 30, 90] as const

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'America/Toronto'
}

export function ApplicationStatsPanel() {
  const { t } = useTranslation()
  const timeZone = useMemo(() => browserTimeZone(), [])
  const [days, setDays] = useState<7 | 30 | 90>(30)
  const [stats, setStats] = useState<JobApplicationStatsResponse | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    setError(false)
    getJobApplicationStats(days, timeZone)
      .then((response) => {
        if (!cancelled) {
          setStats(response.data)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setStats(null)
          setError(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [days, timeZone])

  const maxDaily = Math.max(1, ...(stats?.daily.map((day) => day.count) ?? [1]))

  return (
    <section className="mt-4 min-w-0 rounded-xl border border-slate-200 bg-white p-4">
      <div className="grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-4">
        <StatCard label={t('jobs.statsToday')} value={stats?.todayCount ?? '—'} />
        <StatCard label={t('jobs.statsLast7')} value={stats?.last7DaysCount ?? '—'} />
        <StatCard label={t('jobs.statsLast30')} value={stats?.last30DaysCount ?? '—'} />
        <StatCard label={t('jobs.statsTotal')} value={stats?.totalApplied ?? '—'} />
      </div>
      <div className="mt-3 flex min-w-0 flex-wrap items-center gap-2 text-xs text-slate-600">
        <span>{t('jobs.statsRange')}</span>
        {RANGES.map((range) => (
          <button
            key={range}
            type="button"
            className={`rounded-lg border px-2 py-1 ${days === range ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-300'}`}
            onClick={() => setDays(range)}
          >
            {t('jobs.statsDays', { count: range })}
          </button>
        ))}
      </div>
      {error ? <div className="mt-3"><Alert message={t('jobs.statsLoadFailed')} /></div> : null}
      {stats ? (
        <div className="mt-3 min-w-0">
          <p className="text-xs font-medium text-slate-700">{t('jobs.statsDaily')}</p>
          <div className="mt-2 flex h-24 min-w-0 items-end gap-px overflow-hidden">
            {stats.daily.map((day) => (
              <div
                key={day.date}
                className="min-w-0 flex-1 rounded-t bg-slate-700"
                style={{ height: `${Math.max(day.count === 0 ? 4 : (day.count / maxDaily) * 100, 4)}%` }}
                title={`${day.date}: ${day.count}`}
              />
            ))}
          </div>
          <ul className="mt-2 max-h-32 overflow-y-auto text-xs text-slate-600">
            {stats.daily.map((day) => (
              <li key={`row-${day.date}`} className="flex justify-between gap-2">
                <span>{day.date}</span>
                <span>{day.count}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  )
}

function StatCard({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="min-w-0 rounded-lg bg-slate-50 px-3 py-2">
      <p className="truncate text-xs text-slate-500">{label}</p>
      <p className="text-lg font-semibold text-slate-900">{value}</p>
    </div>
  )
}
