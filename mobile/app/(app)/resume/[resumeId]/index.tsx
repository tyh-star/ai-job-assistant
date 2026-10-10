import { router, useLocalSearchParams } from 'expo-router'
import { parseResourceId } from '../../../../src/resumes/model'
import { ResumeFileScreen } from '../../../../src/screens/ResumeFileScreen'

export default function ResumeFileRoute() {
  const { resumeId } = useLocalSearchParams<{ resumeId: string }>()
  const back = () => (router.canGoBack() ? router.back() : router.replace('/resumes'))
  return (
    <ResumeFileScreen
      resumeId={parseResourceId(resumeId)}
      onBack={back}
      onDeleted={back}
      onOpenQuality={(id) => router.push({ pathname: '/resume/[resumeId]/quality', params: { resumeId: String(id) } })}
      onOpenStructure={(id) => router.push({ pathname: '/resume/[resumeId]/structure', params: { resumeId: String(id) } })}
    />
  )
}
