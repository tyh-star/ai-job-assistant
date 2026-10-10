import { useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Notice } from '../../components/AuthShell'
import { Button } from '../../components/Button'
import { detailStyles } from '../../components/DetailLayout'
import { confirmAction } from '../../components/feedback'
import { ChoiceChips, FormInput } from '../../builder/fields'
import { colors, radius } from '../../theme'
import { formatLocalDateTime, isProposalExpired } from '../model'
import { Badge, RejectedList, ResultView, tailorStyles } from '../resultViews'
import type { TailoredResumeResponse, V3EntryReview, V3EvidenceUsage, V3OpportunityEntry, V3SkillReview } from '../types'
import {
  actionableEntries,
  allowedEntryKeys,
  answerSaved,
  confirmedUsedNeedsText,
  entryTitleForKey,
  evidenceDisplayText,
  hasCannotInvent,
  matchedRequirements,
  mostRelevantTitles,
  originalJoined,
  proposedJoined,
  relatedRequirementForQuestion,
  selectedEntryKey,
  suggestionTypeLabelKey,
  suppliedInfoForEntry,
  usageCounts,
  usageReasonKey,
  visibleQuestions,
  type AnswerIntent,
} from './rules'
import type { EnhancedController, EnhancedStage, FlowError } from './useEnhancedTailor'

function errorText(t: TFunction, error: FlowError | null): string {
  if (!error) {
    return ''
  }
  return t(error.key, { reason: error.reason ? t(error.reason, (error.params ?? {}) as Record<string, string>) : '', ...(error.params ?? {}) })
}

const PROGRESS: Partial<Record<EnhancedStage, string>> = {
  preparing: 'tailor.progress.preparing',
  scanning: 'tailor.enhanced.progress.scanning',
  restoring: 'tailor.enhanced.progress.restoring',
  submitting: 'tailor.enhanced.progress.submitting',
  generating: 'tailor.enhanced.progress.generating',
  confirming: 'tailor.actions.confirming',
  checking: 'tailor.actions.checking',
}

export function EnhancedFlowView({ controller }: { controller: EnhancedController }) {
  const { t, i18n } = useTranslation()
  const { state } = controller
  const zh = i18n.language.toLowerCase().startsWith('zh')

  switch (state.stage) {
    case 'idle':
      return null
    case 'preparing':
    case 'scanning':
    case 'restoring':
    case 'generating':
      return (
        <View role="status" aria-busy style={tailorStyles.panel}>
          <Text style={tailorStyles.panelBody}>{t(PROGRESS[state.stage] as string)}</Text>
        </View>
      )
    case 'questions':
    case 'submitting':
      return <QuestionsView controller={controller} zh={zh} />
    case 'scanFailed':
    case 'generateFailed':
    case 'generateUnknown':
    case 'restoreFailed':
      return (
        <View style={tailorStyles.block}>
          <Notice tone="error">{errorText(t, state.error)}</Notice>
        </View>
      )
    case 'done':
      return state.result ? (
        <ResultView result={state.result} language={i18n.language} t={t}>
          <Notice tone="info">{t('tailor.enhanced.done.completed')}</Notice>
        </ResultView>
      ) : null
    case 'gone':
      return (
        <View style={tailorStyles.block}>
          <Notice tone="error">{t('tailor.enhanced.gone.unavailable')}</Notice>
          {state.pastExpiry ? <Text style={tailorStyles.hint}>{t('tailor.enhanced.gone.pastExpiry')}</Text> : null}
        </View>
      )
    default:
      return <ReviewView controller={controller} zh={zh} />
  }
}

