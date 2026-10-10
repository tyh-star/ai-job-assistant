import { router, useLocalSearchParams } from 'expo-router'
import { useAuth } from '../../src/auth/AuthContext'
import { loginHref } from '../../src/auth/returnTo'
import { CatalogJobDetailScreen } from '../../src/screens/CatalogJobDetailScreen'

/** Public: GET /job-catalog/{id} needs no account. Saving, applied and tailoring do. */
export default function CatalogJobRoute() {
  const { catalogJobId } = useLocalSearchParams<{ catalogJobId: string }>()
  const { status } = useAuth()
  const id = Array.isArray(catalogJobId) ? catalogJobId[0] : (catalogJobId ?? '')
  const guest = status !== 'signedIn'
  return (
    <CatalogJobDetailScreen
      catalogJobId={id}
      guest={guest}
      onBack={() => (router.canGoBack() ? router.back() : router.replace(guest ? '/browse' : '/jobs'))}
      onRequireLogin={() => router.replace(loginHref(`/catalog/${encodeURIComponent(id)}`))}
      onTailor={
        guest ? undefined : (jobId) => router.navigate({ pathname: '/tailor', params: { jobId: String(jobId), at: String(Date.now()) } })
      }
    />
  )
}
