import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { StyleSheet, Text, View } from 'react-native'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/client'
import { resumesApi } from '../api/endpoints'
import { apiErrorKey } from '../api/errors'
import { EntryCard, FormInput, ListInput } from '../builder/fields'
import type { LeaveReason } from '../builder/leaveGuard'
import { useSessionGuard } from '../builder/sessionGuard'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { DetailLayout, detailStyles } from '../components/DetailLayout'
import { Skeleton, StateView, confirmAction } from '../components/feedback'
import { resumeKeys } from '../resumes/queries'
import {
  emptyEducation,
  emptyExperience,
  emptyProject,
  entryHasContent,
  missingTailorContent,
  normalizeStructure,
  saveStructureSavedAt,
  structureSignature,
  validateStructure,
  withSkillNames,
  withSummaryTexts,
  type ResumeStructure,
  type StructureEducation,
  type StructureExperience,
  type StructureProject,
} from '../resumes/structure'
import { colors, radius } from '../theme'

type Props = {
  /** A PDF file id from GET /resumes; null when the route value was not a valid id. */
  resumeId: number | null
  /** Opened from tailoring: after a save the user can return to it. */
  fromTailor?: boolean
  onBack: () => void
  onContinueTailor?: () => void
  onLeaveReasonChange?: (reason: LeaveReason | null) => void
}

type Load = { kind: 'loading' } | { kind: 'notFound' } | { kind: 'unsupported' } | { kind: 'failed'; errorKey: string } | { kind: 'ready' }

type ListKey = 'experience' | 'projects' | 'education'

