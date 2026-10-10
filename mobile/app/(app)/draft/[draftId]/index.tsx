import { router, useLocalSearchParams } from 'expo-router'
import { parseResourceId } from '../../../../src/resumes/model'
import { DraftDetailScreen } from '../../../../src/screens/DraftDetailScreen'

export default function DraftRoute() {
  const { draftId } = useLocalSearchParams<{ draftId: string }>()
  const back = () => (router.canGoBack() ? router.back() : router.replace({ pathname: '/resumes', params: { segment: 'drafts' } }))
  return (
    <DraftDetailScreen
      draftId={parseResourceId(draftId)}
      onBack={back}
      onDeleted={back}
      onEdit={(id) => router.push({ pathname: '/draft/[draftId]/edit', params: { draftId: String(id) } })}
    />
  )
}
