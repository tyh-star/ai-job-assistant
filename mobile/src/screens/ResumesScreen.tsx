import { useEffect, useRef, useState } from 'react'
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { apiErrorKey, mayBeMissingOnServer, requestDiagnostic, uploadErrorKey } from '../api/errors'
import type { ResumeBuilderDraftSummary, ResumeResponse } from '../api/types'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { FlashBanner, StateView, useFlash } from '../components/feedback'
import { FileIcon } from '../components/Icons'
import { JobCard, JobCardSkeleton, type JobBadge } from '../components/JobCard'
import { SegmentedControl } from '../components/SegmentedControl'
import { displayFileName } from '../resumes/displayName'
import { fileStatus, formatBytes, validatePickedFile, visibleFiles, type ValidFile } from '../resumes/model'
import { pickResumeDocument } from '../resumes/picker'
import { useDrafts, useResumeFiles, useUploadResume } from '../resumes/queries'
import { colors, radius, space } from '../theme'
import { formatMonthDay } from '../utils/dates'

export type ResumesSegment = 'files' | 'drafts'

type Props = {
  segmentParam?: string
  /** `upload` opens the picker once per `nonce`, for the dashboard's upload entry. */
  actionParam?: string
  nonce?: string
  onOpenFile: (resumeId: number) => void
  onOpenDraft: (draftId: number) => void
  onCreateDraft?: () => void
  /** Opens the editor of an online resume directly from the list. */
  onEditDraft?: (draftId: number) => void
}

type Row = { kind: 'file'; file: ResumeResponse } | { kind: 'draft'; draft: ResumeBuilderDraftSummary }

