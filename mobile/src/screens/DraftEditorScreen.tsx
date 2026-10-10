import { useEffect, useState, type ReactNode } from 'react'
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { apiErrorKey, isNotFound } from '../api/errors'
import type { ResumeResponse } from '../api/types'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import { ChevronLeftIcon } from '../components/Icons'
import { ChoiceChips, CountInput, EntryCard, FormInput, ListInput, SectionCard, SwitchRow } from '../builder/fields'
import type { LeaveReason } from '../builder/leaveGuard'
import {
  CUSTOM_PREFIX,
  LIMITS,
  addCustomSection,
  emptyEntry,
  hasEntryContent,
  removeCustomSection,
  setTargetTitle,
  type DraftForm,
  type FieldProblems,
} from '../builder/model'
import { CUSTOM_SECTION, ENTRY_SECTIONS, type EntrySection, type FieldDef } from '../builder/sections'
import { PROFESSIONAL_LINK_TYPES, RESUME_LOCALES, TEMPLATE_TYPES, type DraftContent, type EntrySectionKey } from '../builder/types'
import { useDraftEditor, type GenerateState, type PendingStatus, type SaveState } from '../builder/useDraftEditor'
import { displayFileName } from '../resumes/displayName'
import { ShareUnavailableError, downloadAndShare } from '../resumes/files'
import { colors, radius, space } from '../theme'

type Props = {
  draftId: number | null
  onBack: () => void
  onOpenFile: (resumeId: number) => void
  onOpenFiles: () => void
  /** Why leaving now would lose something, for the route's navigation guard. */
  onLeaveReasonChange?: (reason: LeaveReason | null) => void
}

export function DraftEditorScreen(props: Props) {
  const { t } = useTranslation()
  if (props.draftId === null) {
    return (
      <EditorFrame onBack={props.onBack}>
        <StateView tone="error" title={t('resumes.draft.notFoundTitle')} actionLabel={t('resumes.detail.back')} onAction={props.onBack} />
      </EditorFrame>
    )
  }
  return <Editor {...props} draftId={props.draftId} />
}

