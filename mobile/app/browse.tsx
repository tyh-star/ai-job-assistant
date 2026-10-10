import { Redirect, router } from 'expo-router'
import { useAuth } from '../src/auth/AuthContext'
import { JobsScreen } from '../src/screens/JobsScreen'

/** Public job search for visitors. Signed-in users have the full Jobs tab. */
export default function BrowseJobsRoute() {
  const { status } = useAuth()
  if (status === 'signedIn') {
    return <Redirect href="/jobs" />
  }
  return (
    <JobsScreen
      guest
      onBack={() => (router.canGoBack() ? router.back() : router.replace('/welcome'))}
      onOpenCatalogJob={(catalogJobId) => router.push({ pathname: '/catalog/[catalogJobId]', params: { catalogJobId } })}
      onOpenJob={() => {}}
    />
  )
}
