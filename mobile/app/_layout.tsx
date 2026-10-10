import '../src/i18n'
import { useEffect, useState } from 'react'
import { View } from 'react-native'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { AuthProvider, useAuth } from '../src/auth/AuthContext'
import { useSessionCacheGuard } from '../src/auth/sessionCache'
import { restoreLanguage } from '../src/i18n'
import { colors } from '../src/theme'

export default function RootLayout() {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
      }),
  )

  return (
    <SafeAreaProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <SessionShell />
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  )
}

function SessionShell() {
  const { status, user } = useAuth()
  const queryClient = useQueryClient()
  const [languageReady, setLanguageReady] = useState(false)

  useEffect(() => {
    void restoreLanguage().finally(() => setLanguageReady(true))
  }, [])

  useSessionCacheGuard(status, user?.userId ?? null, queryClient)

  if (status === 'restoring' || !languageReady) {
    return <View style={{ flex: 1, backgroundColor: colors.background }} />
  }

  return (
    <>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }} />
    </>
  )
}