function QuestionsView({ controller, zh }: { controller: EnhancedController; zh: boolean }) {
  const { t } = useTranslation()
  const { state } = controller
  const map = state.opportunity
  if (!map) {
    return null
  }
  const questions = visibleQuestions(map.questions)
  const submitting = state.stage === 'submitting'
  const index = Math.min(state.questionIndex, Math.max(questions.length - 1, 0))
  const current = questions[index]
  const relevant = mostRelevantTitles(map)

  return (
    <View style={tailorStyles.block}>
      <View style={tailorStyles.panel}>
        <Text role="heading" style={tailorStyles.panelTitle}>
          {t('tailor.enhanced.opportunity.title')}
        </Text>
        <Text style={tailorStyles.panelBody}>
          {[
            t('tailor.enhanced.opportunity.checked', { count: map.entries.length }),
            t('tailor.enhanced.opportunity.worth', { count: actionableEntries(map).length }),
            t('tailor.enhanced.opportunity.questions', { count: questions.length }),
          ].join(' · ')}
        </Text>
        {relevant.length > 0 ? (
          <Text style={tailorStyles.hint}>{t('tailor.enhanced.opportunity.mostRelevant', { names: relevant.join(zh ? '、' : ', ') })}</Text>
        ) : null}
        {hasCannotInvent(map) ? <Text style={tailorStyles.hint}>{t('tailor.enhanced.opportunity.cannotInvent')}</Text> : null}
      </View>

      <View style={tailorStyles.panel}>
        <Text role="heading" style={tailorStyles.panelTitle}>
          {t('tailor.enhanced.questions.title')}
        </Text>
        {state.error && (!current || state.failedQuestionId !== current.id) ? <Notice tone="error">{errorText(t, state.error)}</Notice> : null}
        {current ? (
          <QuestionCard controller={controller} zh={zh} questionIndex={index} total={questions.length} submitting={submitting} />
        ) : (
          <Text style={tailorStyles.panelBody}>{t('tailor.enhanced.questions.none')}</Text>
        )}
        <Text style={tailorStyles.hint}>{t('tailor.enhanced.questions.saveNote')}</Text>
      </View>
    </View>
  )
}

function QuestionCard({
  controller,
  zh,
  questionIndex,
  total,
  submitting,
}: {
  controller: EnhancedController
  zh: boolean
  questionIndex: number
  total: number
  submitting: boolean
}) {
  const { t } = useTranslation()
  const { state } = controller
  const map = state.opportunity
  const question = visibleQuestions(map?.questions)[questionIndex]
  if (!map || !question) {
    return null
  }
  const draft = state.answers[question.id] ?? { intent: null, text: '' }
  const entryKey = selectedEntryKey(question, draft)
  const entry =
    map.entries.find((item) => item.canonicalEntryKey === entryKey) ??
    map.entries.find((item) => item.canonicalEntryKey === question.canonicalEntryKey)
  const related = relatedRequirementForQuestion(entry, question)
  const options = allowedEntryKeys(question)
    .map((key) => map.entries.find((item) => item.canonicalEntryKey === key))
    .filter((item): item is V3OpportunityEntry => Boolean(item))
  const needsText = confirmedUsedNeedsText(question, draft)
  const failed = state.failedQuestionId === question.id && state.error
  const onlyNeedsText = needsText && state.error?.key === 'tailor.enhanced.errors.needRealDetails'
  const update = (next: Partial<typeof draft>) => controller.setAnswer(question.id, { ...draft, ...next })

  return (
    <View style={tailorStyles.card}>
      <Text style={tailorStyles.hint}>{t('tailor.enhanced.questions.progress', { current: questionIndex + 1, total })}</Text>
      <Text style={styles.entryTitle}>{entry?.entryTitle || question.entryTitle}</Text>
      <Text style={tailorStyles.panelBody}>{zh ? question.promptZh || question.promptEn : question.promptEn || question.promptZh}</Text>
      {related ? <Text style={tailorStyles.hint}>{t('tailor.enhanced.questions.whyAsking', { text: related })}</Text> : null}
      {options.length > 1 ? (
        <ChoiceChips
          label={t('tailor.enhanced.questions.chooseEntry')}
          value={entryKey}
          disabled={submitting}
          options={options.map((option) => ({ value: option.canonicalEntryKey, label: option.entryTitle }))}
          onChange={(value) => update({ entryKey: value })}
        />
      ) : null}
      <ChoiceChips<Exclude<AnswerIntent, null>>
        label={t('tailor.enhanced.questions.answerLabel')}
        value={draft.intent ?? ''}
        disabled={submitting}
        options={[
          { value: 'used', label: t('tailor.enhanced.questions.used') },
          { value: 'not_used', label: t('tailor.enhanced.questions.notUsed') },
          { value: 'skipped', label: t('tailor.enhanced.questions.skipped') },
        ]}
        onChange={(value) => update(value === 'used' ? { intent: 'used' } : { intent: value, text: '' })}
      />
      {draft.intent === 'used' ? (
        <FormInput
          label={t('tailor.enhanced.questions.specificDetails')}
          value={draft.text}
          multiline
          onChangeText={(text) => update({ intent: 'used', text })}
          error={needsText ? t('tailor.enhanced.errors.needRealDetails') : null}
        />
      ) : null}
      {answerSaved(question, draft) ? <Badge text={t('tailor.enhanced.questions.saved')} tone="good" /> : null}
      {failed && !onlyNeedsText ? <Notice tone="error">{errorText(t, state.error)}</Notice> : null}
      <View style={tailorStyles.row}>
        <Button
          variant="secondary"
          size="sm"
          label={t('tailor.enhanced.questions.previous')}
          disabled={questionIndex === 0 || submitting}
          onPress={() => controller.setQuestionIndex(questionIndex - 1)}
        />
        <Button
          variant="secondary"
          size="sm"
          label={t('tailor.enhanced.questions.next')}
          disabled={questionIndex >= total - 1 || submitting}
          onPress={() => controller.setQuestionIndex(questionIndex + 1)}
        />
      </View>
    </View>
  )
}

