import { router, useLocalSearchParams } from 'expo-router'
import { sanitizeReturnTo } from '../../src/auth/returnTo'
import { LoginScreen } from '../../src/screens/LoginScreen'

export default function LoginRoute() {
  const { returnTo } = useLocalSearchParams<{ returnTo?: string }>()
  const safe = sanitizeReturnTo(returnTo)
  return (
    <LoginScreen
      loginRequired={safe !== null}
      onGoRegister={() => router.replace(safe ? { pathname: '/register', params: { returnTo: safe } } : '/register')}
      onBrowse={() => router.replace('/welcome')}
    />
  )
}
