import '../src/i18n'
import { render, screen, userEvent } from '@testing-library/react-native'
import * as endpoints from '../src/api/endpoints'
import { WelcomeScreen } from '../src/screens/WelcomeScreen'

jest.mock('../src/api/endpoints', () => {
  const api = () => new Proxy({}, { get: () => jest.fn() })
  return {
    authApi: api(), usersApi: api(), feedbackApi: api(), resumesApi: api(), draftsApi: api(),
    jobsApi: api(), catalogApi: api(), tailorApi: api(), enhancedApi: api(), evidenceApi: api(),
  }
})

const fetchMock = jest.fn()

beforeEach(() => {
  fetchMock.mockReset()
  global.fetch = fetchMock as unknown as typeof fetch
})

async function renderWelcome() {
  const handlers = { onBrowseJobs: jest.fn(), onLogin: jest.fn(), onRegister: jest.fn() }
  await render(<WelcomeScreen {...handlers} />)
  return handlers
}

it('opens without logging in and without any request', async () => {
  await renderWelcome()
  expect(screen.getByRole('heading', { name: '找职位、做简历、按职位改写' })).toBeOnTheScreen()
  expect(fetchMock).not.toHaveBeenCalled()
  expect(Object.keys(endpoints)).toContain('resumesApi')
})

it('labels the sample content as examples, not real data', async () => {
  await renderWelcome()
  expect(screen.getByText('示例 · 不是真实数据')).toBeOnTheScreen()
  expect(screen.getByText(/不来自任何用户、简历或职位/)).toBeOnTheScreen()
  expect(screen.getByText('负责制作每周的销售报表。')).toBeOnTheScreen()
  expect(screen.getByText('72 / 100')).toBeOnTheScreen()
})

it('browses jobs freely and sends account features to login with where to return', async () => {
  const { onBrowseJobs, onLogin, onRegister } = await renderWelcome()
  const user = userEvent.setup()
  await user.press(screen.getAllByRole('button', { name: '浏览职位' })[0])
  expect(onBrowseJobs).toHaveBeenCalled()
  expect(onLogin).not.toHaveBeenCalled()

  await user.press(screen.getByRole('button', { name: '登录后创建简历' }))
  expect(onLogin).toHaveBeenLastCalledWith('/resumes?segment=drafts')
  await user.press(screen.getByRole('button', { name: '登录后改写简历' }))
  expect(onLogin).toHaveBeenLastCalledWith('/tailor')
  await user.press(screen.getByRole('button', { name: '登录后上传简历' }))
  expect(onLogin).toHaveBeenLastCalledWith('/resumes')
  await user.press(screen.getAllByRole('button', { name: '注册账号' })[0])
  expect(onRegister).toHaveBeenCalled()
})