function ReviewView({ controller, zh }: { controller: EnhancedController; zh: boolean }) {
  const { t } = useTranslation()
  const { state } = controller
  const proposal = state.proposal
  if (!proposal) {
    return null
  }
  const locked = state.stage !== 'review' && state.stage !== 'confirmFailed'
  const entries = proposal.entryReviews ?? []
  const skills = proposal.skillReviews ?? []
  const expiresAt = state.record?.expiresAt ?? proposal.expiresAt
  const time = formatLocalDateTime(expiresAt)

  return (
    <View style={tailorStyles.block}>
      <View style={tailorStyles.panel}>
        <Text role="heading" style={tailorStyles.panelTitle}>
          {t('tailor.enhanced.review.title')}
        </Text>
        <Text style={tailorStyles.panelBody}>{t('tailor.enhanced.review.noFilesYet')}</Text>
        {isProposalExpired(expiresAt) ? (
          <Notice tone="error">{t('tailor.enhanced.review.expired')}</Notice>
        ) : time ? (
          <Text style={tailorStyles.hint}>{t('tailor.preview.expiresAt', { time })}</Text>
        ) : null}
        {state.notice ? <Notice tone="info">{t(`tailor.preview.${state.notice}`)}</Notice> : null}
        {state.stale ? (
          <Notice tone="error">{t(state.staleReason === 'structure' ? 'tailor.structure.outdated' : 'tailor.enhanced.review.stale')}</Notice>
        ) : null}
        {state.error ? <Notice tone="error">{errorText(t, state.error)}</Notice> : null}
        {entries.length === 0 && skills.length === 0 ? (
          <Notice tone="info">{t('tailor.enhanced.review.noHighValue')}</Notice>
        ) : (
          <Text style={tailorStyles.hint}>
            {t('tailor.enhanced.review.selected', {
              selected: state.selectedEntryKeys.length + state.selectedSkillIds.length,
              total: entries.length + skills.length,
            })}
          </Text>
        )}
        <Text style={tailorStyles.hint}>{t('tailor.enhanced.review.discardNote')}</Text>
      </View>
      <EvidencePanel controller={controller} usages={proposal.evidenceUsage ?? []} locked={locked} />
      {entries.map((review) => (
        <EntryCard key={review.canonicalEntryKey} controller={controller} review={review} locked={locked} zh={zh} />
      ))}
      {skills.map((review) => (
        <SkillCard key={review.skillReviewId} controller={controller} review={review} locked={locked} />
      ))}
      <RejectedList result={proposal} t={t} />
    </View>
  )
}

