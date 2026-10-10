import { renderHook } from '@testing-library/react-native'
import * as SecureStore from 'expo-secure-store'
import type { AuthStatus } from '../src/auth/AuthContext'
import { useSessionCacheGuard } from '../src/auth/sessionCache'
import i18n, { restoreLanguage, setAppLanguage } from '../src/i18n'
import { createTestQueryClient } from '../test-utils/render'

const store = SecureStore as unknown as { __reset: () => void }

describe('cache isolation between accounts', () => {
  it('drops every cached query and cancels in-flight ones when the user signs out', async () => {
    const client = createTestQueryClient()
    client.setQueryData(['jobs', 1], [{ id: 1 }])
    client.setQueryData(['me', 1], { name: 'first account' })
    const cancel = jest.spyOn(client, 'cancelQueries')

    const { rerender } = await renderHook(
      ({ status, userId }: { status: AuthStatus; userId: number | null }) => useSessionCacheGuard(status, userId, client),
      { initialProps: { status: 'signedIn', userId: 1 } },
    )
    expect(client.getQueryData(['jobs', 1])).toEqual([{ id: 1 }])

    await rerender({ status: 'signedOut', userId: null })
    expect(cancel).toHaveBeenCalled()
    expect(client.getQueryData(['jobs', 1])).toBeUndefined()
    expect(client.getQueryData(['me', 1])).toBeUndefined()
  })

  it('also drops the cache if a different account takes over', async () => {
    const client = createTestQueryClient()
    const { rerender } = await renderHook(
      ({ userId }: { userId: number }) => useSessionCacheGuard('signedIn', userId, client),
      { initialProps: { userId: 1 } },
    )
    client.setQueryData(['resumes', 1], [{ id: 3 }])
    await rerender({ userId: 2 })
    expect(client.getQueryData(['resumes', 1])).toBeUndefined()
  })
})

describe('language', () => {
  beforeEach(async () => {
    store.__reset()
    await i18n.changeLanguage('zh-CN')
  })

  it('switches at once and remembers the choice for the next launch', async () => {
    await setAppLanguage('en')
    expect(i18n.language).toBe('en')
    expect(i18n.t('profile.title')).toBe('Me')
    expect(await SecureStore.getItemAsync('aijob.language')).toBe('en')

    // A fresh launch starts from the device language, then applies the saved choice.
    await i18n.changeLanguage('zh-CN')
    await restoreLanguage()
    expect(i18n.language).toBe('en')
  })

  it('keeps the device language when nothing valid was saved', async () => {
    await SecureStore.setItemAsync('aijob.language', 'fr')
    await restoreLanguage()
    expect(i18n.language).toBe('zh-CN')
  })
})
