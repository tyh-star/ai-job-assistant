import { useTranslation } from 'react-i18next'
import type { V3SkillReview } from '../api/types'

export function EnhancedSkillsReviewPanel({
  reviews,
  selectedIds,
  suggestionText,
  aiOriginal,
  editingId,
  draft,
  confirming,
  onToggle,
  onStartEdit,
  onChangeDraft,
  onSaveEdit,
  onCancelEdit,
  onRestore,
}: {
  reviews: V3SkillReview[]
  selectedIds: string[]
  suggestionText: Record<string, string>
  aiOriginal: Record<string, string>
  editingId: string | null
  draft: string
  confirming: boolean
  onToggle: (skillReviewId: string, checked: boolean) => void
  onStartEdit: (skillReviewId: string, text: string) => void
  onChangeDraft: (value: string) => void
  onSaveEdit: (skillReviewId: string) => void
  onCancelEdit: () => void
  onRestore: (skillReviewId: string) => void
}) {
  const { t } = useTranslation()
  const selected = new Set(selectedIds)
  if (reviews.length === 0) {
    return null
  }

  return (
    <section className="space-y-4">
      {reviews.map((review) => {
        const accepted = selected.has(review.skillReviewId)
        const proposed = suggestionText[review.skillReviewId] ?? review.proposedSkills
        const originalAi = aiOriginal[review.skillReviewId] ?? review.proposedSkills
        const edited = proposed !== originalAi
        const editing = editingId === review.skillReviewId
        return (
          <article key={review.skillReviewId} className="rounded-xl border border-slate-200 bg-white p-5">
            <h3 className="text-base font-medium text-slate-900">{t('tailor.enhanced.skillsUpdate')}</h3>
            <label className="mt-3 flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={accepted}
                disabled={confirming}
                onChange={(event) => onToggle(review.skillReviewId, event.target.checked)}
              />
              {accepted ? t('tailor.acceptChange') : t('tailor.declineChange')}
            </label>
            {review.whyBetter ? (
              <p className="mt-3 text-sm text-slate-700">
                <span className="font-medium">{t('tailor.whyBetter')}: </span>
                {review.whyBetter}
              </p>
            ) : null}
            {review.addedSkills && review.addedSkills.length > 0 ? (
              <p className="mt-2 text-sm text-slate-700">
                {t('tailor.enhanced.addedSkills', { names: review.addedSkills.join(', ') })}
              </p>
            ) : null}
            <div className="mt-4 grid gap-3 md:grid-cols-2">
              <div>
                <p className="mb-1 text-xs text-slate-500">{t('tailor.enhanced.originalSkills')}</p>
                <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{review.originalSkills}</p>
              </div>
              <div>
                <p className="mb-1 text-xs text-slate-500">{t('tailor.enhanced.proposedSkills')}</p>
                {editing ? (
                  <label className="block text-sm">
                    <span className="sr-only">{t('tailor.editSuggestion')}</span>
                    <textarea
                    className="min-h-24 w-full rounded-md border border-slate-300 p-3 text-sm"
                    value={draft}
                    disabled={confirming}
                    onChange={(event) => onChangeDraft(event.target.value)}
                    />
                  </label>
                ) : (
                  <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{proposed}</p>
                )}
                {edited && !editing ? (
                  <p className="mt-2 text-xs font-medium text-slate-700">{t('tailor.editedByYou')}</p>
                ) : null}
              </div>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {editing ? (
                <>
                  <button type="button" disabled={confirming}
                    className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white disabled:opacity-60"
                    onClick={() => onSaveEdit(review.skillReviewId)}>
                    {t('tailor.saveEdit')}
                  </button>
                  <button type="button" disabled={confirming}
                    className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                    onClick={onCancelEdit}>
                      {t('tailor.cancelEdit')}
                  </button>
                </>
              ) : (
                <>
                  <button type="button" disabled={confirming}
                    className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                    onClick={() => onStartEdit(review.skillReviewId, proposed)}>
                    {t('tailor.editSuggestion')}
                  </button>
                  {edited ? (
                    <button type="button" disabled={confirming}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                      onClick={() => onRestore(review.skillReviewId)}>
                      {t('tailor.restoreAi')}
                    </button>
                  ) : null}
                </>
              )}
            </div>
          </article>
        )
      })}
    </section>
  )
}