function CheckRow({ label, checked, disabled, onPress }: { label: string; checked: boolean; disabled: boolean; onPress: () => void }) {
  const { t } = useTranslation()
  return (
    <Pressable
      role="checkbox"
      aria-label={label}
      aria-checked={checked}
      aria-disabled={disabled}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.check, pressed && { opacity: 0.7 }]}
    >
      <View style={[styles.box, checked && styles.boxOn]}>{checked ? <Text style={styles.tick}>✓</Text> : null}</View>
      <Text style={tailorStyles.panelBody}>{checked ? t('tailor.enhanced.entry.selected') : t('tailor.enhanced.entry.notSelected')}</Text>
    </Pressable>
  )
}

function EditControls({
  controller,
  kind,
  itemKey,
  edited,
  editing,
  locked,
}: {
  controller: EnhancedController
  kind: 'entry' | 'skill'
  itemKey: string
  edited: boolean
  editing: boolean
  locked: boolean
}) {
  const { t } = useTranslation()
  if (editing) {
    return (
      <View style={tailorStyles.row}>
        <Button variant="secondary" size="sm" label={t('tailor.enhanced.entry.cancelEdit')} onPress={controller.cancelEdit} />
        <Button size="sm" label={t('tailor.enhanced.entry.saveEdit')} onPress={controller.saveEdit} />
      </View>
    )
  }
  return (
    <View style={tailorStyles.row}>
      <Button
        variant="secondary"
        size="sm"
        label={t('tailor.enhanced.entry.edit')}
        disabled={locked || controller.state.editing !== null}
        onPress={() => controller.startEdit(kind, itemKey)}
      />
      {edited ? (
        <Button
          variant="secondary"
          size="sm"
          label={t('tailor.enhanced.entry.restoreAi')}
          disabled={locked || controller.state.editing !== null}
          onPress={() => controller.restoreAi(kind, itemKey)}
        />
      ) : null}
    </View>
  )
}

function EntryCard({ controller, review, locked, zh }: { controller: EnhancedController; review: V3EntryReview; locked: boolean; zh: boolean }) {
  const { t } = useTranslation()
  const { state } = controller
  const key = review.canonicalEntryKey
  const proposed = proposedJoined(review, state.texts)
  const edited = proposed !== proposedJoined(review, state.aiOriginal)
  const editing = state.editing?.kind === 'entry' && state.editing.key === key
  const supplied = suppliedInfoForEntry(review, state.proposal?.evidenceUsage, state.evidence, state.opportunity, state.answers)
  const requirements = matchedRequirements(state.opportunity?.entries.find((item) => item.canonicalEntryKey === key))
  const why = zh ? review.whyBetterZh || review.whyBetter : review.whyBetter || review.whyBetterZh
  const selectable = (review.proposedNodes ?? []).some((node) => node.nodeId)

  return (
    <View style={tailorStyles.panel}>
      <Text role="heading" style={styles.entryTitle}>
        {review.entryTitle}
      </Text>
      <Badge text={t(suggestionTypeLabelKey(review.suggestionType))} />
      {selectable ? (
        <CheckRow label={review.entryTitle} checked={state.selectedEntryKeys.includes(key)} disabled={locked} onPress={() => controller.toggleEntry(key)} />
      ) : null}
      {why ? (
        <Text style={tailorStyles.panelBody}>
          <Text style={styles.strong}>{t('tailor.change.whyBetter')}：</Text>
          {why}
        </Text>
      ) : null}
      {supplied.length > 0 ? (
        <View style={{ gap: 2 }}>
          <Text style={tailorStyles.label}>{t('tailor.enhanced.entry.usedYourInfo')}</Text>
          {supplied.map((item) => (
            <Text key={item} style={tailorStyles.hint}>
              · {item}
            </Text>
          ))}
        </View>
      ) : null}
      {requirements.length > 0 ? (
        <Text style={tailorStyles.hint}>{t('tailor.enhanced.entry.matched', { items: requirements.join(zh ? '；' : '; ') })}</Text>
      ) : null}
      <Text style={tailorStyles.label}>{t('tailor.change.original')}</Text>
      <Text style={tailorStyles.quote}>{originalJoined(review)}</Text>
      <Text style={tailorStyles.label}>{t('tailor.enhanced.entry.enhanced')}</Text>
      {editing && state.editing ? (
        <FormInput
          label={t('tailor.enhanced.entry.editLabel', { title: review.entryTitle })}
          value={state.editing.draft}
          multiline
          hint={t('tailor.enhanced.entry.editHint', { count: (review.proposedNodes ?? []).length })}
          onChangeText={controller.changeEdit}
        />
      ) : (
        <Text style={[tailorStyles.quote, tailorStyles.rewritten]}>{proposed}</Text>
      )}
      {edited && !editing ? <Text style={tailorStyles.hint}>{t('tailor.enhanced.entry.edited')}</Text> : null}
      {selectable ? <EditControls controller={controller} kind="entry" itemKey={key} edited={edited} editing={editing} locked={locked} /> : null}
    </View>
  )
}

