import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getJobApplicationStats } from '../api/jobs'
import type { JobApplicationStatsResponse } from '../api/types'
import { Alert } from './Alert'
import { EmptyState } from './EmptyState'
import { LoadingSkeleton } from './ui/LoadingSkeleton'
import { StatCard } from './ui/StatCard'
import { cx } from './ui/cx'

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
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setError(false)
    setLoading(true)
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
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [days, timeZone])

  const maxDaily = Math.max(1, ...(stats?.daily.map((day) => day.count) ?? [1]))
  const hasDailyData = Boolean(stats && stats.daily.some((day) => day.count > 0))

  return (
    <section className="mt-4 min-w-0 rounded-lg border border-line bg-card p-4 shadow-card">
      <div className="grid min-w-0 grid-cols-2 gap-2 sm:grid-cols-4">
        <StatCard label={t('jobs.statsToday')} value={stats?.todayCount ?? '—'} />
        <StatCard label={t('jobs.statsLast7')} value={stats?.last7DaysCount ?? '—'} />
        <StatCard label={t('jobs.statsLast30')} value={stats?.last30DaysCount ?? '—'} />
        <StatCard label={t('jobs.statsTotal')} value={stats?.totalApplied ?? '—'} />
      </div>
      <div className="mt-3 flex min-w-0 flex-wrap items-center gap-2 text-sm text-muted">
        <span>{t('jobs.statsRange')}</span>
        {RANGES.map((range) => (
          <button
            key={range}
            type="button"
            className={cx(
              'rounded-md border px-2 py-1',
              days === range ? 'border-brand bg-brand text-white' : 'border-line',
            )}
            onClick={() => setDays(range)}
          >
            {t('jobs.statsDays', { count: range })}
          </button>
        ))}
      </div>
      {error ? <div className="mt-3"><Alert message={t('jobs.statsLoadFailed')} /></div> : null}
      {loading && !stats ? <LoadingSkeleton className="mt-3" rows={2} label={t('common.loading')} /> : null}
      {stats && !hasDailyData ? (
        <div className="mt-3"><EmptyState title={t('jobs.statsEmpty')} /></div>
      ) : null}
      {stats && hasDailyData ? (
        <div className="mt-3 min-w-0">
          <p className="text-sm font-medium text-ink">{t('jobs.statsDaily')}</p>
          <svg
            className="mt-2 h-24 w-full"
            viewBox={`0 0 ${Math.max(stats.daily.length, 1) * 8} 40`}
            role="img"
            aria-label={t('jobs.statsDaily')}
            preserveAspectRatio="none"
          >
            {stats.daily.map((day, index) => {
              const height = Math.max((day.count / maxDaily) * 36, day.count === 0 ? 1 : 2)
              return (
                <rect
                  key={day.date}
                  x={index * 8 + 1}
                  y={40 - height}
                  width="6"
                  height={height}
                  rx="1"
                  className="fill-accent"
                >
                  <title>{`${day.date}: ${day.count}`}</title>
                </rect>
              )
            })}
          </svg>
          <ul className="mt-2 max-h-32 overflow-y-auto text-sm text-muted">
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
