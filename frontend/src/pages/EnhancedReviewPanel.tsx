import { useTranslation } from 'react-i18next'
import type { V3EntryReview, V3OpportunityMap } from '../api/types'
import { suggestionTypeLabelKey } from '../tailor/enhancedConfirm'
import { cleanDisplayItems } from '../tailor/opportunityCopy'
import type { EnhancedAnswerDraft } from '../tailor/enhancedAnswers'

export function EnhancedReviewPanel({
  reviews,
  selectedEntryKeys,
  suggestionText,
  aiOriginal,
  editingKey,
  draft,
  confirming,
  opportunity,
  answers,
  onToggle,
  onStartEdit,
  onChangeDraft,
  onSaveEdit,
  onCancelEdit,
  onRestore,
}: {
  reviews: V3EntryReview[]
  selectedEntryKeys: string[]
  suggestionText: Record<string, string>
  aiOriginal: Record<string, string>
  editingKey: string | null
  draft: string
  confirming: boolean
  opportunity: V3OpportunityMap | null
  answers: Record<string, EnhancedAnswerDraft>
  onToggle: (entryKey: string, checked: boolean) => void
  onStartEdit: (entryKey: string, text: string) => void
  onChangeDraft: (value: string) => void
  onSaveEdit: (entryKey: string) => void
  onCancelEdit: () => void
  onRestore: (entryKey: string) => void
}) {
  const { t, i18n } = useTranslation()
  const selected = new Set(selectedEntryKeys)

  return (
    <section className="mt-6 space-y-4">
      {reviews.map((review) => {
        const accepted = selected.has(review.canonicalEntryKey)
        const proposedText = proposedJoined(review, suggestionText)
        const originalText = originalJoined(review)
        const edited = proposedText !== proposedJoined(review, aiOriginal)
        const editing = editingKey === review.canonicalEntryKey
        const supplemented = cleanDisplayItems(supplementedFacts(review, opportunity, answers))
        const jd = opportunity?.entries.find((entry) => entry.canonicalEntryKey === review.canonicalEntryKey)
        const whyBetter = i18n.language.startsWith('zh')
          ? (review.whyBetterZh || review.whyBetter)
          : review.whyBetter
        return (
          <article key={review.canonicalEntryKey} className="rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex flex-wrap items-center gap-3">
              <h3 className="text-base font-medium text-slate-900">{review.entryTitle}</h3>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                {t(suggestionTypeLabelKey(review.suggestionType))}
              </span>
            </div>
            <label className="mt-3 flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={accepted}
                disabled={confirming}
                onChange={(event) => onToggle(review.canonicalEntryKey, event.target.checked)}
              />
              {accepted ? t('tailor.acceptChange') : t('tailor.declineChange')}
            </label>
            {whyBetter ? (
              <p className="mt-3 text-sm text-slate-700">
                <span className="font-medium">{t('tailor.whyBetter')}: </span>
                {whyBetter}
              </p>
            ) : null}
            {supplemented.length > 0 ? (
              <div className="mt-3">
                <p className="text-xs font-medium text-slate-500">{t('tailor.enhanced.usedYourInfo')}</p>
                <ul className="mt-1 list-disc space-y-1 pl-5 text-sm text-slate-700">
                  {supplemented.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </div>
            ) : null}
            <div className="mt-4 grid gap-3 md:grid-cols-2">
              <div>
                <p className="mb-1 text-xs text-slate-500">{t('tailor.original')}</p>
                <BulletBlock text={originalText} />
              </div>
              <div>
                <p className="mb-1 text-xs text-slate-500">{t('tailor.enhanced.enhancedVersion')}</p>
                {editing ? (
                  <label className="block text-sm">
                    <span className="sr-only">{t('tailor.editSuggestion')}</span>
                    <textarea
                      className="min-h-32 w-full rounded-md border border-slate-300 p-3 text-sm"
                      value={draft}
                      disabled={confirming}
                      onChange={(event) => onChangeDraft(event.target.value)}
                    />
                  </label>
                ) : (
                  <BulletBlock text={proposedText} />
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
                    onClick={() => onSaveEdit(review.canonicalEntryKey)}>
                    {t('tailor.saveEdit')}
                  </button>
                  <button type="button" disabled={confirming}
                    className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                    onClick={onCancelEdit}>
                    {t('tailor.cancelEdit')}
                  </button>
                </>
              ) : (
                <button type="button" disabled={confirming}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                  onClick={() => onStartEdit(review.canonicalEntryKey, proposedText)}>
                  {t('tailor.editSuggestion')}
                </button>
              )}
              {edited ? (
                <button type="button" disabled={confirming}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                  onClick={() => onRestore(review.canonicalEntryKey)}>
                  {t('tailor.restoreAi')}
                </button>
              ) : null}
            </div>
            <details className="mt-3">
              <summary className="cursor-pointer text-xs text-slate-500">{t('tailor.technicalDetails')}</summary>
              <ul className="mt-2 space-y-1 text-xs text-slate-600">
                <li>{t('tailor.enhanced.fromResume')}</li>
                {(review.usedEvidenceItemIds?.length ?? 0) > 0 || supplemented.length > 0 ? (
                  <li>{t('tailor.enhanced.fromYourConfirmation')}</li>
                ) : null}
                {jd && (jd.coveredRequirements.length > 0 || jd.partiallyCoveredRequirements.length > 0) ? (
                  <li>
                    {t('tailor.enhanced.matchedRequirements')}:{' '}
                    {[...jd.coveredRequirements, ...jd.partiallyCoveredRequirements].join('；')}
                  </li>
                ) : null}
              </ul>
            </details>
          </article>
        )
      })}
    </section>
  )
}

function originalJoined(review: V3EntryReview): string {
  return (review.originalNodes ?? []).map((node) => node.text ?? '').join('\n')
}

function proposedJoined(review: V3EntryReview, texts: Record<string, string>): string {
  return (review.proposedNodes ?? [])
    .map((node) => texts[node.nodeId] ?? node.text ?? '')
    .join('\n')
}

function supplementedFacts(
  review: V3EntryReview,
  opportunity: V3OpportunityMap | null,
  answers: Record<string, EnhancedAnswerDraft>,
): string[] {
  const items: string[] = []
  for (const question of opportunity?.questions ?? []) {
    if (question.canonicalEntryKey !== review.canonicalEntryKey) {
      continue
    }
    const draft = answers[question.id]
    if (draft?.intent === 'used' && draft.text.trim()) {
      items.push(draft.text.trim())
    }
  }
  return items
}

function BulletBlock({ text }: { text: string }) {
  return <pre className="whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-sm">{text}</pre>
}