function SkillCard({ controller, review, locked }: { controller: EnhancedController; review: V3SkillReview; locked: boolean }) {
  const { t } = useTranslation()
  const { state } = controller
  const id = review.skillReviewId
  const proposed = state.texts[id] ?? review.proposedSkills
  const edited = proposed !== (state.aiOriginal[id] ?? review.proposedSkills)
  const editing = state.editing?.kind === 'skill' && state.editing.key === id

  return (
    <View style={tailorStyles.panel}>
      <Text role="heading" style={styles.entryTitle}>
        {t('tailor.enhanced.skills.title')}
      </Text>
      <CheckRow label={t('tailor.enhanced.skills.title')} checked={state.selectedSkillIds.includes(id)} disabled={locked} onPress={() => controller.toggleSkill(id)} />
      {review.whyBetter ? (
        <Text style={tailorStyles.panelBody}>
          <Text style={styles.strong}>{t('tailor.change.whyBetter')}：</Text>
          {review.whyBetter}
        </Text>
      ) : null}
      {review.addedSkills && review.addedSkills.length > 0 ? (
        <Text style={tailorStyles.hint}>{t('tailor.enhanced.skills.added', { names: review.addedSkills.join(', ') })}</Text>
      ) : null}
      <Text style={tailorStyles.label}>{t('tailor.enhanced.skills.original')}</Text>
      <Text style={tailorStyles.quote}>{review.originalSkills}</Text>
      <Text style={tailorStyles.label}>{t('tailor.enhanced.skills.proposed')}</Text>
      {editing && state.editing ? (
        <FormInput label={t('tailor.enhanced.skills.editLabel')} value={state.editing.draft} multiline onChangeText={controller.changeEdit} />
      ) : (
        <Text style={[tailorStyles.quote, tailorStyles.rewritten]}>{proposed}</Text>
      )}
      {edited && !editing ? <Text style={tailorStyles.hint}>{t('tailor.enhanced.entry.edited')}</Text> : null}
      <EditControls controller={controller} kind="skill" itemKey={id} edited={edited} editing={editing} locked={locked} />
    </View>
  )
}