export function ResumesScreen({ segmentParam, actionParam, nonce, onOpenFile, onOpenDraft, onCreateDraft, onEditDraft }: Props) {
  const { t, i18n } = useTranslation()
  const [segment, setSegment] = useState<ResumesSegment>(segmentParam === 'drafts' ? 'drafts' : 'files')
  const [pending, setPending] = useState<ValidFile | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const picking = useRef(false)
  const { flash, show } = useFlash()

  const files = useResumeFiles()
  const drafts = useDrafts()
  const upload = useUploadResume((fraction) => setProgress(fraction))

  useEffect(() => {
    if (segmentParam === 'files' || segmentParam === 'drafts') {
      setSegment(segmentParam)
    }
  }, [segmentParam, nonce])

  useEffect(() => {
    if (actionParam === 'upload') {
      setSegment('files')
      void choose()
    }
    // Run once per dashboard request, identified by its nonce.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionParam, nonce])

  async function choose() {
    if (picking.current || upload.isPending) {
      return
    }
    picking.current = true
    setProblem(null)
    try {
      const picked = await pickResumeDocument()
      if (!picked) {
        return
      }
      const result = validatePickedFile(picked)
      if (result.ok) {
        setPending(result.file)
      } else {
        setPending(null)
        setProblem(t(`resumes.problem.${result.problem}`))
      }
    } catch {
      show('error', t('resumes.pick.pickFailed'))
    } finally {
      picking.current = false
    }
  }

  async function submit() {
    if (!pending || upload.isPending) {
      return
    }
    setProgress(0)
    try {
      const created = await upload.mutateAsync(pending)
      setPending(null)
      setSegment('files')
      show('success', t('resumes.flash.uploaded', { name: displayFileName(created.fileName) }))
    } catch (error) {
      show('error', t('resumes.flash.uploadFailed', { reason: t(uploadErrorKey(error)) }))
    } finally {
      setProgress(null)
    }
  }

  const fileRows = files.data ? visibleFiles(files.data) : []
  const draftRows = drafts.data
    ? drafts.data.slice().sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''))
    : []
  const rows: Row[] =
    segment === 'files'
      ? fileRows.map((file) => ({ kind: 'file', file }))
      : draftRows.map((draft) => ({ kind: 'draft', draft }))
  const active = segment === 'files' ? files : drafts
  const uploading = upload.isPending
  const percent = progress === null ? null : Math.round(progress * 100)

  const header = (
    <View style={styles.header}>
      <View style={styles.titleRow}>
        <Text role="heading" style={styles.title}>
          {t('resumes.title')}
        </Text>
        {segment === 'drafts' && onCreateDraft ? (
          <Button size="sm" label={t('builder.list.create')} onPress={onCreateDraft} />
        ) : (
          <Button size="sm" label={t('resumes.upload')} onPress={() => void choose()} disabled={uploading} />
        )}
      </View>
      <Text style={styles.hint}>{segment === 'drafts' && onCreateDraft ? t('builder.list.hint') : t('resumes.uploadHint')}</Text>
      <Text style={styles.explain}>{t(segment === 'drafts' ? 'resumes.drafts.explain' : 'resumes.files.explain')}</Text>

      {problem ? <Notice tone="error">{problem}</Notice> : null}
      <FlashBanner flash={flash} />

      {pending ? (
        <View style={styles.confirm} aria-label={t('resumes.pick.confirmTitle')}>
          <Text style={styles.confirmTitle}>{t('resumes.pick.confirmTitle')}</Text>
          <View style={styles.confirmFile}>
            <View style={styles.tile}>
              <FileIcon />
            </View>
            <View style={styles.flex}>
              <Text style={styles.fileName} numberOfLines={2}>
                {pending.name}
              </Text>
              <Text style={styles.fileMeta}>
                {t('resumes.pick.type')} {pending.kind.toUpperCase()} · {t('resumes.pick.size')} {formatBytes(pending.size)}
              </Text>
            </View>
          </View>
          {uploading ? (
            <View style={styles.progressBlock} aria-busy>
              <View style={styles.progressTrack}>
                <View style={[styles.progressFill, { width: `${percent ?? 0}%` }]} />
              </View>
              <Text style={styles.progressText}>
                {percent === null || percent === 0
                  ? t('resumes.pick.uploadingNoProgress')
                  : t('resumes.pick.uploading', { percent })}
              </Text>
            </View>
          ) : null}
          <View style={styles.confirmButtons}>
            <Button
              variant="secondary"
              size="sm"
              label={t('resumes.pick.cancel')}
              onPress={() => setPending(null)}
              disabled={uploading}
              style={styles.flex}
            />
            <Button
              size="sm"
              label={t('resumes.pick.submit')}
              onPress={() => void submit()}
              loading={uploading}
              style={styles.flex}
            />
          </View>
        </View>
      ) : null}

      <SegmentedControl
        label={t('resumes.segments.label')}
        value={segment}
        onChange={setSegment}
        options={[
          { value: 'files', label: t('resumes.segments.files') },
          { value: 'drafts', label: t('resumes.segments.drafts') },
        ]}
      />
      {active.isRefetchError && active.data ? (
        <Text style={styles.inlineError}>{t('jobs.results.refreshFailed')}</Text>
      ) : null}
    </View>
  )

  let empty: React.ReactElement
  if (active.isPending) {
    empty = (
      <View style={styles.skeletons} aria-busy>
        {[0, 1, 2].map((key) => (
          <JobCardSkeleton key={key} />
        ))}
      </View>
    )
  } else if (active.isError && !active.data) {
    const diagnostic = requestDiagnostic(active.error)
    const serverHint = segment === 'drafts' && mayBeMissingOnServer(active.error) ? t('resumes.drafts.serverHint') : null
    empty = (
      <StateView
        tone="error"
        title={t(segment === 'files' ? 'resumes.files.errorTitle' : 'resumes.drafts.errorTitle')}
        body={[t(apiErrorKey(active.error)), serverHint, diagnostic].filter(Boolean).join('\n')}
        actionLabel={t('resumes.retry')}
        onAction={() => void active.refetch()}
      />
    )
  } else if (segment === 'files') {
    empty = (
      <StateView
        tone="empty"
        icon={<FileIcon size={22} />}
        title={t('resumes.files.emptyTitle')}
        body={t('resumes.files.emptyBody')}
        actionLabel={t('resumes.upload')}
        onAction={() => void choose()}
      />
    )
  } else {
    empty = (
      <StateView
        tone="empty"
        icon={<FileIcon size={22} />}
        title={t('resumes.drafts.emptyTitle')}
        body={t('resumes.drafts.emptyBody')}
        actionLabel={onCreateDraft ? t('builder.list.create') : undefined}
        onAction={onCreateDraft}
      />
    )
  }

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <FlatList
        data={rows}
        keyExtractor={(row) => (row.kind === 'file' ? `f:${row.file.id}` : `d:${row.draft.id}`)}
        contentContainerStyle={styles.content}
        ItemSeparatorComponent={Separator}
        ListHeaderComponent={header}
        ListEmptyComponent={empty}
        refreshControl={
          <RefreshControl
            refreshing={active.isRefetching}
            onRefresh={() => void active.refetch()}
            tintColor={colors.blue}
            colors={[colors.blue]}
          />
        }
        renderItem={({ item }) =>
          item.kind === 'file' ? (
            <JobCard
              title={displayFileName(item.file.fileName)}
              company={item.file.fileType?.toUpperCase() ?? ''}
              dateLabel={fileDate(t, i18n.language, item.file.updatedAt)}
              badges={fileBadges(t, item.file)}
              onPress={() => onOpenFile(item.file.id)}
            />
          ) : (
            <View style={styles.draftRow}>
              <JobCard
                title={item.draft.title?.trim() || t('resumes.drafts.untitled')}
                company={t(`resumes.templates.${item.draft.templateType}`, { defaultValue: String(item.draft.templateType) })}
                location={item.draft.targetRole?.trim() || t('resumes.drafts.noTargetRole')}
                dateLabel={fileDate(t, i18n.language, item.draft.updatedAt)}
                badges={[{ label: t('resumes.segments.drafts'), tone: 'muted' }]}
                onPress={() => onOpenDraft(item.draft.id)}
              />
              {onEditDraft ? (
                <Button
                  size="sm"
                  variant="secondary"
                  label={t('dashboard.editResume')}
                  onPress={() => onEditDraft(item.draft.id)}
                />
              ) : null}
            </View>
          )
        }
      />
    </SafeAreaView>
  )
}

