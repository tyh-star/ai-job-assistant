import '../src/i18n'
import type { ReactNode } from 'react'
import { act, render, screen } from '@testing-library/react-native'
import TabsLayout from '../app/(app)/(tabs)/_layout'
import DashboardRoute from '../app/(app)/(tabs)/dashboard'
import TailorTabRoute from '../app/(app)/(tabs)/tailor'
import PersonalJobRoute from '../app/(app)/job/[jobId]'
import ResumeFileRoute from '../app/(app)/resume/[resumeId]/index'
import ResumeStructureRoute from '../app/(app)/resume/[resumeId]/structure'
import CatalogJobRoute from '../app/catalog/[catalogJobId]'
import { confirmAction } from '../src/components/feedback'

type Captured = Record<string, (...args: never[]) => unknown> & Record<string, unknown>

const mockRouter = {
  navigate: jest.fn(),
  push: jest.fn(),
  replace: jest.fn(),
  back: jest.fn(),
  canGoBack: jest.fn(() => false),
}
const mockState = {
  params: {} as Record<string, string | undefined>,
  listeners: [] as Array<(event: { preventDefault: () => void; data: { action: unknown } }) => void>,
  tailorMounts: 0,
  captured: {} as Record<string, Captured>,
}
const mockNavigation = {
  addListener: jest.fn((_type: string, listener: (typeof mockState.listeners)[number]) => {
    mockState.listeners.push(listener)
    return () => {}
  }),
  dispatch: jest.fn(),
  setOptions: jest.fn(),
}

jest.mock('expo-router', () => {
  const { Text: MockText, View: MockView } = jest.requireActual('react-native')
  const Tabs = ({ children }: { children: ReactNode }) => <MockView>{children}</MockView>
  Tabs.Screen = ({ name, options }: { name: string; options: { title: string; tabBarIcon: (p: { color: string }) => ReactNode } }) => (
    <MockView>
      <MockText>{`tab:${name}:${options.title}`}</MockText>
      {options.tabBarIcon({ color: '#000000' })}
    </MockView>
  )
  return {
    Tabs,
    // A getter: route modules are imported (and this factory runs) before mockRouter is initialized.
    get router() {
      return mockRouter
    },
    useLocalSearchParams: () => mockState.params,
    useNavigation: () => mockNavigation,
  }
})

jest.mock('../src/components/feedback', () => ({ confirmAction: jest.fn() }))
jest.mock('../src/auth/AuthContext', () => ({ useAuth: () => ({ status: 'signedIn' }) }))

jest.mock('../src/screens/TailorScreen', () => {
  const { useEffect } = jest.requireActual('react')
  const { Text: MockText } = jest.requireActual('react-native')
  return {
    TailorScreen: (props: Captured) => {
      mockState.captured.tailor = props
      useEffect(() => {
        mockState.tailorMounts += 1
      }, [])
      return <MockText>{`tailor job=${String(props.initialJobId)} resume=${String(props.initialResumeId)} mode=${String(props.initialMode)}`}</MockText>
    },
  }
})
jest.mock('../src/screens/DashboardScreen', () => ({
  DashboardScreen: (props: Captured) => {
    mockState.captured.dashboard = props
    return null
  },
}))
jest.mock('../src/screens/JobDetailScreen', () => ({
  JobDetailScreen: (props: Captured) => {
    mockState.captured.job = props
    return null
  },
}))
jest.mock('../src/screens/CatalogJobDetailScreen', () => ({
  CatalogJobDetailScreen: (props: Captured) => {
    mockState.captured.catalog = props
    return null
  },
}))
jest.mock('../src/screens/ResumeFileScreen', () => ({
  ResumeFileScreen: (props: Captured) => {
    mockState.captured.file = props
    return null
  },
}))
jest.mock('../src/screens/ResumeStructureScreen', () => ({
  ResumeStructureScreen: (props: Captured) => {
    mockState.captured.structure = props
    return null
  },
}))

const confirm = confirmAction as jest.MockedFunction<typeof confirmAction>

function call(name: string, fn: string, ...args: unknown[]) {
  return (mockState.captured[name][fn] as unknown as (...a: unknown[]) => unknown)(...args)
}

beforeEach(() => {
  jest.clearAllMocks()
  mockRouter.canGoBack.mockReturnValue(false)
  mockState.params = {}
  mockState.listeners = []
  mockState.tailorMounts = 0
  mockState.captured = {}
})

describe('bottom tabs', () => {
  it('shows 控制台, 职位, 简历, 改写, 我的 in that order', async () => {
    await render(<TabsLayout />)
    const labels = screen.getAllByText(/^tab:/).map((node) => node.props.children)
    expect(labels).toEqual(['tab:dashboard:控制台', 'tab:jobs:职位', 'tab:resumes:简历', 'tab:tailor:改写', 'tab:profile:我的'])
  })
})