function EvidencePanel({ controller, usages, locked }: { controller: EnhancedController; usages: V3EvidenceUsage[]; locked: boolean }) {
  const { t } = useTranslation()
  const { state } = controller
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  if (usages.length === 0) {
    return null
  }
  const counts = usageCounts(usages)
  const busy = locked || state.evidenceBusy

  async function saveCorrection(usage: V3EvidenceUsage) {
    const ok = await confirmAction({
      title: t('tailor.enhanced.evidence.correctTitle'),
      message: t('tailor.enhanced.evidence.correctBody'),
      confirmLabel: t('tailor.enhanced.evidence.save'),
      cancelLabel: t('tailor.enhanced.evidence.cancel'),
    })
    if (ok && (await controller.correctEvidence(usage, draft))) {
      setEditingId(null)
    }
  }

  async function remove(usage: V3EvidenceUsage) {
    const ok = await confirmAction({
      title: t('tailor.enhanced.evidence.deleteTitle'),
      message: t('tailor.enhanced.evidence.deleteBody'),
      confirmLabel: t('tailor.enhanced.evidence.delete'),
      cancelLabel: t('tailor.enhanced.evidence.cancel'),
      destructive: true,
    })
    if (ok) {
      await controller.deleteEvidence(usage)
    }
  }

  return (
    <View style={tailorStyles.panel}>
      <Text role="heading" style={tailorStyles.panelTitle}>
        {t('tailor.enhanced.evidence.title')}
      </Text>
      <Text style={tailorStyles.panelBody}>{t('tailor.enhanced.evidence.summary', counts)}</Text>
      {state.evidenceError ? <Notice tone="error">{errorText(t, state.evidenceError)}</Notice> : null}
      {usages.map((usage) => {
        const item = state.evidence?.find((row) => row.id === usage.evidenceItemId)
        const text = evidenceDisplayText(usage, state.evidence)
        const used = (usage.usageStatus ?? '').startsWith('USED_')
        const titles = (usage.usedInEntryKeys ?? []).map((key) => entryTitleForKey(key, state.opportunity)).filter(Boolean)
        const skills = usage.usedInSkillKeys ?? []
        const status = used
          ? [
              titles.length > 0 ? t('tailor.enhanced.evidence.usedInEntry', { name: titles.join(', ') }) : '',
              skills.length > 0 ? t('tailor.enhanced.evidence.usedInSkills', { names: skills.join(', ') }) : '',
            ]
              .filter(Boolean)
              .join(' · ') || t('tailor.enhanced.evidence.usedInEnhancement')
          : usage.usageStatus === 'USER_REJECTED'
            ? t('tailor.enhanced.evidence.userRejected')
            : t(usageReasonKey(usage.reasonCode))
        return (
          <View key={usage.evidenceItemId} style={tailorStyles.card}>
            <Text style={tailorStyles.panelBody}>{text || t('tailor.enhanced.evidence.noText')}</Text>
            <Text style={tailorStyles.hint}>{status}</Text>
            {editingId === usage.evidenceItemId ? (
              <>
                <FormInput label={t('tailor.enhanced.evidence.correctLabel')} value={draft} multiline onChangeText={setDraft} />
                <View style={tailorStyles.row}>
                  <Button variant="secondary" size="sm" label={t('tailor.enhanced.evidence.cancel')} disabled={state.evidenceBusy} onPress={() => setEditingId(null)} />
                  <Button
                    size="sm"
                    label={t('tailor.enhanced.evidence.save')}
                    loading={state.evidenceBusy}
                    disabled={!draft.trim()}
                    onPress={() => void saveCorrection(usage)}
                  />
                </View>
              </>
            ) : (
              <View style={tailorStyles.row}>
                <Button
                  variant="secondary"
                  size="sm"
                  label={t('tailor.enhanced.evidence.correct')}
                  disabled={busy || !item || editingId !== null}
                  onPress={() => {
                    setEditingId(usage.evidenceItemId)
                    setDraft(item?.action ?? text)
                  }}
                />
                <Button
                  variant="secondary"
                  size="sm"
                  label={t('tailor.enhanced.evidence.delete')}
                  disabled={busy || editingId !== null}
                  onPress={() => void remove(usage)}
                />
              </View>
            )}
          </View>
        )
      })}
    </View>
  )
}

