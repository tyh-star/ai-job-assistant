import { useEffect, useRef } from 'react'

export type LeaveReason = 'unsaved' | 'busy'

type BeforeRemoveEvent = { preventDefault: () => void; data: { action: unknown } }

/** The parts of a stack navigation object this guard uses. */
export type GuardedNavigation = {
  addListener: (type: 'beforeRemove', listener: (event: BeforeRemoveEvent) => void) => () => void
  dispatch: (action: never) => void
  setOptions?: (options: { gestureEnabled: boolean }) => void
}

/**
 * Stops the back button, Android back and the iOS swipe from silently dropping unsaved
 * edits (or a request still running). The user is asked; leaving continues only on yes.
 */
export function useLeaveGuard(
  navigation: GuardedNavigation,
  reason: LeaveReason | null,
  ask: (reason: LeaveReason) => Promise<boolean>,
) {
  const reasonRef = useRef(reason)
  reasonRef.current = reason
  const askRef = useRef(ask)
  askRef.current = ask
  const asking = useRef(false)

  useEffect(
    () =>
      navigation.addListener('beforeRemove', (event) => {
        const why = reasonRef.current
        if (!why) {
          return
        }
        event.preventDefault()
        if (asking.current) {
          return
        }
        asking.current = true
        void askRef.current(why).then((leave) => {
          asking.current = false
          if (leave) {
            reasonRef.current = null
            navigation.dispatch(event.data.action as never)
          }
        })
      }),
    [navigation],
  )

  // A native swipe cannot be cancelled halfway, so it is switched off while guarded.
  useEffect(() => {
    navigation.setOptions?.({ gestureEnabled: reason === null })
  }, [navigation, reason])
}
