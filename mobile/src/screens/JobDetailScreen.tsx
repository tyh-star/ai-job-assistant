import { Linking, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import { apiErrorKey, isNotFound } from '../api/errors'
import { Button } from '../components/Button'
import { DetailLayout, InfoRow, detailStyles } from '../components/DetailLayout'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import { TextLink } from '../components/TextLink'
import { personalBadges, sourceLabel } from '../jobs/labels'
import { safeHttpUrl } from '../jobs/model'
import { usePersonalJob, usePersonalJobActions } from '../jobs/queries'
import { colors, radius } from '../theme'
import { formatFullDate } from '../utils/dates'
import { Badges } from './CatalogJobDetailScreen'

type Props = {
  /** The numeric personal job id from GET /jobs; null when the route value was invalid. */
  jobId: number | null
  onBack: () => void
  onDeleted: () => void
  /** Opens tailoring with this personal job preselected. */
  onTailor?: (jobId: number) => void
}

export function JobDetailScreen({ jobId, onBack, onDeleted, onTailor }: Props) {
  const { t } = useTranslation()
  if (jobId === null) {
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={t('jobs.detail.notFoundTitle')}
          body={t('jobs.detail.notFoundBody')}
          actionLabel={t('jobs.detail.back')}
          onAction={onBack}
        />
      </DetailLayout>
    )
  }
  return <PersonalJobDetail jobId={jobId} onBack={onBack} onDeleted={onDeleted} onTailor={onTailor} />
}

function PersonalJobDetail({ jobId, onBack, onDeleted, onTailor }: Props & { jobId: number }) {
  const { t, i18n } = useTranslation()
  const query = usePersonalJob(jobId)
  const actions = usePersonalJobActions(jobId)
  const { flash, show } = useFlash()

  if (query.isPending) {
    return (
      <DetailLayout onBack={onBack}>
        <View aria-busy style={{ gap: 10 }}>
          <Skeleton width="80%" height={24} />
          <Skeleton width="50%" height={16} />
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
          title={missing ? t('jobs.detail.notFoundTitle') : t('jobs.detail.loadFailedTitle')}
          body={missing ? t('jobs.detail.notFoundBody') : t(apiErrorKey(query.error))}
          actionLabel={missing ? t('jobs.detail.back') : t('jobs.retry')}
          onAction={missing ? onBack : () => void query.refetch()}
        />
      </DetailLayout>
    )
  }

  const job = query.data
  const originalUrl = safeHttpUrl(job.jobUrl)

  async function toggleApplied() {
    const clearing = job.applied
    const ok = await confirmAction(
      clearing
        ? {
            title: t('jobs.confirm.clearAppliedTitle'),
            message: t('jobs.confirm.clearAppliedBody'),
            confirmLabel: t('jobs.confirm.clearAppliedYes'),
            cancelLabel: t('jobs.confirm.cancel'),
          }
        : {
            title: t('jobs.confirm.markAppliedTitle'),
            message: t('jobs.confirm.markAppliedBody'),
            confirmLabel: t('jobs.confirm.markAppliedYes'),
            cancelLabel: t('jobs.confirm.cancel'),
          },
    )
    if (!ok) {
      return
    }
    try {
      await (clearing ? actions.clearApplied.mutateAsync() : actions.markApplied.mutateAsync())
      show('success', t(clearing ? 'jobs.flash.clearedApplied' : 'jobs.flash.markedApplied'))
    } catch (error) {
      show('error', t('jobs.flash.failed', { reason: t(apiErrorKey(error)) }))
    }
  }

  async function remove() {
    const ok = await confirmAction({
      title: t('jobs.confirm.deleteTitle'),
      message: t('jobs.confirm.deleteBody'),
      confirmLabel: t('jobs.confirm.deleteYes'),
      cancelLabel: t('jobs.confirm.cancel'),
      destructive: true,
    })
    if (!ok) {
      return
    }
    try {
      await actions.remove.mutateAsync()
      onDeleted()
    } catch (error) {
      show('error', t('jobs.flash.failed', { reason: t(apiErrorKey(error)) }))
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
              variant={job.applied ? 'secondary' : 'primary'}
              label={job.applied ? t('jobs.actions.clearApplied') : t('jobs.actions.markApplied')}
              onPress={() => void toggleApplied()}
              disabled={actions.busy}
              loading={actions.markApplied.isPending || actions.clearApplied.isPending}
              style={detailStyles.flex}
            />
            {onTailor ? (
              <Button
                variant="secondary"
                label={t('dashboard.tailorByJob')}
                onPress={() => onTailor(job.id)}
                disabled={actions.busy}
                style={detailStyles.flex}
              />
            ) : null}
          </View>
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {job.title}
      </Text>
      <Text style={detailStyles.company}>{job.company}</Text>
      <Text style={detailStyles.hint}>{t('jobs.detail.recordHint')}</Text>
      <Badges labels={personalBadges(t, job)} />

      <View style={detailStyles.section}>
        <InfoRow label={t('jobs.detail.location')} value={job.location?.trim() || null} />
        <InfoRow label={t('jobs.detail.source')} value={sourceLabel(t, job.source)} />
        <InfoRow label={t('jobs.detail.savedAt')} value={formatFullDate(job.createdAt, i18n.language) || null} />
        <InfoRow label={t('jobs.detail.appliedAt')} value={job.applied ? formatFullDate(job.appliedAt, i18n.language) || null : null} />
        {originalUrl ? (
          <TextLink label={t('jobs.detail.openOriginal')} arrow onPress={() => void Linking.openURL(originalUrl)} />
        ) : null}
      </View>

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('jobs.detail.description')}
        </Text>
        {job.description?.trim() ? (
          <Text style={detailStyles.body}>{job.description.trim()}</Text>
        ) : (
          <Text style={detailStyles.muted}>{t('jobs.detail.noDescription')}</Text>
        )}
      </View>

      <Button
        variant="secondary"
        label={t('jobs.actions.delete')}
        onPress={() => void remove()}
        disabled={actions.busy}
        loading={actions.remove.isPending}
        style={{ marginTop: 16, borderColor: colors.dangerBorder }}
      />
    </DetailLayout>
  )
}
