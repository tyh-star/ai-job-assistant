import { router, useLocalSearchParams } from 'expo-router'
import { JobsScreen } from '../../../src/screens/JobsScreen'

export default function JobsRoute() {
  const { tab, at } = useLocalSearchParams<{ tab?: string; at?: string }>()
  return (
    <JobsScreen
      tabParam={tab}
      tabNonce={at}
      onOpenCatalogJob={(catalogJobId) =>
        router.push({ pathname: '/catalog/[catalogJobId]', params: { catalogJobId } })
      }
      onOpenJob={(jobId) => router.push({ pathname: '/job/[jobId]', params: { jobId: String(jobId) } })}
    />
  )
}
