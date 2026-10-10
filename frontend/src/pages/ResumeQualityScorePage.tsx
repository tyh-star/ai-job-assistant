import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { getResumeQualityScore } from '../api/resumes'
import type { ResumeQualityScoreResponse } from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'

const SECTION_CLASS = 'rounded-lg border border-line bg-card p-6 shadow-card'

const LEVEL_BADGE_CLASS: Record<string, string> = {
  EXCELLENT: 'bg-success-soft text-success',
  GOOD: 'bg-accent-soft text-brand',
  NEEDS_WORK: 'bg-warning-soft text-warning',
  WEAK: 'bg-danger-soft text-danger',
}

const CATEGORY_BAR_CLASS = 'bg-accent'

export function ResumeQualityScorePage() {
  const { t } = useTranslation()
  const params = useParams()
  const resumeId = Number(params.id)
  const [result, setResult] = useState<ResumeQualityScoreResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      const { data } = await getResumeQualityScore(resumeId)
      setResult(data)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!Number.isFinite(resumeId)) {
      return
    }
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumeId])

  if (!Number.isFinite(resumeId)) {
    return <Alert message={t('errors.notFound')} />
  }

  return (
    <div className="max-w-2xl">
      <Link className="text-sm text-slate-600 underline" to="/resumes">
        {t('resumes.backToList')}
      </Link>
      <h1 className="page-title mt-3">{t('resumeQuality.title')}</h1>

      {loading ? <div className="mt-6"><Spinner label={t('resumeQuality.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} onRetry={() => void load()} /></div> : null}

      {!loading && !error && result ? (
        result.scorable ? (
          <ScoredView result={result} />
        ) : (
          <UnscorableView result={result} />
        )
      ) : null}
    </div>
  )
}

function ScoredView({ result }: { result: ResumeQualityScoreResponse }) {
  const { t } = useTranslation()
  const score = result.score ?? 0
  const level = result.level ?? ''
  const topSuggestions = result.suggestions.slice(0, 3)

  return (
    <div className="mt-6 space-y-6">
      <section className={SECTION_CLASS}>
        <div className="flex flex-wrap items-center gap-5">
          <ScoreRing score={score} label={t('resumeQuality.scoreOutOf100', { score })} />
          <span
            className={`rounded-full px-3 py-1 text-sm font-medium ${LEVEL_BADGE_CLASS[level] ?? 'bg-slate-100 text-ink'}`}
          >
            {t(`resumeQuality.level.${level}`, level)}
          </span>
        </div>
        <p className="mt-1 text-xs text-slate-400">
          {t('resumeQuality.rubricVersion', { version: result.rubricVersion.replace(/^v/i, '') })}
        </p>

        {/* Two explicit, required disclaimers -- never framed as hiring probability or job match, and never implied to cost AI quota. */}
        <p className="mt-4 text-sm text-slate-600">{t('resumeQuality.disclaimerNotProbability')}</p>
        <p className="mt-1 text-sm text-slate-500">{t('resumeQuality.disclaimerNoAiQuota')}</p>
      </section>

      <section className={SECTION_CLASS}>
        <h2 className="text-lg font-medium text-slate-900">{t('resumeQuality.categoriesTitle')}</h2>
        <div className="mt-4 space-y-4">
          {result.categories.map((category) => (
            <div key={category.code}>
              <div className="flex items-center justify-between text-sm">
                <span className="text-slate-700">{t(`resumeQuality.category.${category.code}`, category.code)}</span>
                <span className="text-slate-500">{category.score} / {category.maxScore}</span>
              </div>
              <div
                className="mt-1 h-2 w-full overflow-hidden rounded-full bg-slate-100"
                role="progressbar"
                aria-valuenow={category.score}
                aria-valuemin={0}
                aria-valuemax={category.maxScore}
                aria-label={t(`resumeQuality.category.${category.code}`, category.code)}
              >
                <div
                  className={`h-full rounded-full ${CATEGORY_BAR_CLASS}`}
                  style={{ width: `${Math.min(100, (category.score / category.maxScore) * 100)}%` }}
                />
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className={SECTION_CLASS}>
        <h2 className="text-lg font-semibold text-ink">{t('resumeQuality.strengthsTitle')}</h2>
        {result.strengths.length > 0 ? (
          <ul className="mt-3 list-inside list-disc space-y-1 text-sm text-success">
            {result.strengths.map((code) => (
              <li key={code}>{t(`resumeQuality.strength.${code}`, code)}</li>
            ))}
          </ul>
        ) : (
          <p className="mt-3 text-sm text-muted">{t('resumeQuality.noStrengths')}</p>
        )}
      </section>

      <section className={SECTION_CLASS}>
        <h2 className="text-lg font-semibold text-ink">{t('resumeQuality.suggestionsTitle')}</h2>
        {topSuggestions.length > 0 ? (
          <ol className="mt-3 list-inside list-decimal space-y-1 text-sm text-warning">
            {topSuggestions.map((code) => (
              <li key={code}>{t(`resumeQuality.suggestion.${code}`, code)}</li>
            ))}
          </ol>
        ) : (
          <p className="mt-3 text-sm text-muted">{t('resumeQuality.noSuggestions')}</p>
        )}
      </section>
    </div>
  )
}

function ScoreRing({ score, label }: { score: number; label: string }) {
  const radius = 36
  const circumference = 2 * Math.PI * radius
  const offset = circumference * (1 - Math.min(100, Math.max(0, score)) / 100)
  return (
    <div className="relative h-24 w-24 shrink-0">
      <svg className="h-24 w-24 -rotate-90" viewBox="0 0 88 88" aria-hidden="true">
        <circle cx="44" cy="44" r={radius} fill="none" className="stroke-slate-200" strokeWidth="8" />
        <circle
          cx="44"
          cy="44"
          r={radius}
          fill="none"
          className="stroke-accent"
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
        />
      </svg>
      <p className="absolute inset-0 flex items-center justify-center text-sm font-semibold text-ink">{label}</p>
    </div>
  )
}

function UnscorableView({ result }: { result: ResumeQualityScoreResponse }) {
  const { t } = useTranslation()
  return (
    <section className={`${SECTION_CLASS} mt-6`}>
      <h2 className="text-lg font-medium text-slate-900">{t('resumeQuality.unscorableTitle')}</h2>
      <p className="mt-2 text-sm text-slate-600">
        {t(`resumeQuality.reason.${result.reasonCode}`, result.reasonCode ?? '')}
      </p>
    </section>
  )
}
