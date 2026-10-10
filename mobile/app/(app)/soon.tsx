import { router, useLocalSearchParams } from 'expo-router'
import { useTranslation } from 'react-i18next'
import { ComingSoonScreen } from '../../src/screens/ComingSoonScreen'

const TITLE_KEYS: Record<string, string> = {
  jobs: 'nav.jobs',
  resumes: 'nav.resumes',
  tailor: 'nav.tailor',
}

export default function ComingSoonRoute() {
  const { t } = useTranslation()
  const { feature } = useLocalSearchParams<{ feature?: string }>()
  const title = t(TITLE_KEYS[feature ?? ''] ?? 'comingSoon.title')
  return (
    <ComingSoonScreen
      title={title}
      onBack={() => (router.canGoBack() ? router.back() : router.replace('/dashboard'))}
    />
  )
}