describe('改写 tab', () => {
  it('opens the existing tailoring screen with the preselected job, resume and mode, without a back button', async () => {
    mockState.params = { jobId: '9', resumeId: '14', mode: 'AGGRESSIVE', at: '100' }
    await render(<TailorTabRoute />)
    expect(screen.getByText('tailor job=9 resume=14 mode=AGGRESSIVE')).toBeOnTheScreen()
    expect(mockState.captured.tailor.onBack).toBeUndefined()
  })

  it('keeps the current choices on a plain tab switch and reopens only for a new shortcut', async () => {
    mockState.params = { jobId: '9', at: '100' }
    const view = await render(<TailorTabRoute />)

    mockState.params = {}
    await act(async () => {
      await view.rerender(<TailorTabRoute />)
    })
    expect(screen.getByText('tailor job=9 resume=null mode=null')).toBeOnTheScreen()
    expect(mockState.tailorMounts).toBe(1)

    mockState.params = { jobId: '12', at: '200' }
    await act(async () => {
      await view.rerender(<TailorTabRoute />)
    })
    expect(screen.getByText('tailor job=12 resume=null mode=null')).toBeOnTheScreen()
    expect(mockState.tailorMounts).toBe(2)
    expect(confirm).not.toHaveBeenCalled()
  })

  it('asks before a shortcut replaces work that is still running', async () => {
    mockState.params = { jobId: '9', at: '100' }
    const view = await render(<TailorTabRoute />)
    await act(async () => {
      call('tailor', 'onLeaveReasonChange', 'busy')
    })

    confirm.mockResolvedValueOnce(false)
    mockState.params = { jobId: '12', at: '200' }
    await act(async () => {
      await view.rerender(<TailorTabRoute />)
    })
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(screen.getByText('tailor job=9 resume=null mode=null')).toBeOnTheScreen()
    expect(mockState.tailorMounts).toBe(1)

    confirm.mockResolvedValueOnce(true)
    mockState.params = { jobId: '12', at: '300' }
    await act(async () => {
      await view.rerender(<TailorTabRoute />)
    })
    expect(screen.getByText('tailor job=12 resume=null mode=null')).toBeOnTheScreen()
  })

  it('still guards removal of the screen while there are unsaved answers', async () => {
    await render(<TailorTabRoute />)
    await act(async () => {
      call('tailor', 'onLeaveReasonChange', 'unsaved')
    })
    confirm.mockResolvedValueOnce(false)
    const event = { preventDefault: jest.fn(), data: { action: { type: 'RESET' } } }
    await act(async () => {
      mockState.listeners.forEach((listener) => listener(event))
    })
    expect(event.preventDefault).toHaveBeenCalled()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(mockNavigation.dispatch).not.toHaveBeenCalled()
  })

  it('opens PDF recognition with the job and mode to come back to', async () => {
    await render(<TailorTabRoute />)
    call('tailor', 'onOpenStructure', 14, 9, 'ENHANCED')
    expect(mockRouter.push).toHaveBeenCalledWith({
      pathname: '/resume/[resumeId]/structure',
      params: { resumeId: '14', from: 'tailor', mode: 'ENHANCED', jobId: '9' },
    })
  })
})

describe('entries into the 改写 tab', () => {
  it('dashboard shortcut switches to the tab without a stack screen', async () => {
    await render(<DashboardRoute />)
    call('dashboard', 'onOpen', { kind: 'tailor' })
    expect(mockRouter.navigate).toHaveBeenCalledWith('/tailor')
    expect(mockRouter.push).not.toHaveBeenCalled()
  })

  it('personal job detail opens the tab with that job preselected', async () => {
    mockState.params = { jobId: '42' }
    await render(<PersonalJobRoute />)
    call('job', 'onTailor', 42)
    expect(mockRouter.navigate).toHaveBeenCalledWith({ pathname: '/tailor', params: { jobId: '42', at: expect.any(String) } })
    expect(mockRouter.push).not.toHaveBeenCalled()
  })

  it('saved catalog posting opens the tab with its personal job preselected', async () => {
    mockState.params = { catalogJobId: 'freehire:analyst' }
    await render(<CatalogJobRoute />)
    call('catalog', 'onTailor', 42)
    expect(mockRouter.navigate).toHaveBeenCalledWith({ pathname: '/tailor', params: { jobId: '42', at: expect.any(String) } })
  })

  it('resume file detail no longer offers tailoring', async () => {
    mockState.params = { resumeId: '14' }
    await render(<ResumeFileRoute />)
    expect(mockState.captured.file.onTailor).toBeUndefined()
    expect(mockState.captured.file.onOpenStructure).toEqual(expect.any(Function))
  })

  it('PDF recognition goes back to the 改写 tab underneath', async () => {
    mockState.params = { resumeId: '14', from: 'tailor', jobId: '9', mode: 'ENHANCED' }
    mockRouter.canGoBack.mockReturnValue(true)
    await render(<ResumeStructureRoute />)
    call('structure', 'onContinueTailor')
    expect(mockRouter.back).toHaveBeenCalled()
    expect(mockRouter.replace).not.toHaveBeenCalled()
  })

  it('PDF recognition reopens the 改写 tab with the same choices when nothing is underneath', async () => {
    mockState.params = { resumeId: '14', from: 'tailor', jobId: '9', mode: 'ENHANCED' }
    await render(<ResumeStructureRoute />)
    call('structure', 'onContinueTailor')
    expect(mockRouter.replace).toHaveBeenCalledWith({
      pathname: '/tailor',
      params: { resumeId: '14', jobId: '9', mode: 'ENHANCED', at: expect.any(String) },
    })
  })
})
