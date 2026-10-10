import { renderHook, waitFor } from '@testing-library/react-native'
import { useLeaveGuard, type GuardedNavigation, type LeaveReason } from '../src/builder/leaveGuard'

function fakeNavigation() {
  let listener: ((event: { preventDefault: () => void; data: { action: unknown } }) => void) | null = null
  const navigation = {
    addListener: jest.fn((_type: 'beforeRemove', next: typeof listener) => {
      listener = next
      return () => {
        listener = null
      }
    }),
    dispatch: jest.fn(),
    setOptions: jest.fn(),
  }
  const leave = () => {
    const event = { preventDefault: jest.fn(), data: { action: { type: 'GO_BACK' } } }
    listener?.(event)
    return event
  }
  return { navigation, leave }
}

it('lets the screen go when nothing would be lost', async () => {
  const { navigation, leave } = fakeNavigation()
  const ask = jest.fn()
  await renderHook(() => useLeaveGuard(navigation as unknown as GuardedNavigation, null, ask))
  expect(leave().preventDefault).not.toHaveBeenCalled()
  expect(ask).not.toHaveBeenCalled()
  expect(navigation.setOptions).toHaveBeenLastCalledWith({ gestureEnabled: true })
})

it('asks before discarding unsaved edits, and only leaves on yes', async () => {
  const { navigation, leave } = fakeNavigation()
  const answers = [false, true]
  const ask = jest.fn(async (_reason: LeaveReason) => answers.shift() ?? false)
  await renderHook(() => useLeaveGuard(navigation as unknown as GuardedNavigation, 'unsaved', ask))
  expect(navigation.setOptions).toHaveBeenLastCalledWith({ gestureEnabled: false })

  expect(leave().preventDefault).toHaveBeenCalled()
  await waitFor(() => expect(ask).toHaveBeenCalledWith('unsaved'))
  expect(navigation.dispatch).not.toHaveBeenCalled()

  leave()
  await waitFor(() => expect(navigation.dispatch).toHaveBeenCalledWith({ type: 'GO_BACK' }))
})
