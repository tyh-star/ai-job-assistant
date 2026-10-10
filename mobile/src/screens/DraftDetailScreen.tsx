import { useState } from 'react'
import { Text, View } from 'react-native'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { draftsApi } from '../api/endpoints'
import { apiErrorKey, builderErrorKey, isNotFound, requestDiagnostic } from '../api/errors'
import { Button } from '../components/Button'
import { DetailLayout, InfoRow, detailStyles } from '../components/DetailLayout'
import { FlashBanner, Skeleton, StateView, confirmAction, useFlash } from '../components/feedback'
import { afterDraftDeleted } from '../builder/cache'
import { clearPendingGeneration } from '../builder/pendingGeneration'
import { useSessionGuard } from '../builder/sessionGuard'
import { isDraftReady } from '../dashboard/model'
import { draftSectionCounts } from '../resumes/model'
import { useDraft } from '../resumes/queries'
import { radius } from '../theme'
import { formatFullDate } from '../utils/dates'
import { Badges } from './CatalogJobDetailScreen'

type Props = {
  /** A resume-builder draft id -- never used for file routes like quality score. */
  draftId: number | null
  onBack: () => void
  onEdit: (draftId: number) => void
  onDeleted: () => void
}

/** Summary of a draft's real fields, with the way into the editor. */
export function DraftDetailScreen({ draftId, onBack, onEdit, onDeleted }: Props) {
  const { t, i18n } = useTranslation()
  const query = useDraft(draftId)
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const [deleting, setDeleting] = useState(false)
  const { flash, show } = useFlash()

  if (draftId === null || (query.isError && isNotFound(query.error))) {
    return (
      <DetailLayout onBack={onBack}>
        <StateView tone="error" title={t('resumes.draft.notFoundTitle')} actionLabel={t('resumes.detail.back')} onAction={onBack} />
      </DetailLayout>
    )
  }

  if (query.isPending) {
    return (
      <DetailLayout onBack={onBack}>
        <View aria-busy style={{ gap: 10 }}>
          <Skeleton width="80%" height={24} />
          <Skeleton width="100%" height={160} style={{ marginTop: 16, borderRadius: radius.card }} />
        </View>
      </DetailLayout>
    )
  }

  if (query.isError) {
    return (
      <DetailLayout onBack={onBack}>
        <StateView
          tone="error"
          title={t('resumes.draft.loadFailedTitle')}
          body={[t(apiErrorKey(query.error)), requestDiagnostic(query.error)].filter(Boolean).join('\n')}
          actionLabel={t('resumes.retry')}
          onAction={() => void query.refetch()}
        />
      </DetailLayout>
    )
  }

  const draft = query.data
  const id = draftId
  const info = draft.content?.basicInfo ?? {}
  const ready = draft.content ? isDraftReady(draft.content, draft.targetRole) : false
  const sections = draftSectionCounts(draft.content)
  const place = [info.city, info.country].map((part) => part?.trim()).filter(Boolean).join(', ')

  async function onDelete() {
    const ok = await confirmAction({
      title: t('builder.detail.deleteTitle'),
      message: t('builder.detail.deleteBody'),
      confirmLabel: t('resumes.confirm.deleteYes'),
      cancelLabel: t('resumes.confirm.cancel'),
      destructive: true,
    })
    if (!ok || deleting) {
      return
    }
    const ticket = guard.capture()
    setDeleting(true)
    try {
      await draftsApi.remove(id)
      if (!ticket.isCurrent()) {
        return
      }
      if (ticket.userId !== null) {
        await clearPendingGeneration(ticket.userId, id).catch(() => {})
      }
      await afterDraftDeleted(queryClient, ticket.userId, id)
      if (ticket.isCurrent()) {
        onDeleted()
      }
    } catch (error) {
      if (ticket.isCurrent()) {
        setDeleting(false)
        show('error', t('builder.detail.deleteFailed', { reason: t(builderErrorKey(error)) }))
      }
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
              label={t('builder.detail.delete')}
              onPress={() => void onDelete()}
              loading={deleting}
              style={detailStyles.flex}
            />
            <Button label={t('dashboard.editResume')} onPress={() => onEdit(id)} disabled={deleting} style={detailStyles.flex} />
          </View>
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {draft.title?.trim() || t('resumes.drafts.untitled')}
      </Text>
      <Badges
        labels={[
          { label: t('resumes.segments.drafts'), tone: 'muted' },
          ready ? { label: t('resumes.draft.ready'), tone: 'applied' } : { label: t('resumes.draft.notReady'), tone: 'muted' },
        ]}
      />
      <Text style={[detailStyles.muted, { marginTop: 10 }]}>{t('builder.detail.hint')}</Text>

      <View style={detailStyles.section}>
        <InfoRow
          label={t('resumes.draft.template')}
          value={t(`resumes.templates.${draft.templateType}`, { defaultValue: String(draft.templateType) })}
        />
        <InfoRow label={t('resumes.draft.targetRole')} value={draft.targetRole?.trim() || null} />
        <InfoRow
          label={t('resumes.draft.language')}
          value={t(`builder.locales.${draft.locale}`, { defaultValue: String(draft.locale) })}
        />
        <InfoRow label={t('resumes.draft.version')} value={draft.version != null ? String(draft.version) : null} />
        <InfoRow label={t('resumes.draft.updatedAt')} value={formatFullDate(draft.updatedAt, i18n.language) || null} />
      </View>

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('resumes.draft.contact')}
        </Text>
        <InfoRow label={t('resumes.draft.fullName')} value={info.fullName?.trim() || null} />
        <InfoRow label={t('resumes.draft.email')} value={info.email?.trim() || null} />
        <InfoRow label={t('resumes.draft.phone')} value={info.phone?.trim() || null} />
        <InfoRow label={t('resumes.draft.city')} value={place || null} />
      </View>

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('resumes.draft.sections')}
        </Text>
        {sections.length > 0 ? (
          sections.map((section) => (
            <InfoRow
              key={section.key}
              label={t(`resumes.draft.section.${section.key}`)}
              value={t('resumes.draft.sectionCount', { count: section.count })}
            />
          ))
        ) : (
          <Text style={detailStyles.muted}>{t('resumes.draft.noSections')}</Text>
        )}
      </View>
    </DetailLayout>
  )
}