/** Review and correct a PDF's recognized content (the website's ResumeStructurePage). */
export function ResumeStructureScreen({ resumeId, fromTailor = false, onBack, onContinueTailor, onLeaveReasonChange }: Props) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const [load, setLoad] = useState<Load>(resumeId === null ? { kind: 'notFound' } : { kind: 'loading' })
  const [doc, setDoc] = useState<ResumeStructure | null>(null)
  const [baseline, setBaseline] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [nameError, setNameError] = useState<string | null>(null)
  const savingRef = useRef(false)

  const dirty = doc !== null && structureSignature(doc) !== baseline

  useEffect(() => {
    onLeaveReasonChange?.(saving ? 'busy' : dirty ? 'unsaved' : null)
  }, [saving, dirty, onLeaveReasonChange])

  const fetchStructure = useCallback(async () => {
    if (resumeId === null) {
      return
    }
    const ticket = guard.capture()
    setLoad({ kind: 'loading' })
    try {
      const data = normalizeStructure(await resumesApi.structure(resumeId))
      if (!ticket.isCurrent()) {
        return
      }
      setDoc(data)
      setBaseline(structureSignature(data))
      setLoad({ kind: 'ready' })
    } catch (error) {
      if (!ticket.isCurrent()) {
        return
      }
      if (error instanceof ApiError && error.status === 404) {
        setLoad({ kind: 'notFound' })
      } else if (error instanceof ApiError && error.status === 400) {
        setLoad({ kind: 'unsupported' })
      } else {
        setLoad({ kind: 'failed', errorKey: apiErrorKey(error) })
      }
    }
  }, [guard, resumeId])

  useEffect(() => {
    void fetchStructure()
  }, [fetchStructure])

  function edit(update: (current: ResumeStructure) => ResumeStructure) {
    setDoc((current) => (current ? update(current) : current))
    setSaved(false)
    setSaveError(null)
    setNameError(null)
  }

  function updateEntry<K extends ListKey>(key: K, index: number, patch: Partial<ResumeStructure[K][number]>) {
    edit((current) => ({
      ...current,
      [key]: (current[key] as ResumeStructure[K]).map((entry, i) => (i === index ? { ...entry, ...patch } : entry)),
    }))
  }

  async function removeEntry(key: ListKey, index: number) {
    const entry = doc?.[key][index]
    if (!entry) {
      return
    }
    if (entryHasContent(entry)) {
      const ok = await confirmAction({
        title: t('resumes.structure.removeTitle'),
        message: t('resumes.structure.removeBody'),
        confirmLabel: t('resumes.structure.removeYes'),
        cancelLabel: t('resumes.structure.cancel'),
        destructive: true,
      })
      if (!ok) {
        return
      }
    }
    edit((current) => ({ ...current, [key]: current[key].filter((_: unknown, i: number) => i !== index) }))
  }

  async function save() {
    if (!doc || resumeId === null || savingRef.current) {
      return
    }
    const problem = validateStructure(doc)
    if (problem) {
      setNameError(t(problem))
      return
    }
    const sent = doc
    const sentSignature = structureSignature(sent)
    const ticket = guard.capture()
    savingRef.current = true
    setSaving(true)
    setSaveError(null)
    try {
      const response = normalizeStructure(await resumesApi.updateStructure(resumeId, sent))
      if (!ticket.isCurrent()) {
        return
      }
      if (ticket.userId !== null) {
        await saveStructureSavedAt(ticket.userId, resumeId, new Date().toISOString()).catch(() => {})
      }
      if (!ticket.isCurrent()) {
        return
      }
      // The PDF goes back to "needs review" on the server; lists and the detail show that.
      void queryClient.invalidateQueries({ queryKey: resumeKeys.files(ticket.userId), exact: true })
      void queryClient.invalidateQueries({ queryKey: resumeKeys.file(ticket.userId, resumeId) })
      setDoc((current) => (current && structureSignature(current) === sentSignature ? response : current))
      setBaseline(structureSignature(response))
      setSaved(true)
    } catch (error) {
      if (ticket.isCurrent()) {
        setSaveError(t('resumes.structure.saveFailed', { reason: t(apiErrorKey(error)) }))
      }
    } finally {
      savingRef.current = false
      if (ticket.isCurrent()) {
        setSaving(false)
      }
    }
  }

  if (load.kind === 'notFound' || load.kind === 'unsupported') {
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={t(load.kind === 'notFound' ? 'resumes.structure.notFoundTitle' : 'resumes.structure.unsupported')}
          actionLabel={t('resumes.detail.back')}
          onAction={onBack}
        />
      </DetailLayout>
    )
  }

  if (load.kind === 'failed') {
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={t('resumes.structure.loadFailedTitle')}
          body={t(load.errorKey)}
          actionLabel={t('resumes.retry')}
          onAction={() => void fetchStructure()}
        />
      </DetailLayout>
    )
  }

  if (load.kind === 'loading' || !doc) {
    return (
      <DetailLayout onBack={onBack}>
        <View aria-busy style={{ gap: 10 }}>
          <Text role="status" style={detailStyles.muted}>
            {t('resumes.structure.recognizing')}
          </Text>
          <Skeleton width="70%" height={24} />
          <Skeleton width="100%" height={160} style={{ marginTop: 12, borderRadius: radius.card }} />
        </View>
      </DetailLayout>
    )
  }

  const savedNow = saved && !dirty

  return (
    <DetailLayout
      onBack={onBack}
      avoidKeyboard
      actions={
        <>
          {saveError ? <Notice tone="error">{saveError}</Notice> : null}
          {savedNow ? <Notice tone="info">{t(fromTailor ? 'resumes.structure.savedTailor' : 'resumes.structure.saved')}</Notice> : null}
          <View style={detailStyles.actionRow}>
            {savedNow && fromTailor && onContinueTailor ? (
              <Button variant="secondary" label={t('resumes.structure.continueTailor')} onPress={onContinueTailor} style={detailStyles.flex} />
            ) : null}
            <Button
              label={saving ? t('resumes.structure.saving') : t('resumes.structure.save')}
              onPress={() => void save()}
              loading={saving}
              disabled={!dirty}
              style={detailStyles.flex}
            />
          </View>
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {t('resumes.structure.title')}
      </Text>
      <Text style={[detailStyles.muted, styles.intro]}>{t('resumes.structure.hint')}</Text>
      <Text style={[detailStyles.hint, styles.intro]}>{t('resumes.structure.pdfUnchanged')}</Text>
      {missingTailorContent(doc) ? (
        <View style={styles.notice}>
          <Notice tone="info">{t('resumes.structure.needsContent')}</Notice>
        </View>
      ) : null}

      <Section title={t('resumes.structure.contact')}>
        <FormInput
          label={t('resumes.structure.fullName')}
          value={doc.contact.fullName}
          error={nameError}
          onChangeText={(value) => edit((current) => ({ ...current, contact: { ...current.contact, fullName: value } }))}
        />
        <FormInput
          label={t('resumes.structure.email')}
          value={doc.contact.email}
          keyboardType="email-address"
          autoCapitalize="none"
          onChangeText={(value) => edit((current) => ({ ...current, contact: { ...current.contact, email: value } }))}
        />
        <FormInput
          label={t('resumes.structure.phone')}
          value={doc.contact.phone}
          keyboardType="phone-pad"
          onChangeText={(value) => edit((current) => ({ ...current, contact: { ...current.contact, phone: value } }))}
        />
        <FormInput
          label={t('resumes.structure.location')}
          value={doc.contact.location}
          onChangeText={(value) => edit((current) => ({ ...current, contact: { ...current.contact, location: value } }))}
        />
        <ListInput
          label={t('resumes.structure.links')}
          hint={t('resumes.structure.onePerLine')}
          value={doc.contact.links}
          onChange={(links) => edit((current) => ({ ...current, contact: { ...current.contact, links } }))}
        />
      </Section>

      <Section title={t('resumes.structure.summary')}>
        <ListInput
          label={t('resumes.structure.summary')}
          hint={t('resumes.structure.onePerLine')}
          value={doc.summaryBullets.map((item) => item.text)}
          onChange={(texts) => edit((current) => withSummaryTexts(current, texts))}
        />
      </Section>

      <Section title={t('resumes.structure.skills')}>
        <ListInput
          label={t('resumes.structure.skills')}
          hint={t('resumes.structure.onePerLine')}
          value={doc.skills.map((item) => item.name)}
          onChange={(names) => edit((current) => withSkillNames(current, names))}
        />
      </Section>

      <Section title={t('resumes.structure.experience')}>
        {doc.experience.map((entry: StructureExperience, index) => (
          <EntryCard
            key={`experience-${index}`}
            heading={[entry.title, entry.company].map((part) => part.trim()).filter(Boolean).join(' · ') || t('resumes.structure.experienceItem', { n: index + 1 })}
            removeLabel={t('resumes.structure.remove')}
            onRemove={() => void removeEntry('experience', index)}
          >
            <FormInput label={t('resumes.structure.company')} value={entry.company} onChangeText={(value) => updateEntry('experience', index, { company: value })} />
            <FormInput label={t('resumes.structure.jobTitle')} value={entry.title} onChangeText={(value) => updateEntry('experience', index, { title: value })} />
            <FormInput label={t('resumes.structure.startDate')} value={entry.startDate} onChangeText={(value) => updateEntry('experience', index, { startDate: value })} />
            <FormInput label={t('resumes.structure.endDate')} value={entry.endDate} onChangeText={(value) => updateEntry('experience', index, { endDate: value })} />
            <FormInput label={t('resumes.structure.location')} value={entry.location} onChangeText={(value) => updateEntry('experience', index, { location: value })} />
            <ListInput
              label={t('resumes.structure.bullets')}
              hint={t('resumes.structure.onePerLine')}
              value={entry.bullets}
              onChange={(bullets) => updateEntry('experience', index, { bullets })}
            />
          </EntryCard>
        ))}
        <Button
          variant="secondary"
          size="sm"
          label={t('resumes.structure.addExperience')}
          onPress={() => edit((current) => ({ ...current, experience: [...current.experience, emptyExperience()] }))}
        />
      </Section>

      <Section title={t('resumes.structure.projects')}>
        {doc.projects.map((entry: StructureProject, index) => (
          <EntryCard
            key={`project-${index}`}
            heading={entry.name.trim() || t('resumes.structure.projectItem', { n: index + 1 })}
            removeLabel={t('resumes.structure.remove')}
            onRemove={() => void removeEntry('projects', index)}
          >
            <FormInput label={t('resumes.structure.projectName')} value={entry.name} onChangeText={(value) => updateEntry('projects', index, { name: value })} />
            <FormInput
              label={t('resumes.structure.organization')}
              value={entry.organizationOrType}
              onChangeText={(value) => updateEntry('projects', index, { organizationOrType: value })}
            />
            <FormInput label={t('resumes.structure.dates')} value={entry.dates} onChangeText={(value) => updateEntry('projects', index, { dates: value })} />
            <ListInput
              label={t('resumes.structure.bullets')}
              hint={t('resumes.structure.onePerLine')}
              value={entry.bullets}
              onChange={(bullets) => updateEntry('projects', index, { bullets })}
            />
            <ListInput
              label={t('resumes.structure.links')}
              hint={t('resumes.structure.onePerLine')}
              value={entry.links}
              onChange={(links) => updateEntry('projects', index, { links })}
            />
          </EntryCard>
        ))}
        <Button
          variant="secondary"
          size="sm"
          label={t('resumes.structure.addProject')}
          onPress={() => edit((current) => ({ ...current, projects: [...current.projects, emptyProject()] }))}
        />
      </Section>

      <Section title={t('resumes.structure.education')}>
        {doc.education.map((entry: StructureEducation, index) => (
          <EntryCard
            key={`education-${index}`}
            heading={entry.school.trim() || t('resumes.structure.educationItem', { n: index + 1 })}
            removeLabel={t('resumes.structure.remove')}
            onRemove={() => void removeEntry('education', index)}
          >
            <FormInput label={t('resumes.structure.school')} value={entry.school} onChangeText={(value) => updateEntry('education', index, { school: value })} />
            <FormInput label={t('resumes.structure.degree')} value={entry.degree} onChangeText={(value) => updateEntry('education', index, { degree: value })} />
            <FormInput label={t('resumes.structure.dates')} value={entry.dates} onChangeText={(value) => updateEntry('education', index, { dates: value })} />
            <FormInput
              label={t('resumes.structure.details')}
              value={entry.details}
              multiline
              onChangeText={(value) => updateEntry('education', index, { details: value })}
            />
          </EntryCard>
        ))}
        <Button
          variant="secondary"
          size="sm"
          label={t('resumes.structure.addEducation')}
          onPress={() => edit((current) => ({ ...current, education: [...current.education, emptyEducation()] }))}
        />
      </Section>

      {doc.unassigned.length > 0 ? (
        <Section title={t('resumes.structure.unassigned')}>
          <Text style={detailStyles.hint}>{t('resumes.structure.unassignedHint')}</Text>
          {doc.unassigned.map((block, index) => (
            <Text key={typeof block.id === 'string' ? block.id : `block-${index}`} style={styles.block}>
              {typeof block.text === 'string' ? block.text : ''}
            </Text>
          ))}
        </Section>
      ) : null}
    </DetailLayout>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <View style={[detailStyles.section, styles.section]}>
      <Text role="heading" style={detailStyles.sectionTitle}>
        {title}
      </Text>
      {children}
    </View>
  )
}

const styles = StyleSheet.create({
  intro: { marginTop: 6 },
  notice: { marginTop: 12 },
  section: { gap: 12 },
  block: { fontSize: 14, lineHeight: 20, color: colors.ink2, padding: 10, borderRadius: radius.chip, backgroundColor: colors.surface },
})
