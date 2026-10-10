import { useEffect, useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { apiErrorKey } from '../api/errors'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { DetailLayout, detailStyles } from '../components/DetailLayout'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import type { LeaveReason } from '../builder/leaveGuard'
import { useSessionGuard } from '../builder/sessionGuard'
import { useMyJobs } from '../jobs/queries'
import { displayFileName } from '../resumes/displayName'
import { ShareUnavailableError } from '../resumes/files'
import { visibleFiles } from '../resumes/model'
import { useResumeFiles } from '../resumes/queries'
import { EnhancedActions, EnhancedFlowView } from '../tailor/enhanced/EnhancedFlow'
import { useEnhancedTailor } from '../tailor/enhanced/useEnhancedTailor'
import { downloadAndShareTailored, tailoredFileFromUrl, tailoredShareName, type TailoredFile } from '../tailor/files'
import { formatLocalDateTime, isProposalExpired, jobHasDescription, resumeEligibility } from '../tailor/model'
import { loadPendingProposal } from '../tailor/pendingProposal'
import { clearKnownResult, loadEnhancedPending, loadKnownResult, type KnownResult, type ProductMode } from '../tailor/records'
import { Badge, ChangeCard, RejectedList, ResultView, tailorStyles } from '../tailor/resultViews'
import type { TailorMode, TailoredResumeResponse } from '../tailor/types'
import { useTailorRun, type TailorPhase } from '../tailor/useTailorRun'
import { colors, radius } from '../theme'

type Props = {
  initialJobId?: number | null
  initialResumeId?: number | null
  /** Omitted when the screen is the 改写 bottom tab. */
  onBack?: () => void
  onOpenJobs: () => void
  onOpenResumes: () => void
  onLeaveReasonChange?: (reason: LeaveReason | null) => void
  initialMode?: ProductMode | null
  /** Opens the PDF's recognized-content screen, keeping this job, resume and mode to come back to. */
  onOpenStructure?: (resumeId: number, jobId: number | null, mode: ProductMode) => void
}

const MODES: ProductMode[] = ['CONSERVATIVE', 'AGGRESSIVE', 'ENHANCED']
const INCOMPLETE_RECOGNITION = 'tailor.errors.incompleteRecognition'

export function TailorScreen({
  initialJobId = null,
  initialResumeId = null,
  initialMode = null,
  onBack,
  onOpenJobs,
  onOpenResumes,
  onLeaveReasonChange,
  onOpenStructure,
}: Props) {
  const { t, i18n } = useTranslation()
  const jobs = useMyJobs()
  const files = useResumeFiles()
  const tailor = useTailorRun()
  const enhanced = useEnhancedTailor()
  const guard = useSessionGuard()
  const { phase } = tailor
  const enhancedStage = enhanced.state.stage
  const [jobId, setJobId] = useState<number | null>(initialJobId)
  const [resumeId, setResumeId] = useState<number | null>(initialResumeId)
  const [mode, setMode] = useState<ProductMode>(initialMode ?? 'CONSERVATIVE')
  const [known, setKnown] = useState<KnownResult | null>(null)
  const { flash, show } = useFlash()
  const share = useMutation({
    mutationFn: ({ file, name }: { file: TailoredFile; name: string }) => downloadAndShareTailored(file, name),
  })

  const busy = tailor.busy || enhanced.busy
  useEffect(() => {
    onLeaveReasonChange?.(busy ? 'busy' : enhanced.hasUnsaved ? 'unsaved' : null)
  }, [busy, enhanced.hasUnsaved, onLeaveReasonChange])

  // Pending proposals are kept per job and product mode; open the mode that has one.
  useEffect(() => {
    if (jobId === null) {
      return
    }
    const ticket = guard.capture()
    const userId = ticket.userId
    if (userId === null) {
      return
    }
    void Promise.all([loadEnhancedPending(userId, jobId).catch(() => null), loadPendingProposal(userId, jobId).catch(() => null)]).then(
      ([enhancedRecord, aggressiveRecord]) => {
        if (!ticket.isCurrent()) {
          return
        }
        if (enhancedRecord) {
          setMode('ENHANCED')
        } else if (aggressiveRecord) {
          setMode('AGGRESSIVE')
        }
      },
    )
  }, [jobId, guard])

  useEffect(() => {
    if (jobId === null) {
      return
    }
    if (mode === 'AGGRESSIVE') {
      void tailor.restore(jobId)
    } else if (mode === 'ENHANCED') {
      void enhanced.restore(jobId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId, mode])

  useEffect(() => {
    if (phase.kind === 'preview' || (phase.kind === 'unknown' && phase.stage === 'confirm')) {
      setResumeId(phase.record.resumeId)
      setMode('AGGRESSIVE')
    }
  }, [phase])

  useEffect(() => {
    if (enhancedStage !== 'idle' && enhanced.state.record) {
      setResumeId(enhanced.state.record.resumeId)
      setMode('ENHANCED')
    }
  }, [enhancedStage, enhanced.state.record])

  const idleAll = phase.kind === 'idle' && enhancedStage === 'idle'
  useEffect(() => {
    setKnown(null)
    if (jobId === null || !idleAll) {
      return
    }
    const ticket = guard.capture()
    const userId = ticket.userId
    if (userId === null) {
      return
    }
    void loadKnownResult(userId, jobId, mode)
      .catch(() => null)
      .then((record) => {
        if (ticket.isCurrent()) {
          setKnown(record)
        }
      })
  }, [jobId, mode, idleAll, guard])

  const jobList = jobs.data ?? []
  const resumeList = files.data ? visibleFiles(files.data) : []
  const job = jobList.find((item) => item.id === jobId) ?? null
  const resume = resumeList.find((item) => item.id === resumeId) ?? null
  const selectable =
    (phase.kind === 'idle' || phase.kind === 'failed' || phase.kind === 'gone') &&
    (enhancedStage === 'idle' || enhancedStage === 'scanFailed' || enhancedStage === 'gone')
  const canRun = Boolean(job && resume && jobHasDescription(job) && resumeEligibility(resume) === 'ok')
  const needsStructure =
    Boolean(resume && (resume.fileType ?? '').toLowerCase() === 'pdf') &&
    (mode === 'ENHANCED'
      ? enhancedStage === 'scanFailed' && enhanced.state.error?.reason === INCOMPLETE_RECOGNITION
      : phase.kind === 'failed' && phase.stage === 'prepare' && phase.errorKey === INCOMPLETE_RECOGNITION)

  async function onShare(forJobId: number, url: string | null, forResumeId: number | null) {
    const file = tailoredFileFromUrl(url, forJobId)
    if (!file) {
      show('error', t('tailor.flash.downloadFailed', { reason: t('tailor.errors.badFileUrl') }))
      return
    }
    const fileResume = resumeList.find((item) => item.id === forResumeId) ?? resume
    const fileJob = jobList.find((item) => item.id === forJobId) ?? job
    const name = tailoredShareName(fileResume?.fileName ?? '', fileJob?.title ?? '', file.fileType)
    try {
      await share.mutateAsync({ file, name })
      show('success', t('tailor.flash.shared'))
    } catch (error) {
      const reason = error instanceof ShareUnavailableError ? t('resumes.errors.shareUnavailable') : t(apiErrorKey(error))
      show('error', t('tailor.flash.downloadFailed', { reason }))
    }
  }

  async function onRun() {
    if (!job || !resume || !canRun || mode === 'ENHANCED') {
      return
    }
    if (phase.kind === 'unknown' && phase.stage === 'run' && phase.mode === 'CONSERVATIVE') {
      const ok = await confirmAction({
        title: t('tailor.confirmRetry.title'),
        message: t('tailor.confirmRetry.body'),
        confirmLabel: t('tailor.actions.retryRun'),
        cancelLabel: t('tailor.confirmDiscard.no'),
      })
      if (!ok) {
        return
      }
    }
    await tailor.run({ job, resume, mode })
  }

  async function onDiscard() {
    const ok = await confirmAction({
      title: t('tailor.confirmDiscard.title'),
      message: t('tailor.confirmDiscard.body'),
      confirmLabel: t('tailor.confirmDiscard.yes'),
      cancelLabel: t('tailor.confirmDiscard.no'),
      destructive: true,
    })
    if (ok) {
      await tailor.discard()
    }
  }

  async function onRemoveKnown(record: KnownResult) {
    const ticket = guard.capture()
    if (ticket.userId !== null) {
      await clearKnownResult(ticket.userId, record.jobId, record.mode).catch(() => {})
    }
    if (ticket.isCurrent()) {
      setKnown(null)
    }
  }

  return (
    <DetailLayout
      onBack={onBack}
      avoidKeyboard
      actions={
        <>
          <FlashBanner flash={flash} />
          {mode === 'ENHANCED' ? (
            <EnhancedActions
              controller={enhanced}
              canRun={canRun}
              sharing={share.isPending}
              onStart={() => {
                if (job && resume && canRun) {
                  void enhanced.start({ job, resume })
                }
              }}
              onRetryRestore={() => {
                if (jobId !== null) {
                  void enhanced.restore(jobId)
                }
              }}
              onShare={(result, url) => void onShare(result.jobId, url, enhanced.state.resumeId)}
            />
          ) : (
            <Actions
              phase={phase}
              mode={mode}
              canRun={canRun}
              sharing={share.isPending}
              onRun={() => void onRun()}
              onConfirm={() => void tailor.confirm()}
              onDiscard={() => void onDiscard()}
              onCheck={() => void tailor.check()}
              onRetryRestore={() => jobId !== null && void tailor.restore(jobId)}
              onReset={tailor.reset}
              onShare={(result, url) => void onShare(result.jobId, url, resume?.id ?? null)}
            />
          )}
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {t('tailor.title')}
      </Text>
      <Text style={[detailStyles.muted, { marginTop: 6 }]}>{t('tailor.intro')}</Text>

      <Section title={t('tailor.steps.job')}>
        {jobs.isPending ? (
          <Skeleton width="100%" height={56} style={{ borderRadius: radius.tile }} />
        ) : jobs.isError && !jobs.data ? (
          <StateView tone="error" title={t('tailor.jobs.loadFailed')} body={t(apiErrorKey(jobs.error))} actionLabel={t('jobs.retry')} onAction={() => void jobs.refetch()} />
        ) : jobList.length === 0 ? (
          <StateView tone="empty" title={t('tailor.jobs.empty')} actionLabel={t('tailor.jobs.action')} onAction={onOpenJobs} />
        ) : (
          <View role="radiogroup" aria-label={t('tailor.steps.job')} style={styles.options}>
            {jobList.map((item) => (
              <OptionRow
                key={item.id}
                label={item.title}
                detail={item.company}
                note={jobHasDescription(item) ? null : t('tailor.jobs.noDescription')}
                selected={item.id === jobId}
                disabled={!selectable || !jobHasDescription(item)}
                onPress={() => setJobId(item.id)}
              />
            ))}
          </View>
        )}
      </Section>

      <Section title={t('tailor.steps.resume')}>
        {files.isPending ? (
          <Skeleton width="100%" height={56} style={{ borderRadius: radius.tile }} />
        ) : files.isError && !files.data ? (
          <StateView tone="error" title={t('tailor.resumes.loadFailed')} body={t(apiErrorKey(files.error))} actionLabel={t('resumes.retry')} onAction={() => void files.refetch()} />
        ) : resumeList.length === 0 ? (
          <StateView tone="empty" title={t('tailor.resumes.empty')} actionLabel={t('tailor.resumes.action')} onAction={onOpenResumes} />
        ) : (
          <View role="radiogroup" aria-label={t('tailor.steps.resume')} style={styles.options}>
            {resumeList.map((item) => {
              const eligibility = resumeEligibility(item)
              return (
                <OptionRow
                  key={item.id}
                  label={displayFileName(item.fileName)}
                  detail={(item.fileType ?? '').toUpperCase()}
                  note={eligibility === 'ok' ? null : t(`tailor.resumes.${eligibility}`)}
                  selected={item.id === resumeId}
                  disabled={!selectable || eligibility !== 'ok'}
                  onPress={() => setResumeId(item.id)}
                />
              )
            })}
          </View>
        )}
        {resume && (resume.fileType ?? '').toLowerCase() === 'pdf' ? <Text style={detailStyles.hint}>{t('tailor.resumes.pdfNote')}</Text> : null}
      </Section>

      <Section title={t('tailor.steps.mode')}>
        <View role="radiogroup" aria-label={t('tailor.steps.mode')} style={styles.options}>
          {MODES.map((item) => (
            <OptionRow
              key={item}
              label={t(`tailor.modes.${item}.title`)}
              detail={t(`tailor.modes.${item}.body`)}
              selected={item === mode}
              disabled={!selectable}
              onPress={() => setMode(item)}
            />
          ))}
        </View>
      </Section>

      {known && idleAll ? (
        <KnownResultCard
          known={known}
          sharing={share.isPending}
          onShare={(url) => void onShare(known.jobId, url, known.resumeId)}
          onRemove={() => void onRemoveKnown(known)}
        />
      ) : null}

      {mode === 'ENHANCED' ? <EnhancedFlowView controller={enhanced} /> : <PhaseView phase={phase} language={i18n.language} t={t} />}

      {needsStructure && resume && onOpenStructure ? (
        <View style={tailorStyles.panel}>
          <Text style={tailorStyles.panelBody}>{t('tailor.structure.needed')}</Text>
          <Button variant="secondary" label={t('tailor.structure.open')} onPress={() => onOpenStructure(resume.id, jobId, mode)} />
        </View>
      ) : null}
    </DetailLayout>
  )
}

function KnownResultCard({
  known,
  sharing,
  onShare,
  onRemove,
}: {
  known: KnownResult
  sharing: boolean
  onShare: (url: string) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const time = formatLocalDateTime(known.savedAt)
  return (
    <View style={tailorStyles.panel}>
      <Text role="heading" style={tailorStyles.panelTitle}>
        {t('tailor.known.title')}
      </Text>
      <Text style={tailorStyles.hint}>{t('tailor.known.note')}</Text>
      {time ? <Text style={tailorStyles.hint}>{t('tailor.known.savedAt', { time })}</Text> : null}
      <View style={tailorStyles.row}>
        <Badge text={t('tailor.result.appliedCount', { count: known.appliedChangeCount })} />
        <Badge text={t('tailor.result.rejectedCount', { count: known.rejectedChangeCount })} />
      </View>
      <View style={detailStyles.actionRow}>
        <Button size="sm" label={t('tailor.actions.downloadDocx')} loading={sharing} onPress={() => onShare(known.downloadUrl)} style={detailStyles.flex} />
        {known.pdfDownloadUrl ? (
          <Button
            variant="secondary"
            size="sm"
            label={t('tailor.actions.downloadPdf')}
            disabled={sharing}
            onPress={() => onShare(known.pdfDownloadUrl as string)}
            style={detailStyles.flex}
          />
        ) : null}
      </View>
      <Button variant="secondary" size="sm" label={t('tailor.known.remove')} disabled={sharing} onPress={onRemove} />
    </View>
  )
}

function Actions({
  phase,
  mode,
  canRun,
  sharing,
  onRun,
  onConfirm,
  onDiscard,
  onCheck,
  onRetryRestore,
  onReset,
  onShare,
}: {
  phase: TailorPhase
  mode: TailorMode
  canRun: boolean
  sharing: boolean
  onRun: () => void
  onConfirm: () => void
  onDiscard: () => void
  onCheck: () => void
  onRetryRestore: () => void
  onReset: () => void
  onShare: (result: TailoredResumeResponse, url: string | null) => void
}) {
  const { t } = useTranslation()
  const runLabel = mode === 'AGGRESSIVE' ? t('tailor.actions.runAggressive') : t('tailor.actions.runConservative')

  switch (phase.kind) {
    case 'running':
      return <Button label={t(`tailor.progress.${phase.step}`)} onPress={() => {}} loading />
    case 'restoring':
    case 'checking':
      return <Button label={t('tailor.actions.checking')} onPress={() => {}} loading />
    case 'confirming':
      return <Button label={t('tailor.actions.confirming')} onPress={() => {}} loading />
    case 'preview':
    case 'confirmFailed': {
      const expired = isProposalExpired(phase.record.expiresAt)
      const empty = (phase.proposal.changes ?? []).length === 0
      return (
        <View style={detailStyles.actionRow}>
          <Button variant="secondary" label={t('tailor.actions.discard')} onPress={onDiscard} style={detailStyles.flex} />
          {!empty ? (
            <Button label={t('tailor.actions.confirm')} onPress={onConfirm} disabled={expired || phase.outdated === true} style={detailStyles.flex} />
          ) : null}
        </View>
      )
    }
    case 'unknown':
      return phase.stage === 'confirm' ? (
        <Button label={t('tailor.actions.check')} onPress={onCheck} />
      ) : (
        <View style={detailStyles.actionRow}>
          <Button variant="secondary" label={t('tailor.actions.again')} onPress={onReset} style={detailStyles.flex} />
          <Button
            label={phase.mode === 'AGGRESSIVE' ? t('tailor.actions.regenerate') : t('tailor.actions.retryRun')}
            onPress={onRun}
            disabled={!canRun}
            style={detailStyles.flex}
          />
        </View>
      )
    case 'restoreFailed':
      return (
        <View style={detailStyles.actionRow}>
          <Button variant="secondary" label={t('tailor.actions.discard')} onPress={onDiscard} style={detailStyles.flex} />
          <Button label={t('tailor.actions.retryRestore')} onPress={onRetryRestore} style={detailStyles.flex} />
        </View>
      )
    case 'done': {
      const pdfReady = phase.result.pdfExportStatus === 'SUCCESS' && Boolean(phase.result.pdfDownloadUrl)
      return (
        <>
          <View style={detailStyles.actionRow}>
            <Button
              label={sharing ? t('tailor.actions.downloading') : t('tailor.actions.downloadDocx')}
              loading={sharing}
              onPress={() => onShare(phase.result, phase.result.downloadUrl)}
              style={detailStyles.flex}
            />
            {pdfReady ? (
              <Button
                variant="secondary"
                label={t('tailor.actions.downloadPdf')}
                disabled={sharing}
                onPress={() => onShare(phase.result, phase.result.pdfDownloadUrl)}
                style={detailStyles.flex}
              />
            ) : null}
          </View>
          <Button variant="secondary" size="sm" label={t('tailor.actions.again')} onPress={onReset} disabled={sharing} />
        </>
      )
    }
    default:
      return <Button label={runLabel} onPress={onRun} disabled={!canRun} />
  }
}

function PhaseView({ phase, language, t }: { phase: TailorPhase; language: string; t: TFunction }) {
  switch (phase.kind) {
    case 'running':
      return (
        <View role="status" aria-busy style={tailorStyles.panel}>
          <Text style={tailorStyles.panelBody}>{t(`tailor.progress.${phase.step}`)}</Text>
        </View>
      )
    case 'failed':
      return <Notice tone="error">{t('tailor.failed', { reason: t(phase.errorKey) })}</Notice>
    case 'unknown':
      if (phase.stage === 'run') {
        return <Notice tone="error">{t(`tailor.unknown.run${phase.mode}`, { reason: t(phase.errorKey) })}</Notice>
      }
      return (
        <View style={tailorStyles.block}>
          <Notice tone="error">
            {phase.errorKey === 'tailor.errors.confirmInProgress'
              ? t('tailor.unknown.stillConfirming')
              : t('tailor.unknown.confirm', { reason: t(phase.errorKey ?? 'errors.network') })}
          </Notice>
          {phase.proposal ? <ProposalView proposal={phase.proposal} expiresAt={phase.record.expiresAt} language={language} t={t} /> : null}
        </View>
      )
    case 'gone':
      return <Notice tone="error">{t(`tailor.gone.${phase.reason}`)}</Notice>
    case 'restoreFailed':
      return <Notice tone="error">{t('tailor.restoreFailed', { reason: t(phase.errorKey) })}</Notice>
    case 'preview':
    case 'confirming':
    case 'checking':
    case 'confirmFailed':
      return (
        <View style={tailorStyles.block}>
          {phase.kind === 'preview' && phase.notice ? <Notice tone="info">{t(`tailor.preview.${phase.notice}`)}</Notice> : null}
          {(phase.kind === 'preview' || phase.kind === 'confirmFailed') && phase.outdated ? (
            <Notice tone="error">{t('tailor.structure.outdated')}</Notice>
          ) : null}
          {phase.kind === 'confirmFailed' ? <Notice tone="error">{t('tailor.confirmFailed', { reason: t(phase.errorKey) })}</Notice> : null}
          {phase.proposal ? <ProposalView proposal={phase.proposal} expiresAt={phase.record.expiresAt} language={language} t={t} /> : null}
        </View>
      )
    case 'done':
      return <ResultView result={phase.result} language={language} t={t} />
    default:
      return null
  }
}

function ProposalView({
  proposal,
  expiresAt,
  language,
  t,
}: {
  proposal: TailoredResumeResponse
  expiresAt: string | null
  language: string
  t: TFunction
}) {
  const changes = proposal.changes ?? []
  const expired = isProposalExpired(expiresAt)
  const time = formatLocalDateTime(expiresAt)
  return (
    <View style={tailorStyles.panel}>
      <Text role="heading" style={tailorStyles.panelTitle}>
        {t('tailor.preview.title')}
      </Text>
      <Text style={tailorStyles.panelBody}>{t('tailor.preview.filesAfterConfirm')}</Text>
      {expired ? (
        <Notice tone="error">{t('tailor.preview.expired')}</Notice>
      ) : time ? (
        <Text style={tailorStyles.hint}>{t('tailor.preview.expiresAt', { time })}</Text>
      ) : null}
      {changes.length === 0 ? <Text style={tailorStyles.panelBody}>{t('tailor.preview.noChanges')}</Text> : null}
      {changes.map((change) => (
        <ChangeCard key={change.nodeId} change={change} language={language} t={t} showApplied={false} />
      ))}
      <RejectedList result={proposal} t={t} />
    </View>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={detailStyles.section}>
      <Text role="heading" style={detailStyles.sectionTitle}>
        {title}
      </Text>
      {children}
    </View>
  )
}

function OptionRow({
  label,
  detail,
  note,
  selected,
  disabled,
  onPress,
}: {
  label: string
  detail?: string | null
  note?: string | null
  selected: boolean
  disabled: boolean
  onPress: () => void
}) {
  return (
    <Pressable
      role="radio"
      aria-label={label}
      aria-checked={selected}
      aria-disabled={disabled}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [styles.option, selected && styles.optionSelected, disabled && !selected && styles.optionDisabled, pressed && { opacity: 0.7 }]}
    >
      <View style={[styles.radio, selected && styles.radioOn]}>{selected ? <View style={styles.radioDot} /> : null}</View>
      <View style={detailStyles.flex}>
        <Text style={styles.optionLabel} numberOfLines={2}>
          {label}
        </Text>
        {detail ? <Text style={styles.optionDetail}>{detail}</Text> : null}
        {note ? <Text style={styles.optionNote}>{note}</Text> : null}
      </View>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  options: { gap: 8 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 52,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: radius.tile,
    borderWidth: 1,
    borderColor: colors.hairline,
    backgroundColor: colors.card,
  },
  optionSelected: { borderColor: colors.blue, backgroundColor: colors.surface },
  optionDisabled: { opacity: 0.55 },
  optionLabel: { fontSize: 15, fontWeight: '600', color: colors.ink },
  optionDetail: { marginTop: 2, fontSize: 13, color: colors.muted },
  optionNote: { marginTop: 2, fontSize: 12, color: colors.danger },
  radio: { width: 20, height: 20, borderRadius: 10, borderWidth: 2, borderColor: colors.inputBorder, alignItems: 'center', justifyContent: 'center' },
  radioOn: { borderColor: colors.blue },
  radioDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: colors.blue },
})