function Editor({ draftId, onBack, onOpenFile, onOpenFiles, onLeaveReasonChange }: Props & { draftId: number }) {
  const { t } = useTranslation()
  const editor = useDraftEditor(draftId, t('builder.untitled'))
  const { form, query, saveState, generateState } = editor
  const [open, setOpen] = useState<Record<string, boolean>>({ settings: true, basicInfo: true, jobTarget: true })
  const { flash, show } = useFlash()
  const share = useMutation({ mutationFn: (file: ResumeResponse) => downloadAndShare(file) })

  const leaveReason: LeaveReason | null = editor.busy ? 'busy' : editor.dirty ? 'unsaved' : null
  useEffect(() => {
    onLeaveReasonChange?.(leaveReason)
  }, [leaveReason, onLeaveReasonChange])

  if (!form) {
    if (query.isError && isNotFound(query.error)) {
      return (
        <EditorFrame onBack={onBack}>
          <StateView tone="error" title={t('resumes.draft.notFoundTitle')} actionLabel={t('resumes.detail.back')} onAction={onBack} />
        </EditorFrame>
      )
    }
    if (query.isError) {
      return (
        <EditorFrame onBack={onBack}>
          <StateView
            tone="error"
            title={t('resumes.draft.loadFailedTitle')}
            body={t(apiErrorKey(query.error))}
            actionLabel={t('resumes.retry')}
            onAction={() => void query.refetch()}
          />
        </EditorFrame>
      )
    }
    return (
      <EditorFrame onBack={onBack}>
        <View aria-busy aria-label={t('common.loading')} style={{ gap: 12 }}>
          <Skeleton width="70%" height={26} />
          <Skeleton width="100%" height={180} style={{ borderRadius: radius.card }} />
          <Skeleton width="100%" height={120} style={{ borderRadius: radius.card }} />
        </View>
      </EditorFrame>
    )
  }

  const problems = editor.problems
  const err = (path: string) => problemText(t, problems, path)
  const toggle = (id: string) => setOpen((state) => ({ ...state, [id]: !state[id] }))
  const update = editor.update
  const setContent = (change: (content: DraftContent) => DraftContent) => update((f) => ({ ...f, content: change(f.content) }))
  const conflict = saveState.kind === 'conflict'
  const generating = generateState.kind === 'generating'

  async function confirmRemove(entry: Record<string, unknown>): Promise<boolean> {
    if (!hasEntryContent(entry)) {
      return true
    }
    return confirmAction({
      title: t('builder.confirmRemove.title'),
      message: t('builder.confirmRemove.body'),
      confirmLabel: t('builder.remove'),
      cancelLabel: t('builder.confirmRemove.cancel'),
      destructive: true,
    })
  }

  async function reload() {
    const ok = await confirmAction({
      title: t('builder.confirmReload.title'),
      message: t('builder.confirmReload.body'),
      confirmLabel: t('builder.actions.reload'),
      cancelLabel: t('builder.confirmRemove.cancel'),
      destructive: true,
    })
    if (ok) {
      await editor.reload()
    }
  }

  async function onGenerate() {
    if (editor.pendingStatus === 'confirmable') {
      await editor.confirmPrevious()
      return
    }
    if (editor.pendingStatus === 'stale') {
      const ok = await confirmAction({
        title: t('builder.confirmNew.title'),
        message: t('builder.confirmNew.body'),
        confirmLabel: t('builder.actions.generateNew'),
        cancelLabel: t('builder.confirmRemove.cancel'),
      })
      if (!ok) {
        return
      }
    }
    await editor.generateNew()
  }

  async function onShare(resume: ResumeResponse) {
    try {
      await share.mutateAsync(resume)
      show('success', t('resumes.flash.shared'))
    } catch (error) {
      const reason = error instanceof ShareUnavailableError ? t('resumes.errors.shareUnavailable') : t(apiErrorKey(error))
      show('error', t('resumes.flash.downloadFailed', { reason }))
    }
  }

  const info = form.content.basicInfo
  const target = form.content.jobTarget
  const readiness = editor.readiness

  return (
    <EditorFrame
      onBack={onBack}
      status={<SaveStatus state={saveState} dirty={editor.dirty} />}
      actions={
        <>
          <FlashBanner flash={flash} />
          <View style={styles.actionRow}>
            <Button
              variant="secondary"
              label={saveState.kind === 'saving' ? t('builder.actions.saving') : t('builder.actions.save')}
              onPress={() => void editor.save()}
              loading={saveState.kind === 'saving'}
              disabled={conflict || generating || (!editor.dirty && saveState.kind !== 'failed')}
              style={styles.flex}
            />
            <Button
              label={
                generating
                  ? t('builder.actions.generating')
                  : editor.pendingStatus === 'confirmable'
                    ? t('builder.actions.confirmPrevious')
                    : editor.pendingStatus === 'stale'
                      ? t('builder.actions.generateNew')
                      : t('builder.actions.generate')
              }
              onPress={() => void onGenerate()}
              loading={generating}
              disabled={
                conflict ||
                saveState.kind === 'saving' ||
                (editor.pendingStatus !== 'confirmable' && !readiness?.ready)
              }
              style={styles.flex}
            />
          </View>
        </>
      }
    >
      <Text role="heading" style={styles.title} numberOfLines={2}>
        {form.title.trim() || t('builder.untitled')}
      </Text>

      {conflict ? (
        <View style={styles.block}>
          <Notice tone="error">{t('builder.notice.conflict')}</Notice>
          <Button variant="secondary" size="sm" label={t('builder.actions.reload')} onPress={() => void reload()} />
        </View>
      ) : null}
      {saveState.kind === 'failed' ? (
        <Notice tone="error">{t('builder.notice.saveFailed', { reason: t(saveState.errorKey) })}</Notice>
      ) : null}
      {editor.problemCount > 0 ? <Notice tone="error">{t('builder.notice.invalid', { count: editor.problemCount })}</Notice> : null}

      <SectionCard title={t('builder.sections.settings')} expanded={Boolean(open.settings)} onToggle={() => toggle('settings')}>
        <FormInput
          label={t('builder.fields.title')}
          value={form.title}
          placeholder={t('builder.untitled')}
          onChangeText={(value) => update((f) => ({ ...f, title: value }))}
          error={err('title')}
        />
        <ChoiceChips
          label={t('builder.fields.templateType')}
          value={form.templateType}
          onChange={(value) => update((f) => ({ ...f, templateType: value }))}
          options={TEMPLATE_TYPES.map((value) => ({ value, label: t(`resumes.templates.${value}`) }))}
        />
        <Text style={styles.hint}>{t('builder.templateNote')}</Text>
        <ChoiceChips
          label={t('builder.fields.locale')}
          value={form.locale}
          onChange={(value) => update((f) => ({ ...f, locale: value }))}
          options={RESUME_LOCALES.map((value) => ({ value, label: t(`builder.locales.${value}`) }))}
        />
      </SectionCard>

      <SectionCard title={t('builder.sections.basicInfo')} expanded={Boolean(open.basicInfo)} onToggle={() => toggle('basicInfo')}>
        <FormInput label={t('builder.fields.fullName')} value={info.fullName} error={err('basicInfo.fullName')}
          onChangeText={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, fullName: value } }))} />
        <FormInput label={t('builder.fields.email')} value={info.email} error={err('basicInfo.email')} keyboardType="email-address" autoCapitalize="none"
          onChangeText={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, email: value } }))} />
        <FormInput label={t('builder.fields.phone')} value={info.phone} error={err('basicInfo.phone')} keyboardType="phone-pad"
          onChangeText={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, phone: value } }))} />
        <FormInput label={t('builder.fields.city')} value={info.city} error={err('basicInfo.city')}
          onChangeText={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, city: value } }))} />
        <FormInput label={t('builder.fields.country')} value={info.country} error={err('basicInfo.country')}
          onChangeText={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, country: value } }))} />
        <SwitchRow
          label={t('builder.fields.legallyEligibleToWork')}
          value={info.legallyEligibleToWork === true}
          onChange={(value) => setContent((c) => ({ ...c, basicInfo: { ...c.basicInfo, legallyEligibleToWork: value ? true : null } }))}
        />

        <Text style={styles.subheading}>{t('builder.sections.professionalLinks')}</Text>
        <Text style={styles.hint}>{t('builder.linksHint')}</Text>
        {err('basicInfo.professionalLinks') ? <Text style={styles.error}>{err('basicInfo.professionalLinks')}</Text> : null}
        {info.professionalLinks.map((link, index) => (
          <EntryCard
            key={index}
            heading={link.label.trim() || t(`builder.linkTypes.${link.type}`, { defaultValue: link.type })}
            removeLabel={t('builder.remove')}
            onRemove={() =>
              void confirmRemove({ label: link.label, url: link.url }).then((ok) => {
                if (ok) {
                  setContent((c) => ({
                    ...c,
                    basicInfo: { ...c.basicInfo, professionalLinks: c.basicInfo.professionalLinks.filter((_, i) => i !== index) },
                  }))
                }
              })
            }
          >
            <ChoiceChips
              label={t('builder.fields.linkType')}
              value={link.type}
              options={PROFESSIONAL_LINK_TYPES.map((value) => ({ value, label: t(`builder.linkTypes.${value}`) }))}
              onChange={(value) => patchLink(setContent, index, { type: value })}
            />
            <FormInput label={t('builder.fields.linkLabel')} value={link.label} error={err(`basicInfo.professionalLinks.${index}.label`)}
              onChangeText={(value) => patchLink(setContent, index, { label: value })} />
            <FormInput label={t('builder.fields.linkUrl')} value={link.url} placeholder="https://" keyboardType="url" autoCapitalize="none"
              error={err(`basicInfo.professionalLinks.${index}.url`)} onChangeText={(value) => patchLink(setContent, index, { url: value })} />
          </EntryCard>
        ))}
        <AddButton
          label={t('builder.addLink')}
          count={info.professionalLinks.length}
          max={LIMITS.professionalLinks}
          onPress={() =>
            setContent((c) => ({
              ...c,
              basicInfo: { ...c.basicInfo, professionalLinks: [...c.basicInfo.professionalLinks, { type: 'OTHER', label: '', url: '' }] },
            }))
          }
        />
      </SectionCard>

      <SectionCard title={t('builder.sections.jobTarget')} expanded={Boolean(open.jobTarget)} onToggle={() => toggle('jobTarget')}>
        <FormInput label={t('builder.fields.targetTitle')} value={form.targetRole} error={err('targetRole')}
          onChangeText={(value) => update((f) => setTargetTitle(f, value))} />
        {(['jobLevel', 'targetIndustry', 'workMode', 'targetCity'] as const).map((key) => (
          <FormInput key={key} label={t(`builder.fields.${key}`)} value={target[key]} error={err(`jobTarget.${key}`)}
            onChangeText={(value) => setContent((c) => ({ ...c, jobTarget: { ...c.jobTarget, [key]: value } }))} />
        ))}
        <FormInput label={t('builder.fields.careerSummary')} value={target.careerSummary} multiline error={err('jobTarget.careerSummary')}
          hint={t('builder.hints.ownWords')}
          onChangeText={(value) => setContent((c) => ({ ...c, jobTarget: { ...c.jobTarget, careerSummary: value } }))} />
      </SectionCard>

      {ENTRY_SECTIONS.map((section) => (
        <EntriesCard
          key={section.key}
          section={section}
          form={form}
          problems={problems}
          expanded={Boolean(open[section.key])}
          onToggle={() => toggle(section.key)}
          setContent={setContent}
          confirmRemove={confirmRemove}
        />
      ))}

      <SectionCard
        title={t('builder.sections.hobbies')}
        summary={t('builder.sectionCount', { count: form.content.hobbies.length })}
        expanded={Boolean(open.hobbies)}
        onToggle={() => toggle('hobbies')}
      >
        <SwitchRow
          label={t('builder.showOnResume')}
          value={form.content.sectionEnabled.HOBBIES !== false}
          onChange={(value) => setContent((c) => ({ ...c, sectionEnabled: { ...c.sectionEnabled, HOBBIES: value } }))}
        />
        <ListInput label={t('builder.sections.hobbies')} hint={t('builder.hints.lines')} value={form.content.hobbies} error={err('hobbies')}
          onChange={(value) => setContent((c) => ({ ...c, hobbies: value }))} />
      </SectionCard>

      <EntriesCard
        section={CUSTOM_SECTION}
        form={form}
        problems={problems}
        expanded={Boolean(open.customSections)}
        onToggle={() => toggle('customSections')}
        setContent={setContent}
        confirmRemove={confirmRemove}
      />

      <GeneratePanel
        state={generateState}
        readinessMissing={
          readiness
            ? [
                !readiness.hasName && t('builder.missing.name'),
                !readiness.hasContact && t('builder.missing.contact'),
                !readiness.hasTarget && t('builder.missing.target'),
              ].filter((item): item is string => Boolean(item))
            : []
        }
        dirty={editor.dirty}
        pendingStatus={editor.pendingStatus}
        resultIsCurrent={editor.resultIsCurrent}
        sharing={share.isPending}
        onShare={(resume) => void onShare(resume)}
        onOpenFile={onOpenFile}
        onOpenFiles={onOpenFiles}
      />
    </EditorFrame>
  )
}

