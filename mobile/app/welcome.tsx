import { Redirect, router } from 'expo-router'
import { useAuth } from '../src/auth/AuthContext'
import { loginHref } from '../src/auth/returnTo'
import { WelcomeScreen } from '../src/screens/WelcomeScreen'

export default function WelcomeRoute() {
  const { status } = useAuth()
  if (status === 'signedIn') {
    return <Redirect href="/dashboard" />
  }
  return (
    <WelcomeScreen
      onBrowseJobs={() => router.push('/browse')}
      onLogin={(returnTo) => router.push(loginHref(returnTo))}
      onRegister={() => router.push('/register')}
    />
  )
}
