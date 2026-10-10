import { router, useLocalSearchParams } from 'expo-router'
import { parseResourceId } from '../../../../src/resumes/model'
import { QualityScoreScreen } from '../../../../src/screens/QualityScoreScreen'

export default function QualityScoreRoute() {
  const { resumeId } = useLocalSearchParams<{ resumeId: string }>()
  return (
    <QualityScoreScreen
      resumeId={parseResourceId(resumeId)}
      onBack={() => (router.canGoBack() ? router.back() : router.replace('/resumes'))}
    />
  )
}
