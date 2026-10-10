import { Redirect } from 'expo-router'
import { useAuth } from '../src/auth/AuthContext'

export default function Index() {
  const { status } = useAuth()
  // Visitors start on the public welcome page; login is an entry there, not a gate.
  return <Redirect href={status === 'signedIn' ? '/dashboard' : '/welcome'} />
}
