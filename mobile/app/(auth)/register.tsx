import { router, useLocalSearchParams } from 'expo-router'
import { sanitizeReturnTo } from '../../src/auth/returnTo'
import { RegisterScreen } from '../../src/screens/RegisterScreen'

export default function RegisterRoute() {
  const { returnTo } = useLocalSearchParams<{ returnTo?: string }>()
  const safe = sanitizeReturnTo(returnTo)
  return (
    <RegisterScreen
      onGoLogin={() => router.replace(safe ? { pathname: '/login', params: { returnTo: safe } } : '/login')}
      onBrowse={() => router.replace('/welcome')}
    />
  )
}
