import { useCallback, useState } from 'react'
import { router, useLocalSearchParams, useNavigation } from 'expo-router'
import { useTranslation } from 'react-i18next'
import { confirmAction } from '../../../../src/components/feedback'
import { useLeaveGuard, type GuardedNavigation, type LeaveReason } from '../../../../src/builder/leaveGuard'
import { parseResourceId } from '../../../../src/resumes/model'
import { DraftEditorScreen } from '../../../../src/screens/DraftEditorScreen'

export default function DraftEditorRoute() {
  const { draftId } = useLocalSearchParams<{ draftId: string }>()
  const { t } = useTranslation()
  const navigation = useNavigation()
  const [reason, setReason] = useState<LeaveReason | null>(null)

  const ask = useCallback(
    (why: LeaveReason) =>
      confirmAction({
        title: t(`builder.leave.${why}Title`),
        message: t(`builder.leave.${why}Body`),
        confirmLabel: t('builder.leave.leave'),
        cancelLabel: t('builder.leave.stay'),
        destructive: why === 'unsaved',
      }),
    [t],
  )
  useLeaveGuard(navigation as unknown as GuardedNavigation, reason, ask)

  return (
    <DraftEditorScreen
      draftId={parseResourceId(draftId)}
      onLeaveReasonChange={setReason}
      onBack={() => (router.canGoBack() ? router.back() : router.replace({ pathname: '/resumes', params: { segment: 'drafts' } }))}
      onOpenFile={(resumeId) => router.push({ pathname: '/resume/[resumeId]', params: { resumeId: String(resumeId) } })}
      onOpenFiles={() => router.navigate({ pathname: '/resumes', params: { segment: 'files', at: String(Date.now()) } })}
    />
  )
}
