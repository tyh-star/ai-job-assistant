import { router } from 'expo-router'
import { DashboardScreen, type DashboardTarget } from '../../../src/screens/DashboardScreen'

function open(target: DashboardTarget) {
  // `at` re-applies a tab parameter even when that tab already has the same value.
  const at = String(Date.now())
  switch (target.kind) {
    case 'catalog':
    case 'saved':
    case 'applied':
      router.navigate({ pathname: '/jobs', params: { tab: target.kind, at } })
      return
    case 'job':
      router.push({ pathname: '/job/[jobId]', params: { jobId: String(target.jobId) } })
      return
    case 'resumes':
      router.navigate({ pathname: '/resumes', params: { segment: 'files', at } })
      return
    case 'upload':
      router.navigate({ pathname: '/resumes', params: { segment: 'files', action: 'upload', at } })
      return
    case 'resumeFile':
      router.push({ pathname: '/resume/[resumeId]', params: { resumeId: String(target.resumeId) } })
      return
    case 'quality':
      router.push({ pathname: '/resume/[resumeId]/quality', params: { resumeId: String(target.resumeId) } })
      return
    case 'draft':
      router.push({ pathname: '/draft/[draftId]', params: { draftId: String(target.draftId) } })
      return
    case 'tailor':
      // No preselection: switch to the 改写 tab and keep whatever is already chosen there.
      router.navigate('/tailor')
  }
}

export default function DashboardRoute() {
  return <DashboardScreen onOpen={open} />
}
