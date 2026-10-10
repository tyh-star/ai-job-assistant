import { router } from 'expo-router'
import { CreateDraftScreen } from '../../../src/screens/CreateDraftScreen'

export default function CreateDraftRoute() {
  return (
    <CreateDraftScreen
      onBack={() => (router.canGoBack() ? router.back() : router.replace({ pathname: '/resumes', params: { segment: 'drafts' } }))}
      onCreated={(draftId) => router.replace({ pathname: '/draft/[draftId]/edit', params: { draftId: String(draftId) } })}
    />
  )
}