export function EnhancedActions({
  controller,
  canRun,
  sharing,
  onStart,
  onRetryRestore,
  onShare,
}: {
  controller: EnhancedController
  canRun: boolean
  sharing: boolean
  onStart: () => void
  onRetryRestore: () => void
  onShare: (result: TailoredResumeResponse, url: string | null) => void
}) {
  const { t } = useTranslation()
  const { state } = controller

  async function discard(message: string) {
    const ok = await confirmAction({
      title: t('tailor.confirmDiscard.title'),
      message,
      confirmLabel: t('tailor.confirmDiscard.yes'),
      cancelLabel: t('tailor.confirmDiscard.no'),
      destructive: true,
    })
    if (ok) {
      await controller.discard()
    }
  }

  async function retryUnknown() {
    const ok = await confirmAction({
      title: t('tailor.enhanced.review.retryGenerateTitle'),
      message: t('tailor.enhanced.review.retryGenerateBody'),
      confirmLabel: t('tailor.enhanced.review.retryGenerate'),
      cancelLabel: t('tailor.confirmDiscard.no'),
    })
    if (ok) {
      await controller.retryGenerate()
    }
  }

  const discardButton = (message: string) => (
    <Button variant="secondary" label={t('tailor.actions.discard')} onPress={() => void discard(message)} style={detailStyles.flex} />
  )

  const progress = PROGRESS[state.stage]
  if (progress) {
    return <Button label={t(progress)} onPress={() => {}} loading />
  }

  switch (state.stage) {
    case 'questions':
      return (
        <View style={detailStyles.actionRow}>
          {discardButton(t('tailor.enhanced.questions.discardBody'))}
          <Button label={t('tailor.enhanced.questions.submit')} onPress={() => void controller.submit()} style={detailStyles.flex} />
        </View>
      )
    case 'generateFailed':
    case 'generateUnknown':
      return (
        <View style={detailStyles.actionRow}>
          {discardButton(t('tailor.enhanced.review.discardBody'))}
          <Button
            label={t('tailor.enhanced.review.retryGenerate')}
            onPress={() => void (state.stage === 'generateUnknown' ? retryUnknown() : controller.retryGenerate())}
            style={detailStyles.flex}
          />
        </View>
      )
    case 'review':
    case 'confirmFailed': {
      if (state.stale) {
        return (
          <View style={detailStyles.actionRow}>
            {discardButton(t('tailor.enhanced.review.discardBody'))}
            <Button label={t('tailor.enhanced.review.regenerate')} onPress={() => void controller.regenerate()} style={detailStyles.flex} />
          </View>
        )
      }
      const nothing = state.selectedEntryKeys.length + state.selectedSkillIds.length === 0
      const expired = isProposalExpired(state.record?.expiresAt ?? state.proposal?.expiresAt)
      return (
        <View style={detailStyles.actionRow}>
          {discardButton(t('tailor.enhanced.review.discardBody'))}
          <Button
            label={t('tailor.enhanced.review.confirm')}
            onPress={() => void controller.confirm()}
            disabled={nothing || expired || state.editing !== null || state.evidenceBusy}
            style={detailStyles.flex}
          />
        </View>
      )
    }
    case 'confirmUnknown':
      return <Button label={t('tailor.actions.check')} onPress={() => void controller.check()} />
    case 'restoreFailed':
      return (
        <View style={detailStyles.actionRow}>
          {discardButton(t('tailor.enhanced.review.discardBody'))}
          <Button label={t('tailor.actions.retryRestore')} onPress={onRetryRestore} style={detailStyles.flex} />
        </View>
      )
    case 'done': {
      const result = state.result
      if (!result) {
        return null
      }
      const pdfReady = result.pdfExportStatus === 'SUCCESS' && Boolean(result.pdfDownloadUrl)
      return (
        <>
          <View style={detailStyles.actionRow}>
            <Button
              label={sharing ? t('tailor.actions.downloading') : t('tailor.actions.downloadDocx')}
              loading={sharing}
              onPress={() => onShare(result, result.downloadUrl)}
              style={detailStyles.flex}
            />
            {pdfReady ? (
              <Button
                variant="secondary"
                label={t('tailor.actions.downloadPdf')}
                disabled={sharing}
                onPress={() => onShare(result, result.pdfDownloadUrl)}
                style={detailStyles.flex}
              />
            ) : null}
          </View>
          <Button variant="secondary" size="sm" label={t('tailor.actions.again')} onPress={controller.reset} disabled={sharing} />
        </>
      )
    }
    case 'gone':
      return state.tailorResumeId !== null ? (
        <Button label={t('tailor.enhanced.review.regenerate')} onPress={() => void controller.regenerate()} disabled={!canRun} />
      ) : (
        <Button label={t('tailor.actions.runEnhanced')} onPress={onStart} disabled={!canRun} />
      )
    default:
      return <Button label={t('tailor.actions.runEnhanced')} onPress={onStart} disabled={!canRun} />
  }
}

const styles = StyleSheet.create({
  entryTitle: { fontSize: 15, fontWeight: '700', color: colors.ink },
  strong: { fontWeight: '700' },
  check: { flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: 40 },
  box: {
    width: 22,
    height: 22,
    borderRadius: radius.chip / 2,
    borderWidth: 2,
    borderColor: colors.inputBorder,
    alignItems: 'center',
    justifyContent: 'center',
  },
  boxOn: { borderColor: colors.blue, backgroundColor: colors.blue },
  tick: { color: '#FFFFFF', fontSize: 13, fontWeight: '700' },
})
