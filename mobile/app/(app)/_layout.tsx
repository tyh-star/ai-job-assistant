import { Redirect, Stack, usePathname } from 'expo-router'
import { useAuth } from '../../src/auth/AuthContext'
import { signedOutRedirect } from '../../src/auth/returnTo'

export default function SignedInLayout() {
  const { status, signOutReason } = useAuth()
  const pathname = usePathname()
  if (status !== 'signedIn') {
    return <Redirect href={signedOutRedirect(signOutReason ?? null, pathname)} />
  }
  return <Stack screenOptions={{ headerShown: false }} />
}
