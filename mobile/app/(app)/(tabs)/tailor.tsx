import { useCallback, useEffect, useRef, useState } from 'react'
import { router, useLocalSearchParams, useNavigation } from 'expo-router'
import { useTranslation } from 'react-i18next'
import { confirmAction } from '../../../src/components/feedback'
import { useLeaveGuard, type GuardedNavigation, type LeaveReason } from '../../../src/builder/leaveGuard'
import { parseResourceId } from '../../../src/resumes/model'
import { TailorScreen } from '../../../src/screens/TailorScreen'
import type { ProductMode } from '../../../src/tailor/records'

type Params = { jobId?: string; resumeId?: string; mode?: string; at?: string }

type Selection = { at: string | undefined; jobId: number | null; resumeId: number | null; mode: ProductMode | null }

function selectionFrom(params: Params): Selection {
  const { mode } = params
  return {
    at: params.at,
    jobId: parseResourceId(params.jobId),
    resumeId: parseResourceId(params.resumeId),
    mode: mode === 'CONSERVATIVE' || mode === 'AGGRESSIVE' || mode === 'ENHANCED' ? mode : null,
  }
}

/**
 * The 改写 tab. A tab stays mounted, so shortcuts that preselect a job, resume or mode send a
 * new `at` and the screen opens again with those choices. A plain tab switch keeps whatever
 * is on screen. Work that is still running or not yet saved is not replaced without asking.
 */
export default function TailorTabRoute() {
  const params = useLocalSearchParams<Params>()
  const { t } = useTranslation()
  const navigation = useNavigation()
  const [reason, setReason] = useState<LeaveReason | null>(null)
  const [selection, setSelection] = useState<Selection>(() => selectionFrom(params))
  const reasonRef = useRef(reason)
  reasonRef.current = reason

  const ask = useCallback(
    (why: LeaveReason) =>
      confirmAction({
        title: t(`builder.leave.${why}Title`),
        message: t(`builder.leave.${why}Body`),
        confirmLabel: t('builder.leave.leave'),
        cancelLabel: t('builder.leave.stay'),
      }),
    [t],
  )
  useLeaveGuard(navigation as unknown as GuardedNavigation, reason, ask)

  const next = selectionFrom(params)
  useEffect(() => {
    if (next.at === undefined || next.at === selection.at) {
      return
    }
    let active = true
    const why = reasonRef.current
    void (why ? ask(why) : Promise.resolve(true)).then((replace) => {
      if (active && replace) {
        setSelection(next)
      }
    })
    return () => {
      active = false
    }
    // Only a new `at` means "open again with these choices".
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [next.at])

  return (
    <TailorScreen
      key={selection.at ?? 'tab'}
      initialJobId={selection.jobId}
      initialResumeId={selection.resumeId}
      initialMode={selection.mode}
      onLeaveReasonChange={setReason}
      onOpenStructure={(id, forJobId, forMode) =>
        router.push({
          pathname: '/resume/[resumeId]/structure',
          params: { resumeId: String(id), from: 'tailor', mode: forMode, ...(forJobId !== null ? { jobId: String(forJobId) } : {}) },
        })
      }
      onOpenJobs={() => router.navigate({ pathname: '/jobs', params: { tab: 'catalog', at: String(Date.now()) } })}
      onOpenResumes={() => router.navigate({ pathname: '/resumes', params: { segment: 'files', at: String(Date.now()) } })}
    />
  )
}
