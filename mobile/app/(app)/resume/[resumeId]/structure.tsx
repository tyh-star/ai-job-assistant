import { useCallback, useState } from 'react'
import { router, useLocalSearchParams, useNavigation } from 'expo-router'
import { useTranslation } from 'react-i18next'
import { useLeaveGuard, type GuardedNavigation, type LeaveReason } from '../../../../src/builder/leaveGuard'
import { confirmAction } from '../../../../src/components/feedback'
import { parseResourceId } from '../../../../src/resumes/model'
import { ResumeStructureScreen } from '../../../../src/screens/ResumeStructureScreen'

const PRODUCT_MODES = ['CONSERVATIVE', 'AGGRESSIVE', 'ENHANCED']

export default function ResumeStructureRoute() {
  const { resumeId, from, jobId, mode } = useLocalSearchParams<{ resumeId: string; from?: string; jobId?: string; mode?: string }>()
  const { t } = useTranslation()
  const navigation = useNavigation()
  const [reason, setReason] = useState<LeaveReason | null>(null)
  const id = parseResourceId(resumeId)
  const fromTailor = from === 'tailor'

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

  /** Back to the 改写 tab that is still open underneath, or open it again with the same choices. */
  const returnToTailor = () => {
    if (router.canGoBack()) {
      router.back()
      return
    }
    const params: Record<string, string> = { at: String(Date.now()) }
    if (id !== null) {
      params.resumeId = String(id)
    }
    const job = parseResourceId(jobId)
    if (job !== null) {
      params.jobId = String(job)
    }
    if (mode && PRODUCT_MODES.includes(mode)) {
      params.mode = mode
    }
    router.replace({ pathname: '/tailor', params })
  }

  const back = () => {
    if (fromTailor) {
      returnToTailor()
    } else if (router.canGoBack()) {
      router.back()
    } else if (id !== null) {
      router.replace({ pathname: '/resume/[resumeId]', params: { resumeId: String(id) } })
    } else {
      router.replace('/resumes')
    }
  }

  return (
    <ResumeStructureScreen
      resumeId={id}
      fromTailor={fromTailor}
      onBack={back}
      onContinueTailor={returnToTailor}
      onLeaveReasonChange={setReason}
    />
  )
}
