import { router, useLocalSearchParams } from 'expo-router'
import { ResumesScreen } from '../../../src/screens/ResumesScreen'

export default function ResumesRoute() {
  const { segment, action, at } = useLocalSearchParams<{ segment?: string; action?: string; at?: string }>()
  return (
    <ResumesScreen
      segmentParam={segment}
      actionParam={action}
      nonce={at}
      onOpenFile={(resumeId) => router.push({ pathname: '/resume/[resumeId]', params: { resumeId: String(resumeId) } })}
      onOpenDraft={(draftId) => router.push({ pathname: '/draft/[draftId]', params: { draftId: String(draftId) } })}
      onCreateDraft={() => router.push('/draft/new')}
      onEditDraft={(draftId) => router.push({ pathname: '/draft/[draftId]/edit', params: { draftId: String(draftId) } })}
    />
  )
}
