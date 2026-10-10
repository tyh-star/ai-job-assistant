import { useState } from 'react'
import { Linking, Pressable, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import { apiErrorKey, isDetailGone } from '../api/errors'
import { Button } from '../components/Button'
import { DetailLayout, InfoRow, detailStyles } from '../components/DetailLayout'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import { TextLink } from '../components/TextLink'
import { catalogBadges, catalogLocation, salaryLabel, sourceLabel } from '../jobs/labels'
import { descriptionOf, isKnownEmploymentType, isKnownRemoteType, safeHttpUrl } from '../jobs/model'
import { useCatalogDetail, useCatalogJobActions } from '../jobs/queries'
import { colors, radius } from '../theme'
import { formatFullDate } from '../utils/dates'

const COLLAPSED_LINES = 12
const LONG_DESCRIPTION = 600

type Props = {
  /** The catalog reference (for example "freehire:<slug>"), never a personal job id. */
  catalogJobId: string
  onBack: () => void
  /** Tailoring takes the personal job id, so it is offered only once this posting is saved. */
  onTailor?: (personalJobId: number) => void
  /** Nobody signed in: the posting is public, but saving and applied need an account. */
  guest?: boolean
  onRequireLogin?: () => void
}

export function CatalogJobDetailScreen({ catalogJobId, onBack, onTailor, guest = false, onRequireLogin }: Props) {
  const { t, i18n } = useTranslation()
  const query = useCatalogDetail(catalogJobId)
  const actions = useCatalogJobActions(catalogJobId)
  const { flash, show } = useFlash()
  const [expanded, setExpanded] = useState(false)

  async function run(action: () => Promise<unknown>, successKey: string) {
    try {
      await action()
      show('success', t(successKey))
    } catch (error) {
      show('error', t('jobs.flash.failed', { reason: t(apiErrorKey(error)) }))
    }
  }

  if (query.isPending) {
    return (
      <DetailLayout onBack={onBack}>
        <View aria-busy style={{ gap: 10 }}>
          <Skeleton width="80%" height={24} />
          <Skeleton width="50%" height={16} />
          <Skeleton width="100%" height={160} style={{ marginTop: 16, borderRadius: radius.card }} />
        </View>
      </DetailLayout>
    )
  }

  if (query.isError && !query.data) {
    const gone = isDetailGone(query.error)
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={gone ? t('jobs.detail.goneTitle') : t('jobs.detail.loadFailedTitle')}
          body={gone ? t('jobs.detail.goneBody') : t(apiErrorKey(query.error))}
          actionLabel={gone ? t('jobs.detail.back') : t('jobs.retry')}
          onAction={gone ? onBack : () => void query.refetch()}
        />
      </DetailLayout>
    )
  }

  const job = query.data
  const description = descriptionOf(job)
  const originalUrl = safeHttpUrl(job.applyUrl ?? job.canonicalUrl)
  const place = [catalogLocation(job), [job.city, job.region].filter(Boolean).join(', ')]
    .filter((part, index, all) => part && all.indexOf(part) === index)
    .join(' · ')

  async function toggleSaved() {
    if (job.saved) {
      const personalJobId = job.personalJobId
      if (personalJobId === null) {
        return
      }
      const ok = await confirmAction({
        title: t('jobs.confirm.unsaveTitle'),
        message: t('jobs.confirm.unsaveBody'),
        confirmLabel: t('jobs.confirm.unsaveYes'),
        cancelLabel: t('jobs.confirm.cancel'),
        destructive: true,
      })
      if (ok) {
        await run(() => actions.unsave.mutateAsync(personalJobId), 'jobs.flash.unsaved')
      }
      return
    }
    await run(() => actions.save.mutateAsync(), 'jobs.flash.saved')
  }

  async function toggleApplied() {
    if (job.applied) {
      const ok = await confirmAction({
        title: t('jobs.confirm.clearAppliedTitle'),
        message: t('jobs.confirm.clearAppliedBody'),
        confirmLabel: t('jobs.confirm.clearAppliedYes'),
        cancelLabel: t('jobs.confirm.cancel'),
      })
      if (ok) {
        await run(() => actions.clearApplied.mutateAsync(), 'jobs.flash.clearedApplied')
      }
      return
    }
    const ok = await confirmAction({
      title: t('jobs.confirm.markAppliedTitle'),
      message: t('jobs.confirm.markAppliedBody'),
      confirmLabel: t('jobs.confirm.markAppliedYes'),
      cancelLabel: t('jobs.confirm.cancel'),
    })
    if (ok) {
      await run(() => actions.markApplied.mutateAsync(), 'jobs.flash.markedApplied')
    }
  }

  return (
    <DetailLayout
      onBack={onBack}
      actions={
        guest ? (
          <>
            <Text style={detailStyles.muted}>{t('guest.catalogNotice')}</Text>
            <Button label={t('guest.catalogLogin')} onPress={() => onRequireLogin?.()} />
          </>
        ) : (
        <>
          <FlashBanner flash={flash} />
          <View style={detailStyles.actionRow}>
            <Button
              variant="secondary"
              label={job.saved ? t('jobs.actions.unsave') : t('jobs.actions.save')}
              onPress={() => void toggleSaved()}
              disabled={actions.busy || (job.saved && job.personalJobId === null)}
              loading={actions.save.isPending || actions.unsave.isPending}
              style={detailStyles.flex}
            />
            <Button
              variant={job.applied ? 'secondary' : 'primary'}
              label={job.applied ? t('jobs.actions.clearApplied') : t('jobs.actions.markApplied')}
              onPress={() => void toggleApplied()}
              disabled={actions.busy}
              loading={actions.markApplied.isPending || actions.clearApplied.isPending}
              style={detailStyles.flex}
            />
          </View>
          {onTailor && job.saved && job.personalJobId !== null ? (
            <Button
              variant="secondary"
              size="sm"
              label={t('dashboard.tailorByJob')}
              onPress={() => onTailor(job.personalJobId as number)}
              disabled={actions.busy}
            />
          ) : null}
        </>
        )
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {job.title}
      </Text>
      <Text style={detailStyles.company}>{job.companyName}</Text>
      <Badges labels={catalogBadges(t, job)} />

      <View style={detailStyles.section}>
        <InfoRow label={t('jobs.detail.location')} value={place || null} />
        <InfoRow label={t('jobs.detail.workMode')} value={isKnownRemoteType(job.remoteType) ? t(`jobs.remote.${job.remoteType}`) : null} />
        <InfoRow
          label={t('jobs.detail.employment')}
          value={isKnownEmploymentType(job.employmentType) ? t(`jobs.employment.${job.employmentType}`) : null}
        />
        <InfoRow label={t('jobs.detail.salary')} value={salaryLabel(t, job)} />
        <InfoRow label={t('jobs.detail.posted')} value={formatFullDate(job.postedAt, i18n.language) || null} />
        <InfoRow label={t('jobs.detail.appliedAt')} value={job.applied ? formatFullDate(job.appliedAt, i18n.language) || null : null} />
        <InfoRow label={t('jobs.detail.source')} value={sourceLabel(t, job.source)} />
        {originalUrl ? (
          <TextLink label={t('jobs.detail.openOriginal')} arrow onPress={() => void Linking.openURL(originalUrl)} />
        ) : null}
      </View>

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('jobs.detail.description')}
        </Text>
        {description ? (
          <>
            <Text style={detailStyles.body} numberOfLines={expanded ? undefined : COLLAPSED_LINES}>
              {description}
            </Text>
            {description.length > LONG_DESCRIPTION ? (
              <Pressable role="button" onPress={() => setExpanded((value) => !value)} hitSlop={8} style={{ minHeight: 44, justifyContent: 'center' }}>
                <Text style={{ fontSize: 14, fontWeight: '600', color: colors.blue }}>
                  {expanded ? t('jobs.detail.collapse') : t('jobs.detail.expand')}
                </Text>
              </Pressable>
            ) : null}
          </>
        ) : (
          <Text style={detailStyles.muted}>{t('jobs.detail.noDescription')}</Text>
        )}
      </View>
    </DetailLayout>
  )
}

export function Badges({ labels }: { labels: { label: string; tone: 'saved' | 'applied' | 'muted' }[] }) {
  if (labels.length === 0) {
    return null
  }
  const tone = {
    saved: { backgroundColor: '#E4EFFD', color: colors.blueStrong },
    applied: { backgroundColor: '#E3F6EC', color: '#067647' },
    muted: { backgroundColor: '#EEF1F6', color: colors.subtle },
  }
  return (
    <View style={detailStyles.badges}>
      {labels.map((badge) => (
        <View
          key={badge.label}
          style={{ paddingHorizontal: 10, paddingVertical: 4, borderRadius: radius.pill, backgroundColor: tone[badge.tone].backgroundColor }}
        >
          <Text style={{ fontSize: 13, fontWeight: '600', color: tone[badge.tone].color }}>{badge.label}</Text>
        </View>
      ))}
    </View>
  )
}
