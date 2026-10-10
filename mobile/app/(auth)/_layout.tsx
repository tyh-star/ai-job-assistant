import { Redirect, Stack, useGlobalSearchParams } from 'expo-router'
import { useAuth } from '../../src/auth/AuthContext'
import { afterLoginTarget } from '../../src/auth/returnTo'

export default function AuthLayout() {
  const { status } = useAuth()
  const { returnTo } = useGlobalSearchParams<{ returnTo?: string }>()
  if (status === 'signedIn') {
    // Back to the feature that asked for an account, or the dashboard.
    return <Redirect href={afterLoginTarget(returnTo)} />
  }
  return <Stack screenOptions={{ headerShown: false, animation: 'fade' }} />
}