function patchLink(
  setContent: (change: (content: DraftContent) => DraftContent) => void,
  index: number,
  patch: Partial<DraftContent['basicInfo']['professionalLinks'][number]>,
) {
  setContent((c) => ({
    ...c,
    basicInfo: {
      ...c.basicInfo,
      professionalLinks: c.basicInfo.professionalLinks.map((link, i) => (i === index ? { ...link, ...patch } : link)),
    },
  }))
}

function problemText(t: TFunction, problems: FieldProblems, path: string): string | null {
  const problem = problems[path]
  return problem ? t(problem.key, problem.params) : null
}

function entriesOf(content: DraftContent, key: EntrySectionKey): Record<string, unknown>[] {
  return content[key] as unknown as Record<string, unknown>[]
}

function EntriesCard({
  section,
  form,
  problems,
  expanded,
  onToggle,
  setContent,
  confirmRemove,
}: {
  section: EntrySection
  form: DraftForm
  problems: FieldProblems
  expanded: boolean
  onToggle: () => void
  setContent: (change: (content: DraftContent) => DraftContent) => void
  confirmRemove: (entry: Record<string, unknown>) => Promise<boolean>
}) {
  const { t } = useTranslation()
  const list = entriesOf(form.content, section.key)
  const custom = section.key === 'customSections'
  const max = LIMITS.entries[section.key]

  const patch = (index: number, change: Record<string, unknown>) =>
    setContent((c) => ({
      ...c,
      [section.key]: entriesOf(c, section.key).map((entry, i) => (i === index ? { ...entry, ...change } : entry)),
    }))

  const remove = (index: number) =>
    void confirmRemove(list[index]).then((ok) => {
      if (!ok) {
        return
      }
      setContent((c) =>
        custom ? removeCustomSection(c, index) : { ...c, [section.key]: entriesOf(c, section.key).filter((_, i) => i !== index) },
      )
    })

  const add = () =>
    setContent((c) => (custom ? addCustomSection(c) : { ...c, [section.key]: [...entriesOf(c, section.key), emptyEntry(section.key)] }))

  return (
    <SectionCard title={t(section.title)} summary={t('builder.sectionCount', { count: list.length })} expanded={expanded} onToggle={onToggle}>
      {section.sectionId ? (
        <SwitchRow
          label={t('builder.showOnResume')}
          value={form.content.sectionEnabled[section.sectionId] !== false}
          onChange={(value) =>
            setContent((c) => ({ ...c, sectionEnabled: { ...c.sectionEnabled, [section.sectionId as string]: value } }))
          }
        />
      ) : null}
      {problems[section.key] ? <Text style={styles.error}>{problemText(t, problems, section.key)}</Text> : null}
      {list.length === 0 ? <Text style={styles.hint}>{t('builder.emptySection')}</Text> : null}
      {list.map((entry, index) => {
        const ref = custom ? `${CUSTOM_PREFIX}${String(entry.id)}` : null
        const heading =
          section.heading.map((key) => String(entry[key] ?? '').trim()).filter(Boolean).join(' · ') ||
          t('builder.entryFallback', { index: index + 1 })
        return (
          <EntryCard key={typeof entry.id === 'string' ? entry.id : index} heading={heading} removeLabel={t('builder.remove')} onRemove={() => remove(index)}>
            {ref ? (
              <SwitchRow
                label={t('builder.showOnResume')}
                value={form.content.sectionEnabled[ref] !== false}
                onChange={(value) => setContent((c) => ({ ...c, sectionEnabled: { ...c.sectionEnabled, [ref]: value } }))}
              />
            ) : null}
            {section.fields.map((field) =>
              field.key === 'endDate' && entry.current === true ? null : (
                <EntryField
                  key={field.key}
                  field={field}
                  value={entry[field.key]}
                  error={problemText(t, problems, `${section.key}.${index}.${field.key}`)}
                  onChange={(value) => patch(index, { [field.key]: value })}
                />
              ),
            )}
          </EntryCard>
        )
      })}
      <AddButton label={t(section.addLabel)} count={list.length} max={max} onPress={add} />
    </SectionCard>
  )
}

