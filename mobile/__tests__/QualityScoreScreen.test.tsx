import '../src/i18n'
import { screen, userEvent } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { resumesApi } from '../src/api/endpoints'
import { QualityScoreScreen } from '../src/screens/QualityScoreScreen'
import { qualityScore, uploadResume } from '../test-utils/fixtures'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  resumesApi: { list: jest.fn(), get: jest.fn(), qualityScore: jest.fn() },
  draftsApi: {},
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const resumes = resumesApi as jest.Mocked<typeof resumesApi>

beforeEach(() => {
  jest.resetAllMocks()
  resumes.get.mockResolvedValue(uploadResume(12, 'cv.pdf'))
})

it('shows exactly what the backend scored: total, categories, strengths and suggestions', async () => {
  resumes.qualityScore.mockResolvedValue(qualityScore())
  await renderWithQuery(<QualityScoreScreen resumeId={12} onBack={jest.fn()} />)

  expect(await screen.findByText('72 / 100')).toBeOnTheScreen()
  expect(resumes.qualityScore).toHaveBeenCalledWith(12)
  expect(screen.getByText('良好')).toBeOnTheScreen()
  expect(screen.getByText('结构与章节完整性')).toBeOnTheScreen()
  expect(screen.getByText('18 / 25')).toBeOnTheScreen()
  expect(screen.getByText('• 结构完整，章节齐全')).toBeOnTheScreen()
  expect(screen.getByText('• 为你的成果添加具体数字或百分比')).toBeOnTheScreen()
  expect(screen.queryByText('可读性与格式一致性')).not.toBeOnTheScreen()
})

it('shows a failed request as a failure with retry, never as 0 points', async () => {
  resumes.qualityScore
    .mockRejectedValueOnce(new ApiError('/resumes/12/quality-score', 502, null))
    .mockResolvedValueOnce(qualityScore({ score: 88, level: 'EXCELLENT' }))
  await renderWithQuery(<QualityScoreScreen resumeId={12} onBack={jest.fn()} />)

  expect(await screen.findByText('评分暂时无法加载')).toBeOnTheScreen()
  expect(screen.queryByText(/0 \/ 100/)).not.toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
  expect(await screen.findByText('88 / 100')).toBeOnTheScreen()
})

it('shows the backend reason when a resume cannot be scored', async () => {
  resumes.qualityScore.mockResolvedValue(
    qualityScore({ scorable: false, reasonCode: 'TEXT_NOT_AVAILABLE', score: null, level: null, categories: null, strengths: null, suggestions: null }),
  )
  await renderWithQuery(<QualityScoreScreen resumeId={12} onBack={jest.fn()} />)
  expect(await screen.findByText('暂时无法评分')).toBeOnTheScreen()
  expect(screen.getByText(/未能从这份简历中提取到文字内容/)).toBeOnTheScreen()
  expect(screen.queryByText(/\/ 100/)).not.toBeOnTheScreen()
})

it('never requests a score without a valid file id', async () => {
  await renderWithQuery(<QualityScoreScreen resumeId={null} onBack={jest.fn()} />)
  expect(screen.getByText('找不到这份简历')).toBeOnTheScreen()
  expect(resumes.qualityScore).not.toHaveBeenCalled()
})
