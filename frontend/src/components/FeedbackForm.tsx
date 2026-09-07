import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { deleteMyFeedback, getFeedbackSummary, getMyFeedback, saveMyFeedback } from '../api/feedback'
import type { FeedbackSummaryResponse } from '../api/types'
import { Alert } from './Alert'
import { Spinner } from './Spinner'

const SECTION_CLASS = 'rounded-xl border border-slate-200 bg-white p-6 min-w-0'
const SUCCESS_CLASS = 'rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900'
const SUBMIT_CLASS = 'min-h-11 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60'
const COMMENT_MAX_LENGTH = 1000
const STARS = [1, 2, 3, 4, 5] as const

function summaryLabel(
  t: (key: string, options?: Record<string, unknown>) => string,
  summary: FeedbackSummaryResponse | null,
) {
  if (!summary || summary.reviewerCount <= 0) {
    return t('feedback.summaryEmpty')
  }
  return t('feedback.summaryText', {
    count: summary.reviewerCount,
    average: summary.averageRating.toFixed(1),
  })
}

/** Site rating card -- only mounted on the signed-in Profile page. */
export function FeedbackForm() {
  const { t } = useTranslation()
  const { hash } = useLocation()

  const [rating, setRating] = useState(0)
  const [hoveredStar, setHoveredStar] = useState(0)
  const [comment, setComment] = useState('')
  const [hasExistingFeedback, setHasExistingFeedback] = useState(false)

  const [summary, setSummary] = useState<FeedbackSummaryResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [successMessage, setSuccessMessage] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      try {
        const { data } = await getFeedbackSummary()
        setSummary(data)
      } catch (err) {
        setError(t(apiErrorKey(err)))
      }

      try {
        const { data } = await getMyFeedback()
        setRating(data.rating)
        setComment(data.comment ?? '')
        setHasExistingFeedback(true)
      } catch (err) {
        if (apiErrorKey(err) === 'errors.notFound') {
          setHasExistingFeedback(false)
        } else {
          setError(t(apiErrorKey(err)))
        }
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (hash !== '#feedback' || loading) {
      return
    }
    document.getElementById('feedback')?.scrollIntoView({ block: 'start' })
  }, [hash, loading])

  function onStarKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'ArrowRight' || event.key === 'ArrowUp') {
      event.preventDefault()
      setRating((current) => Math.min(5, (current || 0) + 1))
    } else if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') {
      event.preventDefault()
      setRating((current) => Math.max(1, current === 0 ? 1 : current - 1))
    } else if (event.key === 'Home') {
      event.preventDefault()
      setRating(1)
    } else if (event.key === 'End') {
      event.preventDefault()
      setRating(5)
    }
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) {
      return
    }
    if (rating < 1 || rating > 5) {
      setError(t('feedback.ratingRequired'))
      return
    }
    if (comment.length > COMMENT_MAX_LENGTH) {
      setError(t('feedback.commentTooLong'))
      return
    }
    setSaving(true)
    setError('')
    setSuccessMessage('')
    const wasUpdate = hasExistingFeedback
    try {
      const trimmedComment = comment.trim()
      const { data } = await saveMyFeedback({ rating, comment: trimmedComment || null })
      setRating(data.rating)
      setComment(data.comment ?? '')
      setHasExistingFeedback(true)
      setSuccessMessage(wasUpdate ? t('feedback.updated') : t('feedback.thankYou'))
      const { data: freshSummary } = await getFeedbackSummary()
      setSummary(freshSummary)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  async function onDelete() {
    if (saving) {
      return
    }
    if (!window.confirm(t('feedback.deleteConfirm'))) {
      return
    }
    setSaving(true)
    setError('')
    setSuccessMessage('')
    try {
      await deleteMyFeedback()
      setRating(0)
      setComment('')
      setHasExistingFeedback(false)
      setSuccessMessage(t('feedback.deleted'))
      const { data: freshSummary } = await getFeedbackSummary()
      setSummary(freshSummary)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  const displayedStars = hoveredStar || rating
  const remainingChars = COMMENT_MAX_LENGTH - comment.length

  return (
    <section id="feedback" className={SECTION_CLASS}>
      <h2 className="text-lg font-medium text-slate-900">{t('feedback.title')}</h2>

      {loading ? <div className="mt-4"><Spinner label={t('feedback.loading')} /></div> : (
        <div className="mt-4 space-y-4">
          <p className="break-words text-sm text-slate-600">{summaryLabel(t, summary)}</p>

          <form className="space-y-4" onSubmit={onSubmit}>
            {error ? <Alert message={error} /> : null}
            {successMessage ? <div className={SUCCESS_CLASS}>{successMessage}</div> : null}

            <div>
              <span className="block text-sm text-slate-700" id="feedback-rating-label">
                {t('feedback.ratingLabel')}
              </span>
              <div
                role="radiogroup"
                aria-labelledby="feedback-rating-label"
                className="mt-2 flex flex-wrap gap-1"
                onMouseLeave={() => setHoveredStar(0)}
              >
                {STARS.map((star) => (
                  <button
                    key={star}
                    type="button"
                    role="radio"
                    aria-label={t('feedback.starAriaLabel', { count: star })}
                    aria-checked={rating === star}
                    tabIndex={rating === star || (rating === 0 && star === 1) ? 0 : -1}
                    className={`min-h-11 min-w-11 text-3xl leading-none ${
                      rating === star ? 'rounded-lg ring-2 ring-amber-500 ring-offset-1' : ''
                    }`}
                    onMouseEnter={() => setHoveredStar(star)}
                    onClick={() => setRating(star)}
                    onKeyDown={onStarKeyDown}
                  >
                    <span aria-hidden="true" className={star <= displayedStars ? 'text-amber-500' : 'text-slate-300'}>
                      ★
                    </span>
                  </button>
                ))}
              </div>
            </div>

            <label className="block min-w-0 text-sm">
              <span className="text-slate-700">{t('feedback.commentLabel')}</span>
              <textarea
                className="mt-1 w-full min-w-0 break-words rounded-lg border border-slate-300 px-3 py-2 text-sm"
                rows={4}
                maxLength={COMMENT_MAX_LENGTH}
                placeholder={t('feedback.commentPlaceholder')}
                value={comment}
                onChange={(event) => setComment(event.target.value)}
              />
              <span className="mt-1 block text-xs text-slate-400">
                {comment.length} / {COMMENT_MAX_LENGTH} — {t('feedback.remainingChars', { count: remainingChars })}
              </span>
            </label>

            <div className="flex flex-wrap gap-3">
              <button type="submit" className={SUBMIT_CLASS} disabled={saving}>
                {saving ? t('feedback.submitting') : hasExistingFeedback ? t('feedback.update') : t('feedback.submit')}
              </button>
              {hasExistingFeedback ? (
                <button
                  type="button"
                  className="min-h-11 rounded-lg border border-red-200 px-4 py-2 text-sm text-red-700 disabled:opacity-60"
                  disabled={saving}
                  onClick={() => void onDelete()}
                >
                  {t('feedback.delete')}
                </button>
              ) : null}
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
