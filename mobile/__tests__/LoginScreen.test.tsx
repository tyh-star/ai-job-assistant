import '../src/i18n'
import { render, screen, userEvent } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { LoginScreen } from '../src/screens/LoginScreen'

const mockAuth = {
  login: jest.fn(),
  signOutReason: null as null | 'expired' | 'passwordChanged' | 'accountDeleted' | 'sessionStoreFailed',
  acknowledgeSignOutReason: jest.fn(),
}

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => mockAuth,
}))

beforeEach(() => {
  mockAuth.login.mockReset()
  mockAuth.signOutReason = null
})

it('validates both fields before calling the API', async () => {
  const user = userEvent.setup()
  await render(<LoginScreen onGoRegister={jest.fn()} />)
  await user.press(screen.getByRole('button', { name: '登录' }))
  expect(screen.getByText('请输入邮箱')).toBeOnTheScreen()
  expect(screen.getByText('请输入密码')).toBeOnTheScreen()
  expect(mockAuth.login).not.toHaveBeenCalled()
})

it('shows the wrong-credentials message for a login 401', async () => {
  mockAuth.login.mockRejectedValue(new ApiError('/auth/login', 401, { message: 'Invalid email or password' }))
  const user = userEvent.setup()
  await render(<LoginScreen onGoRegister={jest.fn()} />)
  await user.type(screen.getByLabelText('邮箱'), 'nb@example.com')
  await user.type(screen.getByLabelText('密码'), 'not-the-password')
  await user.press(screen.getByRole('button', { name: '登录' }))
  expect(await screen.findByText('邮箱或密码不正确。')).toBeOnTheScreen()
  expect(mockAuth.login).toHaveBeenCalledWith('nb@example.com', 'not-the-password')
})

it('explains why the user was signed out', async () => {
  mockAuth.signOutReason = 'expired'
  await render(<LoginScreen onGoRegister={jest.fn()} />)
  expect(screen.getByText('登录状态已过期，请重新登录。')).toBeOnTheScreen()
})

it('asks for a fresh login after a password change or an account deletion', async () => {
  mockAuth.signOutReason = 'passwordChanged'
  await render(<LoginScreen onGoRegister={jest.fn()} />)
  expect(screen.getByText('密码已修改。为了安全，请用新密码重新登录。')).toBeOnTheScreen()

  mockAuth.signOutReason = 'accountDeleted'
  await render(<LoginScreen onGoRegister={jest.fn()} />)
  expect(screen.getByText('账户已注销。')).toBeOnTheScreen()
})
