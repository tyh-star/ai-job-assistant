import '../src/i18n'
import { Alert } from 'react-native'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { feedbackApi, usersApi } from '../src/api/endpoints'
import { SessionStoreError } from '../src/auth/AuthContext'
import { ProfileScreen } from '../src/screens/ProfileScreen'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  usersApi: { me: jest.fn() },
  feedbackApi: { me: jest.fn(), save: jest.fn(), remove: jest.fn() },
  resumesApi: {},
  draftsApi: {},
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

const mockAuth = {
  user: { userId: 5, name: 'Old stored name', email: 'old@example.test', role: 'USER' },
  syncUser: jest.fn(),
  updateName: jest.fn(),
  updateEmail: jest.fn(),
  changePassword: jest.fn(),
  deleteAccount: jest.fn(),
  logout: jest.fn(),
}

jest.mock('../src/auth/AuthContext', () => {
  const actual = jest.requireActual('../src/auth/AuthContext')
  return { ...actual, useAuth: () => mockAuth }
})

const users = usersApi as jest.Mocked<typeof usersApi>
const feedback = feedbackApi as jest.Mocked<typeof feedbackApi>
const ME = { id: 5, name: 'nb', email: 'nb@example.test', role: 'USER', createdAt: '2026-09-01T10:00:00' }

/** Presses the last (confirm) or first (cancel) button of every system dialog. */
function answerDialogs(confirm: boolean) {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    const button = confirm ? buttons?.[buttons.length - 1] : buttons?.[0]
    button?.onPress?.()
  })
}

async function renderProfile() {
  await renderWithQuery(<ProfileScreen />)
  // Only present once GET /users/me has answered (the email also appears in the form row).
  await screen.findByText('注册于 2026年9月1日')
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  users.me.mockResolvedValue(ME)
  feedback.me.mockRejectedValue(new ApiError('/feedback/me', 404, { message: 'No feedback submitted yet' }))
})

it('shows the account from GET /users/me rather than the details stored at login', async () => {
  await renderProfile()
  expect(screen.getAllByText('nb').length).toBeGreaterThan(0)
  expect(screen.queryByText('Old stored name')).not.toBeOnTheScreen()
  expect(mockAuth.syncUser).toHaveBeenCalledWith(ME)
})

it('saves a new name, and keeps the displayed name when saving fails', async () => {
  mockAuth.updateName.mockRejectedValueOnce(new ApiError('/users/me/name', 502, null))
  await renderProfile()
  const user = userEvent.setup()

  await user.press(screen.getByRole('button', { name: '修改姓名' }))
  const field = screen.getByLabelText('姓名')
  await user.clear(field)
  await user.type(field, 'New Name')
  await user.press(screen.getByRole('button', { name: '保存姓名' }))
  expect(await screen.findByText('姓名没有更新：服务暂时不可用，请稍后再试。')).toBeOnTheScreen()
  expect(mockAuth.updateName).toHaveBeenCalledWith('New Name')

  mockAuth.updateName.mockResolvedValueOnce({ ...ME, name: 'New Name' })
  await user.press(screen.getByRole('button', { name: '保存姓名' }))
  expect(await screen.findByText('姓名已更新。')).toBeOnTheScreen()
})

it('validates the email form, keeps passwords hidden, and shows a wrong password as an error', async () => {
  mockAuth.updateEmail.mockRejectedValueOnce(new ApiError('/users/me/email', 400, { message: 'Current password is incorrect' }))
  await renderProfile()
  const user = userEvent.setup()

  await user.press(screen.getByRole('button', { name: '修改邮箱' }))
  const password = screen.getByLabelText('当前密码')
  expect(password).toHaveProp('secureTextEntry', true)
  await user.press(screen.getByRole('button', { name: '保存邮箱' }))
  expect(screen.getByText('请输入密码')).toBeOnTheScreen()
  expect(mockAuth.updateEmail).not.toHaveBeenCalled()

  await user.type(password, 'wrong')
  await user.type(screen.getByLabelText('新邮箱'), 'new@example.test')
  await user.press(screen.getByRole('button', { name: '保存邮箱' }))
  expect(await screen.findByText('邮箱没有更新：当前密码不正确。')).toBeOnTheScreen()
  expect(mockAuth.updateEmail).toHaveBeenCalledWith('wrong', 'new@example.test')
})

it('does not show a success message when the new session could not be stored', async () => {
  mockAuth.updateEmail.mockRejectedValueOnce(new SessionStoreError())
  await renderProfile()
  const user = userEvent.setup()
  await user.press(screen.getByRole('button', { name: '修改邮箱' }))
  await user.type(screen.getByLabelText('当前密码'), 'Secret#123')
  await user.type(screen.getByLabelText('新邮箱'), 'new@example.test')
  await user.press(screen.getByRole('button', { name: '保存邮箱' }))
  await waitFor(() => expect(mockAuth.updateEmail).toHaveBeenCalled())
  expect(screen.queryByText('邮箱已更新。')).not.toBeOnTheScreen()
})