function EntryField({
  field,
  value,
  error,
  onChange,
}: {
  field: FieldDef
  value: unknown
  error: string | null
  onChange: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const label = t(field.label)
  switch (field.kind) {
    case 'list':
      return <ListInput label={label} hint={t('builder.hints.lines')} value={Array.isArray(value) ? (value as string[]) : []} error={error} onChange={onChange} />
    case 'count':
      return <CountInput label={label} value={typeof value === 'number' ? value : null} error={error} onChange={onChange} />
    case 'boolean':
      return <SwitchRow label={label} value={value === true} onChange={onChange} />
    default:
      return (
        <FormInput
          label={label}
          value={typeof value === 'string' ? value : ''}
          multiline={field.kind === 'multiline'}
          placeholder={field.kind === 'date' ? 'YYYY-MM' : field.kind === 'url' ? 'https://' : undefined}
          hint={field.kind === 'date' ? t('builder.hints.date') : undefined}
          keyboardType={field.kind === 'url' ? 'url' : undefined}
          autoCapitalize={field.kind === 'url' ? 'none' : undefined}
          error={error}
          onChangeText={onChange}
        />
      )
  }
}

function AddButton({ label, count, max, onPress }: { label: string; count: number; max: number; onPress: () => void }) {
  const { t } = useTranslation()
  const full = count >= max
  return (
    <View style={{ gap: 4 }}>
      <Button variant="secondary" size="sm" label={label} onPress={onPress} disabled={full} />
      {full ? <Text style={styles.hint}>{t('builder.maxReached', { max })}</Text> : null}
    </View>
  )
}

function SaveStatus({ state, dirty }: { state: SaveState; dirty: boolean }) {
  const { t } = useTranslation()
  let text: string | null = null
  let tone: 'muted' | 'good' | 'bad' = 'muted'
  if (state.kind === 'saving') {
    text = t('builder.status.saving')
  } else if (state.kind === 'conflict') {
    text = t('builder.status.conflict')
    tone = 'bad'
  } else if (state.kind === 'failed' || state.kind === 'invalid') {
    text = t('builder.status.failed')
    tone = 'bad'
  } else if (dirty) {
    text = t('builder.status.dirty')
  } else if (state.kind === 'saved') {
    text = t('builder.status.saved')
    tone = 'good'
  }
  if (!text) {
    return null
  }
  return (
    <Text
      role="status"
      aria-live="polite"
      accessibilityLiveRegion="polite"
      style={[styles.status, tone === 'good' && { color: colors.green }, tone === 'bad' && { color: colors.danger }]}
    >
      {text}
    </Text>
  )
}

function GeneratePanel({
  state,
  readinessMissing,
  dirty,
  pendingStatus,
  resultIsCurrent,
  sharing,
  onShare,
  onOpenFile,
  onOpenFiles,
}: {
  state: GenerateState
  readinessMissing: string[]
  dirty: boolean
  pendingStatus: PendingStatus
  resultIsCurrent: boolean
  sharing: boolean
  onShare: (resume: ResumeResponse) => void
  onOpenFile: (resumeId: number) => void
  onOpenFiles: () => void
}) {
  const { t, i18n } = useTranslation()
  const separator = i18n.language.startsWith('zh') ? '、' : ', '
  return (
    <View style={styles.panel}>
      <Text role="heading" style={styles.panelTitle}>
        {t('builder.generate.title')}
      </Text>
      <Text style={styles.panelBody}>{t('builder.generate.intro')}</Text>
      {readinessMissing.length > 0 ? (
        <Notice tone="info">{t('builder.generate.missing', { items: readinessMissing.join(separator) })}</Notice>
      ) : dirty && state.kind !== 'generating' ? (
        <Text style={styles.hint}>{t('builder.generate.savesFirst')}</Text>
      ) : null}

      {state.kind === 'generating' ? (
        <View role="status" aria-busy style={styles.result}>
          <Text style={styles.panelBody}>{t('builder.generate.running')}</Text>
        </View>
      ) : null}
      {state.kind === 'failed' ? <Notice tone="error">{t('builder.generate.failed', { reason: t(state.errorKey) })}</Notice> : null}
      {state.kind === 'unknown' ? (
        <View style={{ gap: 8 }}>
          <Notice tone="error">
            {state.restored || !state.errorKey
              ? t('builder.generate.restored')
              : t('builder.generate.unknown', { reason: t(state.errorKey) })}
          </Notice>
          <Text style={styles.panelBody}>
            {pendingStatus === 'stale' ? t('builder.generate.staleHint') : t('builder.generate.confirmHint')}
          </Text>
          <Button variant="secondary" size="sm" label={t('builder.generate.viewFiles')} onPress={onOpenFiles} />
        </View>
      ) : null}
      {state.kind === 'done' ? (
        <View role="status" style={styles.result}>
          <Text style={styles.resultTitle}>
            {t(resultIsCurrent ? 'builder.generate.done' : 'builder.generate.previousDone', {
              name: displayFileName(state.resume.fileName),
            })}
          </Text>
          <Text style={styles.panelBody}>
            {resultIsCurrent ? t('builder.generate.doneBody') : t('builder.generate.previousDoneBody')}
          </Text>
          <View style={styles.actionRow}>
            <Button
              variant="secondary"
              size="sm"
              label={sharing ? t('resumes.detail.downloading') : t('resumes.detail.download')}
              loading={sharing}
              disabled={!state.resume.fileAvailable}
              onPress={() => onShare(state.resume)}
              style={styles.flex}
            />
            <Button variant="secondary" size="sm" label={t('builder.generate.openFile')} onPress={() => onOpenFile(state.resume.id)} style={styles.flex} />
          </View>
        </View>
      ) : null}
    </View>
  )
}

function EditorFrame({
  onBack,
  status,
  actions,
  children,
}: {
  onBack: () => void
  status?: ReactNode
  actions?: ReactNode
  children: ReactNode
}) {
  const { t } = useTranslation()
  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
      <View style={styles.bar}>
        <Pressable
          role="button"
          aria-label={t('resumes.detail.back')}
          onPress={onBack}
          hitSlop={8}
          style={({ pressed }) => [styles.back, pressed && { opacity: 0.6 }]}
        >
          <ChevronLeftIcon />
          <Text style={styles.backText}>{t('resumes.detail.back')}</Text>
        </Pressable>
        {status}
      </View>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView
          style={styles.flex}
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode={Platform.OS === 'ios' ? 'interactive' : 'on-drag'}
        >
          {children}
        </ScrollView>
        {actions ? <View style={styles.actions}>{actions}</View> : null}
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  bar: { minHeight: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 10, gap: 12 },
  back: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44, paddingRight: 12 },
  backText: { fontSize: 16, fontWeight: '600', color: colors.blue },
  status: { flexShrink: 1, paddingRight: 8, fontSize: 13, fontWeight: '600', color: colors.muted, textAlign: 'right' },
  content: { gap: space.sectionGap, paddingHorizontal: space.screenX, paddingBottom: 32 },
  title: { fontSize: 22, lineHeight: 30, fontWeight: '700', color: colors.ink, letterSpacing: -0.4 },
  block: { gap: 8 },
  subheading: { marginTop: 4, fontSize: 15, fontWeight: '700', color: colors.ink },
  hint: { fontSize: 13, lineHeight: 18, color: colors.muted },
  error: { fontSize: 13, color: colors.danger },
  actions: {
    gap: 10,
    paddingHorizontal: space.screenX,
    paddingTop: 10,
    paddingBottom: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.hairline,
    backgroundColor: 'rgba(255,255,255,0.96)',
  },
  actionRow: { flexDirection: 'row', gap: 10 },
  panel: { gap: 10, padding: 16, borderRadius: radius.card, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.card },
  panelTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  panelBody: { fontSize: 14, lineHeight: 20, color: colors.ink2 },
  result: { gap: 8, padding: 12, borderRadius: radius.tile, backgroundColor: colors.surface },
  resultTitle: { fontSize: 15, fontWeight: '700', color: colors.ink },
})
