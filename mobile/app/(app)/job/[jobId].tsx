import { router, useLocalSearchParams } from 'expo-router'
import { parsePersonalJobId } from '../../../src/jobs/model'
import { JobDetailScreen } from '../../../src/screens/JobDetailScreen'

export default function PersonalJobRoute() {
  const { jobId } = useLocalSearchParams<{ jobId: string }>()
  const back = () => (router.canGoBack() ? router.back() : router.replace('/jobs'))
  return (
    <JobDetailScreen
      jobId={parsePersonalJobId(jobId)}
      onBack={back}
      onDeleted={back}
      onTailor={(id) => router.navigate({ pathname: '/tailor', params: { jobId: String(id), at: String(Date.now()) } })}
    />
  )
}
