import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { deleteResumeEvidence, saveResumeEvidence } from '../api/resumes'
import type {
  EvidenceQuestion,
  ResumeEvidenceItem,
  SaveResumeEvidenceRequest,
  TailorAnalysisRequirement,
} from '../api/types'
import { Alert } from '../components/Alert'
import {
  entryKeyAllowed,
  hasSubstantiveEvidence,
  isYesOnlyAnswer,
  sortEntryOptions,
  trimEvidenceField,
  type QuestionUiStatus,
} from './evidenceForm'

export type QuestionIntent = 'used' | 'not_used' | null

export interface QuestionDraft {
  status: QuestionUiStatus
  intent: QuestionIntent
  context: string
  action: string
  result: string
  metric: string
  entryKey: string
  message: string
}

const emptyDraft: QuestionDraft = {
  status: 'idle',
  intent: null,
  context: '',
  action: '',
  result: '',
  metric: '',
  entryKey: '',
  message: '',
}

export function EvidenceQuestionsSection({
  questions,
  requirements,
  resumeId,
  drafts,
  skipped,
  onDraftChange,
  onSkip,
  onRefresh,
  onNotice,
}: {
  questions: EvidenceQuestion[]
  requirements: TailorAnalysisRequirement[]
  resumeId: number
  drafts: Record<string, QuestionDraft>
  skipped: string[]
  onDraftChange: (questionId: string, draft: QuestionDraft) => void
  onSkip: (questionId: string) => void
  onRefresh: () => Promise<void>
  onNotice: (message: string) => void
}) {
  const { t, i18n } = useTranslation()
  const visible = questions.filter((question) => !skipped.includes(question.id))
  if (visible.length === 0) {
    return null
  }
  const chinese = i18n.language.toLowerCase().startsWith('zh')

  return (
    <section className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium">{t('tailor.addEvidence')}</h2>
      <p className="mt-2 text-sm text-slate-600">{t('tailor.addEvidenceHelp')}</p>
      <ul className="mt-4 space-y-4">
        {visible.map((question) => {
          const requirement = requirements.find((item) => item.id === question.requirementId)
          const draft = drafts[question.id] ?? emptyDraft
          return (
            <li key={question.id} className="rounded-lg border border-slate-200 p-4">
              <p className="font-medium">{question.topic || requirement?.requirement}</p>
              <p className="mt-1 text-xs text-slate-500">
                {t('tailor.priorityLabel')}:{' '}
                {t(`tailor.priority.${question.priority}`, { defaultValue: question.priority })}
                {' · '}
                {t('tailor.coverageLabel')}:{' '}
                {requirement
                  ? t(`tailor.status.${requirement.status}`, { defaultValue: requirement.status })
                  : t('tailor.status.MISSING_EVIDENCE')}
              </p>
              <p className="mt-3 text-sm">{chinese ? question.promptZh : question.promptEn}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm disabled:opacity-60"
                  disabled={draft.status === 'saving'}
                  onClick={() => onDraftChange(question.id, {
                    ...draft,
                    status: 'editing',
                    intent: 'used',
                    message: '',
                  })}
                >
                  {t('tailor.yesUsed')}
                </button>
                <button
                  type="button"
                  className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm disabled:opacity-60"
                  disabled={draft.status === 'saving'}
                  onClick={() => onDraftChange(question.id, {
                    ...draft,
                    status: 'editing',
                    intent: 'not_used',
                    message: '',
                  })}
                >
                  {t('tailor.notUsed')}
                </button>
                <button
                  type="button"
                  className="rounded-lg px-3 py-1.5 text-sm text-slate-600 disabled:opacity-60"
                  disabled={draft.status === 'saving'}
                  onClick={() => onSkip(question.id)}
                >
                  {t('tailor.skipForNow')}
                </button>
              </div>
              {draft.intent === 'used' ? (
                <UsedEvidenceForm
                  question={question}
                  requirement={requirement}
                  draft={draft}
                  resumeId={resumeId}
                  onDraftChange={(next) => onDraftChange(question.id, next)}
                  onRefresh={onRefresh}
                  onNotice={onNotice}
                />
              ) : null}
              {draft.intent === 'not_used' ? (
                <NotUsedConfirm
                  question={question}
                  requirement={requirement}
                  draft={draft}
                  resumeId={resumeId}
                  onDraftChange={(next) => onDraftChange(question.id, next)}
                  onRefresh={onRefresh}
                  onNotice={onNotice}
                />
              ) : null}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function UsedEvidenceForm({
  question,
  requirement,
  draft,
  resumeId,
  onDraftChange,
  onRefresh,
  onNotice,
}: {
  question: EvidenceQuestion
  requirement: TailorAnalysisRequirement | undefined
  draft: QuestionDraft
  resumeId: number
  onDraftChange: (draft: QuestionDraft) => void
  onRefresh: () => Promise<void>
  onNotice: (message: string) => void
}) {
  const { t } = useTranslation()
  const education = question.questionType === 'EDUCATION_CONFIRMATION'
  const options = sortEntryOptions(question.entryOptions ?? [])
  const saving = draft.status === 'saving'

  async function onSave() {
    if (saving) {
      return
    }
    const context = trimEvidenceField(draft.context)
    const action = trimEvidenceField(draft.action)
    const result = trimEvidenceField(draft.result)
    const metric = trimEvidenceField(draft.metric)
    if (education) {
      if (!hasSubstantiveEvidence(context, action, result)) {
        onDraftChange({ ...draft, status: 'error', message: t('tailor.needEducationDetails') })
        return
      }
      if (isYesOnlyAnswer([context, action, result])) {
        onDraftChange({ ...draft, status: 'error', message: t('tailor.needRealDetails') })
        return
      }
      onDraftChange({ ...draft, status: 'saving', message: '' })
      try {
        await saveResumeEvidence(resumeId, usedRequest({
          topic: question.topic,
          category: requirement?.category ?? '',
          entryKey: 'GENERAL',
          relatedNodeIds: [],
          context,
          action,
          result,
          metric: '',
        }))
        onDraftChange({ ...draft, status: 'success', message: t('tailor.evidenceSaved') })
        onNotice(`${t('tailor.evidenceSaved')} ${t('tailor.scoreUnchanged')}`)
        await onRefresh()
      } catch (error) {
        onDraftChange({
          ...draft,
          status: 'error',
          message: t(apiErrorKey(error)),
        })
      }
      return
    }
    const entryKey = trimEvidenceField(draft.entryKey)
    if (!hasSubstantiveEvidence(context, action, result)) {
      onDraftChange({ ...draft, status: 'error', message: t('tailor.needContextActionResult') })
      return
    }
    if (isYesOnlyAnswer([context, action, result])) {
      onDraftChange({ ...draft, status: 'error', message: t('tailor.needRealDetails') })
      return
    }
    if (!entryKeyAllowed(entryKey, options)) {
      onDraftChange({ ...draft, status: 'error', message: t('tailor.needValidEntry') })
      return
    }
    const selected = options.find((option) => option.entryKey === entryKey)
    onDraftChange({ ...draft, status: 'saving', message: '' })
    try {
      await saveResumeEvidence(resumeId, usedRequest({
        topic: question.topic,
        category: requirement?.category ?? '',
        entryKey,
        relatedNodeIds: selected?.nodeIds ?? [],
        context,
        action,
        result,
        metric,
      }))
      onDraftChange({ ...draft, status: 'success', message: t('tailor.evidenceSaved') })
      onNotice(`${t('tailor.evidenceSaved')} ${t('tailor.scoreUnchanged')}`)
      await onRefresh()
    } catch (error) {
      onDraftChange({
        ...draft,
        status: 'error',
        message: t(apiErrorKey(error)),
      })
    }
  }

  if (education) {
    return (
      <form
        className="mt-4 space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          void onSave()
        }}
      >
        <Field
          label={t('tailor.school')}
          value={draft.context}
          placeholder={t('tailor.schoolPlaceholder')}
          disabled={saving}
          onChange={(value) => onDraftChange({ ...draft, context: value, message: '' })}
        />
        <Field
          label={t('tailor.degree')}
          value={draft.action}
          placeholder={t('tailor.degreePlaceholder')}
          disabled={saving}
          onChange={(value) => onDraftChange({ ...draft, action: value, message: '' })}
        />
        <Field
          label={t('tailor.dates')}
          value={draft.result}
          placeholder={t('tailor.datesPlaceholder')}
          disabled={saving}
          onChange={(value) => onDraftChange({ ...draft, result: value, message: '' })}
        />
        {draft.message ? <Alert message={draft.message} /> : null}
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
        >
          {saving ? t('tailor.savingEvidence') : t('tailor.saveEvidence')}
        </button>
      </form>
    )
  }

  return (
    <form
      className="mt-4 space-y-3"
      onSubmit={(event) => {
        event.preventDefault()
        void onSave()
      }}
    >
      <Field
        label={t('tailor.context')}
        value={draft.context}
        placeholder={t('tailor.contextPlaceholder')}
        disabled={saving}
        onChange={(value) => onDraftChange({ ...draft, context: value, message: '' })}
      />
      <Field
        label={t('tailor.action')}
        value={draft.action}
        placeholder={t('tailor.actionPlaceholder')}
        disabled={saving}
        onChange={(value) => onDraftChange({ ...draft, action: value, message: '' })}
      />
      <Field
        label={t('tailor.result')}
        value={draft.result}
        placeholder={t('tailor.resultPlaceholder')}
        disabled={saving}
        onChange={(value) => onDraftChange({ ...draft, result: value, message: '' })}
      />
      <Field
        label={t('tailor.metric')}
        value={draft.metric}
        placeholder={t('tailor.metricPlaceholder')}
        disabled={saving}
        onChange={(value) => onDraftChange({ ...draft, metric: value, message: '' })}
      />
      <label className="block text-sm">
        <span>{t('tailor.relatedEntry')}</span>
        <select
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
          value={draft.entryKey}
          disabled={saving}
          onChange={(event) => onDraftChange({ ...draft, entryKey: event.target.value, message: '' })}
        >
          <option value="">{t('tailor.selectEntry')}</option>
          {options.map((option) => (
            <option key={option.entryKey} value={option.entryKey}>
              {option.entryKey === 'GENERAL' ? t('tailor.unlinkedProject') : option.entryLabel}
            </option>
          ))}
        </select>
      </label>
      {draft.message ? <Alert message={draft.message} /> : null}
      <button
        type="submit"
        disabled={saving}
        className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
      >
        {saving ? t('tailor.savingEvidence') : t('tailor.saveEvidence')}
      </button>
    </form>
  )
}

function NotUsedConfirm({
  question,
  requirement,
  draft,
  resumeId,
  onDraftChange,
  onRefresh,
  onNotice,
}: {
  question: EvidenceQuestion
  requirement: TailorAnalysisRequirement | undefined
  draft: QuestionDraft
  resumeId: number
  onDraftChange: (draft: QuestionDraft) => void
  onRefresh: () => Promise<void>
  onNotice: (message: string) => void
}) {
  const { t } = useTranslation()
  const saving = draft.status === 'saving'

  async function onConfirm() {
    if (saving) {
      return
    }
    onDraftChange({ ...draft, status: 'saving', message: '' })
    try {
      await saveResumeEvidence(resumeId, {
        topic: question.topic,
        category: requirement?.category ?? '',
        answerStatus: 'CONFIRMED_NOT_USED',
        entryKey: 'GENERAL',
        relatedNodeIds: [],
        context: '',
        action: '',
        result: '',
        metric: '',
        confirmed: true,
      })
      onDraftChange({ ...draft, status: 'success', message: t('tailor.evidenceSaved') })
      onNotice(`${t('tailor.evidenceSaved')} ${t('tailor.scoreUnchanged')}`)
      await onRefresh()
    } catch (error) {
      onDraftChange({
        ...draft,
        status: 'error',
        message: t(apiErrorKey(error)),
      })
    }
  }

  return (
    <div className="mt-4 space-y-3">
      <p className="text-sm text-slate-700">{t('tailor.notUsedConfirm')}</p>
      {draft.message ? <Alert message={draft.message} /> : null}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
          onClick={() => void onConfirm()}
        >
          {saving ? t('tailor.savingEvidence') : t('common.yes')}
        </button>
        <button
          type="button"
          disabled={saving}
          className="rounded-lg border border-slate-300 px-4 py-2 text-sm disabled:opacity-60"
          onClick={() => onDraftChange({ ...draft, intent: null, status: 'idle', message: '' })}
        >
          {t('common.no')}
        </button>
      </div>
    </div>
  )
}

export function SavedEvidenceSection({
  items,
  resumeId,
  onRefresh,
  onNotice,
}: {
  items: ResumeEvidenceItem[]
  resumeId: number
  onRefresh: () => Promise<void>
  onNotice: (message: string) => void
}) {
  const { t } = useTranslation()
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [error, setError] = useState('')

  async function onDelete(item: ResumeEvidenceItem) {
    if (deletingId !== null) {
      return
    }
    if (!window.confirm(t('tailor.deleteEvidenceConfirm'))) {
      return
    }
    setDeletingId(item.id)
    setError('')
    try {
      await deleteResumeEvidence(resumeId, item.id)
      onNotice(t('tailor.evidenceDeleted'))
      await onRefresh()
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <details className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
      <summary className="cursor-pointer text-lg font-medium">{t('tailor.savedEvidence')}</summary>
      {error ? <div className="mt-3"><Alert message={error} /></div> : null}
      {items.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">{t('tailor.noSavedEvidence')}</p>
      ) : (
        <ul className="mt-4 space-y-4">
          {items.map((item) => (
            <li key={item.id} className="rounded-lg border border-slate-200 p-3 text-sm">
              <p className="font-medium">{item.topic}</p>
              <p className="mt-1 text-xs text-slate-500">
                {item.answerStatus === 'CONFIRMED_USED'
                  ? t('tailor.answerStatus.CONFIRMED_USED')
                  : item.answerStatus === 'CONFIRMED_NOT_USED'
                    ? t('tailor.answerStatus.CONFIRMED_NOT_USED')
                    : t('tailor.userConfirmed')}
                {' · '}
                {t('tailor.relatedEntryLabel')}: {item.entryKey === 'GENERAL'
                  ? t('tailor.unlinkedProject')
                  : (item.entryLabel || item.entryKey)}
              </p>
              {item.context ? <p className="mt-2">{t('tailor.context')}: {item.context}</p> : null}
              {item.action ? <p>{t('tailor.action')}: {item.action}</p> : null}
              {item.result ? <p>{t('tailor.result')}: {item.result}</p> : null}
              {item.metric ? <p>{t('tailor.metric')}: {item.metric}</p> : null}
              <p className="mt-1 text-xs text-slate-500">
                {item.provenance === 'USER_REJECTED'
                  ? t('tailor.provenance.USER_REJECTED')
                  : t('tailor.provenance.USER_CONFIRMED')}
              </p>
              <button
                type="button"
                className="mt-3 rounded-lg border border-slate-300 px-3 py-1.5 text-sm disabled:opacity-60"
                disabled={deletingId === item.id}
                onClick={() => void onDelete(item)}
              >
                {t('tailor.deleteEvidence')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </details>
  )
}

function Field({
  label,
  value,
  placeholder,
  disabled,
  onChange,
}: {
  label: string
  value: string
  placeholder: string
  disabled: boolean
  onChange: (value: string) => void
}) {
  return (
    <label className="block text-sm">
      <span>{label}</span>
      <textarea
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
        rows={2}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function usedRequest(fields: {
  topic: string
  category: string
  entryKey: string
  relatedNodeIds: string[]
  context: string
  action: string
  result: string
  metric: string
}): SaveResumeEvidenceRequest {
  return {
    topic: fields.topic,
    category: fields.category,
    answerStatus: 'CONFIRMED_USED',
    entryKey: fields.entryKey,
    relatedNodeIds: fields.relatedNodeIds,
    context: fields.context,
    action: fields.action,
    result: fields.result,
    metric: fields.metric,
    confirmed: true,
  }
}
