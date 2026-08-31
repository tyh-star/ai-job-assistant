import { useTranslation } from 'react-i18next'
import type { V3OpportunityMap } from '../api/types'
import {
  allowedEntryKeys,
  confirmedUsedNeedsText,
  type EnhancedAnswerDraft,
  selectedEntryKey,
  visibleQuestions,
} from '../tailor/enhancedAnswers'
import { relatedRequirementForQuestion } from '../tailor/opportunityCopy'

export function EnhancedQuestionsPanel({
  map,
  answers,
  questionIndex,
  submitting,
  failedQuestionId,
  onAnswerChange,
  onIndexChange,
  onGenerate,
}: {
  map: V3OpportunityMap
  answers: Record<string, EnhancedAnswerDraft>
  questionIndex: number
  submitting: boolean
  failedQuestionId: string | null
  onAnswerChange: (questionId: string, draft: EnhancedAnswerDraft) => void
  onIndexChange: (index: number) => void
  onGenerate: () => void
}) {
  const { t, i18n } = useTranslation()
  const questions = visibleQuestions(map.questions)
  const current = questions[questionIndex]
  if (!current) {
    return (
      <section className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
        <h2 className="text-lg font-medium">{t('tailor.enhanced.questionsTitle')}</h2>
        <p className="mt-2 text-sm text-slate-600">{t('tailor.enhanced.noQuestions')}</p>
        {submitting ? (
          <p className="mt-2 text-sm text-slate-600" aria-live="polite">
            {t('tailor.enhanced.answersSubmitted')}
          </p>
        ) : null}
        <div className="mt-4">
          <GenerateButton submitting={submitting} onGenerate={onGenerate} />
        </div>
      </section>
    )
  }
  const chinese = i18n.language.toLowerCase().startsWith('zh')
  const draft = answers[current.id] ?? { intent: null, text: '' }
  const entryKey = selectedEntryKey(current, draft)
  const entry = map.entries.find((item) => item.canonicalEntryKey === entryKey)
    ?? map.entries.find((item) => item.canonicalEntryKey === current.canonicalEntryKey)
  const related = relatedRequirementForQuestion(entry, current)
  const needsText = confirmedUsedNeedsText(current, draft)
  const prompt = chinese ? current.promptZh : current.promptEn
  const lastQuestion = questionIndex >= questions.length - 1
  const choices = allowedEntryKeys(current)
  const entryOptions = choices
    .map((key) => map.entries.find((item) => item.canonicalEntryKey === key))
    .filter((item): item is NonNullable<typeof item> => Boolean(item))

  return (
    <section className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium">{t('tailor.enhanced.questionsTitle')}</h2>
      <p className="mt-2 text-sm text-slate-600" aria-live="polite">
        {t('tailor.enhanced.questionProgress', { current: questionIndex + 1, total: questions.length })}
      </p>
      <p className="mt-2 text-sm text-slate-600" aria-live="polite">
        {submitting ? t('tailor.enhanced.answersSubmitted') : t('tailor.enhanced.answersStaged')}
      </p>
      <article className="mt-4 rounded-lg border border-slate-200 p-4">
        <h3 className="text-sm font-medium text-slate-900">{entry?.entryTitle || current.entryTitle}</h3>
        <p className="mt-2 text-sm text-slate-800">{prompt}</p>
        {related ? (
          <p className="mt-2 text-xs text-slate-600">
            {t('tailor.enhanced.whyAsking')}: {related}
          </p>
        ) : null}
        {entryOptions.length > 1 ? (
          <label className="mt-4 block text-sm">
            <span>{t('tailor.enhanced.chooseEntry')}</span>
            <select
              className="mt-1 w-full rounded-md border border-slate-300 p-2 text-sm"
              value={entryKey}
              disabled={submitting}
              onChange={(event) => onAnswerChange(current.id, {
                ...draft,
                selectedEntryKey: event.target.value,
              })}
            >
              {entryOptions.map((option) => (
                <option key={option.canonicalEntryKey} value={option.canonicalEntryKey}>
                  {option.entryTitle}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <fieldset className="mt-4" disabled={submitting}>
          <legend className="sr-only">{t('tailor.enhanced.answerLegend')}</legend>
          <div className="flex flex-wrap gap-2">
            <ChoiceButton
              selected={draft.intent === 'used'}
              label={t('tailor.yesIDid')}
              onClick={() => onAnswerChange(current.id, { ...draft, intent: 'used' })}
            />
            <ChoiceButton
              selected={draft.intent === 'not_used'}
              label={t('tailor.notUsed')}
              onClick={() => onAnswerChange(current.id, { ...draft, intent: 'not_used', text: '' })}
            />
            <ChoiceButton
              selected={draft.intent === 'skipped'}
              label={t('tailor.skipForNow')}
              onClick={() => onAnswerChange(current.id, { ...draft, intent: 'skipped', text: '' })}
            />
          </div>
        </fieldset>
        {draft.intent === 'used' ? (
          <label className="mt-4 block text-sm">
            <span>{t('tailor.enhanced.specificDetails')}</span>
            <textarea
              className="mt-1 min-h-24 w-full rounded-md border border-slate-300 p-3 text-sm"
              value={draft.text}
              disabled={submitting}
              onChange={(event) => onAnswerChange(current.id, { ...draft, intent: 'used', text: event.target.value })}
            />
          </label>
        ) : null}
        {needsText ? (
          <p role="alert" className="mt-2 text-sm text-red-700">{t('tailor.needRealDetails')}</p>
        ) : null}
        {failedQuestionId === current.id ? (
          <p role="alert" className="mt-2 text-sm text-red-700">{t('tailor.enhanced.answerFailed')}</p>
        ) : null}
      </article>
      <div className="mt-4 flex flex-wrap gap-2">
        <button
          type="button"
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm disabled:opacity-60"
          disabled={questionIndex === 0 || submitting}
          onClick={() => onIndexChange(questionIndex - 1)}
        >
          {t('tailor.enhanced.previousQuestion')}
        </button>
        {lastQuestion ? (
          <GenerateButton submitting={submitting} onGenerate={onGenerate} />
        ) : (
          <button
            type="button"
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm disabled:opacity-60"
            disabled={submitting}
            onClick={() => onIndexChange(questionIndex + 1)}
          >
            {t('tailor.enhanced.nextQuestion')}
          </button>
        )}
      </div>
    </section>
  )
}

function GenerateButton({
  submitting,
  onGenerate,
}: {
  submitting: boolean
  onGenerate: () => void
}) {
  const { t } = useTranslation()
  return (
    <button
      type="button"
      className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
      disabled={submitting}
      aria-busy={submitting}
      onClick={onGenerate}
    >
      {submitting ? t('tailor.enhanced.generatingShort') : t('tailor.enhanced.generateProposal')}
    </button>
  )
}

function ChoiceButton({
  selected,
  label,
  onClick,
}: {
  selected: boolean
  label: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={`rounded-lg border px-3 py-1.5 text-sm ${
        selected ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-300 bg-white text-slate-800'
      }`}
      onClick={onClick}
    >
      {label}
    </button>
  )
}
