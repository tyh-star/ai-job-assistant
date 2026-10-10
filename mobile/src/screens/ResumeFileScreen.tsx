import { Text, View } from 'react-native'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { apiErrorKey, isNotFound } from '../api/errors'
import type { ResumeResponse } from '../api/types'
import { Button } from '../components/Button'
import { DetailLayout, InfoRow, detailStyles } from '../components/DetailLayout'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import { displayFileName } from '../resumes/displayName'
import { ShareUnavailableError, downloadAndShare } from '../resumes/files'
import { deletesFamily, fileStatus } from '../resumes/model'
import { useDeleteResume, useResumeFile } from '../resumes/queries'
import { colors, radius } from '../theme'
import { formatFullDate } from '../utils/dates'
import { Badges } from './CatalogJobDetailScreen'

type Props = {
  /** A file id from GET /resumes; null when the route value was not a valid id. */
  resumeId: number | null
  onBack: () => void
  onOpenQuality: (resumeId: number) => void
  onDeleted: () => void
  /** Opens the recognized-content screen; only PDFs have one. */
  onOpenStructure?: (resumeId: number) => void
}

export function ResumeFileScreen(props: Props) {
  const { t } = useTranslation()
  if (props.resumeId === null) {
    return (
      <DetailLayout onBack={props.onBack}>
        <StateView
          tone="error"
          title={t('resumes.detail.notFoundTitle')}
          body={t('resumes.detail.notFoundBody')}
          actionLabel={t('resumes.detail.back')}
          onAction={props.onBack}
        />
      </DetailLayout>
    )
  }
  return <ResumeFileDetail {...props} resumeId={props.resumeId} />
}

function ResumeFileDetail({ resumeId, onBack, onOpenQuality, onDeleted, onOpenStructure }: Props & { resumeId: number }) {
  const { t, i18n } = useTranslation()
  const query = useResumeFile(resumeId)
  const remove = useDeleteResume()
  const share = useMutation({
    mutationFn: (file: Pick<ResumeResponse, 'id' | 'fileName' | 'fileType'>) => downloadAndShare(file),
  })
  const { flash, show } = useFlash()

  if (query.isPending) {
    return (
      <DetailLayout onBack={onBack}>
        <View aria-busy style={{ gap: 10 }}>
          <Skeleton width="80%" height={24} />
          <Skeleton width="100%" height={140} style={{ marginTop: 16, borderRadius: radius.card }} />
        </View>
      </DetailLayout>
    )
  }

  if (query.isError && !query.data) {
    const missing = isNotFound(query.error)
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={missing ? t('resumes.detail.notFoundTitle') : t('resumes.detail.loadFailedTitle')}
          body={missing ? t('resumes.detail.notFoundBody') : t(apiErrorKey(query.error))}
          actionLabel={missing ? t('resumes.detail.back') : t('resumes.retry')}
          onAction={missing ? onBack : () => void query.refetch()}
        />
      </DetailLayout>
    )
  }

  const resume = query.data
  const status = fileStatus(resume)
  const busy = remove.isPending || share.isPending

  async function onShare() {
    try {
      await share.mutateAsync(resume)
      show('success', t('resumes.flash.shared'))
    } catch (error) {
      const reason =
        error instanceof ShareUnavailableError ? t('resumes.errors.shareUnavailable') : t(apiErrorKey(error))
      show('error', t('resumes.flash.downloadFailed', { reason }))
    }
  }

  async function onDelete() {
    const ok = await confirmAction({
      title: t('resumes.confirm.deleteTitle'),
      message: deletesFamily(resume) ? t('resumes.confirm.deleteFamilyBody') : t('resumes.confirm.deleteBody'),
      confirmLabel: t('resumes.confirm.deleteYes'),
      cancelLabel: t('resumes.confirm.cancel'),
      destructive: true,
    })
    if (!ok) {
      return
    }
    try {
      await remove.mutateAsync(resume)
      onDeleted()
    } catch (error) {
      show('error', t('resumes.flash.deleteFailed', { reason: t(apiErrorKey(error)) }))
    }
  }

  return (
    <DetailLayout
      onBack={onBack}
      actions={
        <>
          <FlashBanner flash={flash} />
          <View style={detailStyles.actionRow}>
            <Button
              variant="secondary"
              label={share.isPending ? t('resumes.detail.downloading') : t('resumes.detail.download')}
              onPress={() => void onShare()}
              disabled={busy || !resume.fileAvailable}
              loading={share.isPending}
              style={detailStyles.flex}
            />
            <Button label={t('resumes.detail.quality')} onPress={() => onOpenQuality(resume.id)} disabled={busy} style={detailStyles.flex} />
          </View>
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {displayFileName(resume.fileName)}
      </Text>
      <Badges
        labels={[
          ...(resume.origin === 'UPLOAD' || resume.origin === 'GENERATED'
            ? [{ label: t(`resumes.files.origin.${resume.origin}`), tone: 'saved' as const }]
            : []),
          { label: t(`resumes.files.status.${status}`), tone: status === 'ready' ? ('applied' as const) : ('muted' as const) },
        ]}
      />

      <View style={detailStyles.section}>
        <InfoRow label={t('resumes.detail.fileType')} value={resume.fileType?.toUpperCase()} />
        <InfoRow label={t('resumes.detail.createdAt')} value={formatFullDate(resume.createdAt, i18n.language) || null} />
        <InfoRow label={t('resumes.detail.updatedAt')} value={formatFullDate(resume.updatedAt, i18n.language) || null} />
        {!resume.fileAvailable ? <Text style={[detailStyles.muted, { marginTop: 10 }]}>{t('resumes.detail.unavailableHint')}</Text> : null}
      </View>

      <Text style={[detailStyles.muted, { marginTop: 12 }]}>{t('resumes.detail.fileHint')}</Text>

      {onOpenStructure && resume.fileAvailable && (resume.fileType ?? '').toLowerCase() === 'pdf' ? (
        <View style={{ marginTop: 16, gap: 6 }}>
          <Button
            variant="secondary"
            label={t('resumes.detail.confirmStructure')}
            onPress={() => onOpenStructure(resume.id)}
            disabled={busy}
          />
          <Text style={detailStyles.hint}>{t('resumes.detail.structureHint')}</Text>
        </View>
      ) : null}

      <Button
        variant="secondary"
        label={t('resumes.detail.delete')}
        onPress={() => void onDelete()}
        disabled={busy}
        loading={remove.isPending}
        style={{ marginTop: 16, borderColor: colors.dangerBorder }}
      />
    </DetailLayout>
  )
}