function fileDate(t: TFunction, language: string, iso: string): string | null {
  const date = formatMonthDay(iso, language)
  return date ? t('resumes.files.updatedOn', { date }) : null
}

function fileBadges(t: TFunction, file: ResumeResponse): JobBadge[] {
  const badges: JobBadge[] = []
  if (file.origin === 'UPLOAD' || file.origin === 'GENERATED') {
    badges.push({ label: t(`resumes.files.origin.${file.origin}`), tone: 'saved' })
  }
  const status = fileStatus(file)
  badges.push({ label: t(`resumes.files.status.${status}`), tone: status === 'ready' ? 'applied' : 'muted' })
  return badges
}

function Separator() {
  return <View style={styles.separator} />
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { paddingHorizontal: space.screenX, paddingBottom: 28 },
  flex: { flex: 1 },
  header: { paddingTop: 10, paddingBottom: 14, gap: 12 },
  titleRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { fontSize: 28, fontWeight: '700', color: colors.ink, letterSpacing: -0.6 },
  hint: { marginTop: -6, fontSize: 13, color: colors.muted },
  explain: { marginTop: -6, fontSize: 13, lineHeight: 18, color: colors.ink2 },
  draftRow: { gap: 8 },
  confirm: {
    gap: 12,
    padding: 14,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
  },
  confirmTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  confirmFile: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  tile: { width: 40, height: 40, borderRadius: radius.tile, backgroundColor: colors.tile, alignItems: 'center', justifyContent: 'center' },
  fileName: { fontSize: 15, fontWeight: '600', color: colors.ink },
  fileMeta: { marginTop: 2, fontSize: 13, color: colors.muted },
  progressBlock: { gap: 6 },
  progressTrack: { height: 6, borderRadius: 3, backgroundColor: '#DCE8F7', overflow: 'hidden' },
  progressFill: { height: 6, borderRadius: 3, backgroundColor: colors.blue },
  progressText: { fontSize: 13, fontWeight: '600', color: colors.blueStrong },
  confirmButtons: { flexDirection: 'row', gap: 10 },
  inlineError: { fontSize: 13, color: colors.danger },
  separator: { height: 10 },
  skeletons: { gap: 10 },
})