it('refuses a weak new password locally and reports a server rejection without signing out', async () => {
  mockAuth.changePassword.mockRejectedValueOnce(new ApiError('/users/me/password', 400, { message: 'Current password is incorrect' }))
  await renderProfile()
  const user = userEvent.setup()

  await user.press(screen.getByRole('button', { name: '修改密码' }))
  expect(screen.getByLabelText('新密码')).toHaveProp('secureTextEntry', true)
  await user.type(screen.getByLabelText('当前密码'), 'Secret#123')
  await user.type(screen.getByLabelText('新密码'), 'weak')
  await user.press(screen.getByRole('button', { name: '保存密码' }))
  expect(screen.getByText('密码不符合强度要求。')).toBeOnTheScreen()
  expect(mockAuth.changePassword).not.toHaveBeenCalled()

  await user.clear(screen.getByLabelText('新密码'))
  await user.type(screen.getByLabelText('新密码'), 'Newer#4567')
  await user.press(screen.getByRole('button', { name: '保存密码' }))
  expect(await screen.findByText('密码没有修改：当前密码不正确。')).toBeOnTheScreen()
  expect(mockAuth.changePassword).toHaveBeenCalledWith('Secret#123', 'Newer#4567')
  expect(mockAuth.logout).not.toHaveBeenCalled()
})

describe('feedback', () => {
  it('treats "no feedback yet" as empty, and a failed request as a failure with retry', async () => {
    await renderProfile()
    expect(await screen.findByText('你还没有评价。')).toBeOnTheScreen()

    jest.resetAllMocks()
    users.me.mockResolvedValue(ME)
    feedback.me
      .mockRejectedValueOnce(new ApiError('/feedback/me', 502, null))
      .mockResolvedValueOnce({ rating: 4, comment: 'Useful', createdAt: '2026-09-02T00:00:00', updatedAt: '2026-09-03T00:00:00' })
    await renderProfile()
    expect(await screen.findByText('评价暂时无法加载')).toBeOnTheScreen()
    expect(screen.queryByText('你还没有评价。')).not.toBeOnTheScreen()
    await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('你的评分：4 星 · 更新于 2026年9月3日')).toBeOnTheScreen()
  })

  it('needs a rating, and reports a failed save without clearing the form', async () => {
    feedback.save.mockRejectedValueOnce(new ApiError('/feedback/me', 502, null))
    await renderProfile()
    const user = userEvent.setup()
    await screen.findByText('你还没有评价。')

    await user.press(screen.getByRole('button', { name: '提交评价' }))
    expect(screen.getByText('请选择 1-5 星评分。')).toBeOnTheScreen()
    expect(feedback.save).not.toHaveBeenCalled()

    await user.press(screen.getByRole('radio', { name: '5 星' }))
    await user.type(screen.getByLabelText('评价（可选）'), '  Great app  ')
    await user.press(screen.getByRole('button', { name: '提交评价' }))
    expect(await screen.findByText('评价没有保存：服务暂时不可用，请稍后再试。')).toBeOnTheScreen()
    expect(feedback.save).toHaveBeenCalledWith({ rating: 5, comment: 'Great app' })
    expect(screen.getByLabelText('评价（可选）')).toHaveDisplayValue('  Great app  ')

    feedback.save.mockResolvedValueOnce({ rating: 5, comment: 'Great app', createdAt: '2026-09-12T00:00:00', updatedAt: '2026-09-12T00:00:00' })
    await user.press(screen.getByRole('button', { name: '提交评价' }))
    expect(await screen.findByText('感谢你的评价！')).toBeOnTheScreen()
  })
})

describe('logout and account deletion', () => {
  it('logs out only after confirmation', async () => {
    answerDialogs(false)
    await renderProfile()
    const user = userEvent.setup()
    await user.press(screen.getByRole('button', { name: '退出登录' }))
    expect(mockAuth.logout).not.toHaveBeenCalled()

    jest.restoreAllMocks()
    answerDialogs(true)
    await user.press(screen.getByRole('button', { name: '退出登录' }))
    expect(mockAuth.logout).toHaveBeenCalledTimes(1)
  })

  it('explains the shared account and the confirmed scope, and can be cancelled at either step', async () => {
    const alert = answerDialogs(false)
    await renderProfile()
    const user = userEvent.setup()

    expect(screen.getByText('这会注销网站和 App 共用的账户，不只是退出这台手机上的登录。')).toBeOnTheScreen()
    await user.press(screen.getByRole('button', { name: '注销账户' }))
    expect(screen.getByText(/服务器上已保存的简历文件/)).toBeOnTheScreen()

    await user.press(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByText(/服务器上已保存的简历文件/)).not.toBeOnTheScreen()

    await user.press(screen.getByRole('button', { name: '注销账户' }))
    await user.press(screen.getByRole('button', { name: '永久注销账户' }))
    expect(alert).toHaveBeenCalledWith('最后确认', '确定要永久注销 nb@example.test 这个账户吗？', expect.any(Array), expect.anything())
    expect(mockAuth.deleteAccount).not.toHaveBeenCalled()
  })

  it('deletes after both confirmations, and keeps the account with an error when it fails', async () => {
    answerDialogs(true)
    mockAuth.deleteAccount.mockRejectedValueOnce(new ApiError('/users/me', 500, null))
    await renderProfile()
    const user = userEvent.setup()

    await user.press(screen.getByRole('button', { name: '注销账户' }))
    await user.press(screen.getByRole('button', { name: '永久注销账户' }))
    expect(await screen.findByText('注销没有成功，账户仍然保留：请求失败，请稍后重试。')).toBeOnTheScreen()
    expect(mockAuth.deleteAccount).toHaveBeenCalledTimes(1)

    mockAuth.deleteAccount.mockResolvedValueOnce(undefined)
    await user.press(screen.getByRole('button', { name: '永久注销账户' }))
    expect(mockAuth.deleteAccount).toHaveBeenCalledTimes(2)
  })
})
