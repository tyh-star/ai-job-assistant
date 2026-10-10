import { Text } from 'react-native'
import { render, screen } from '@testing-library/react-native'
import SignedInLayout from '../app/(app)/_layout'

const mockAuth = {
  status: 'signedIn' as 'signedIn' | 'signedOut' | 'restoring',
  signOutReason: null as null | 'expired' | 'passwordChanged' | 'accountDeleted' | 'sessionStoreFailed',
}

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => mockAuth,
}))

jest.mock('expo-router', () => {
  const { Text: MockText } = jest.requireActual('react-native')
  return {
    Redirect: ({ href }: { href: string }) => <MockText>{`redirect:${href}`}</MockText>,
    Stack: () => <MockText>signed-in stack</MockText>,
    usePathname: () => '/draft/12/edit',
  }
})

it('returns to the same screen after logging in again when the session expired (401)', async () => {
  mockAuth.status = 'signedOut'
  mockAuth.signOutReason = 'expired'
  await render(<SignedInLayout />)
  expect(screen.getByText('redirect:/login?returnTo=%2Fdraft%2F12%2Fedit')).toBeOnTheScreen()
  expect(screen.queryByText('signed-in stack')).not.toBeOnTheScreen()
})

it('shows the login notice when the app signed out for another reason', async () => {
  mockAuth.status = 'signedOut'
  mockAuth.signOutReason = 'passwordChanged'
  await render(<SignedInLayout />)
  expect(screen.getByText('redirect:/login')).toBeOnTheScreen()
})

it('sends a plain logout or a visitor to the public welcome page, not a login wall', async () => {
  mockAuth.status = 'signedOut'
  mockAuth.signOutReason = null
  await render(<SignedInLayout />)
  expect(screen.getByText('redirect:/welcome')).toBeOnTheScreen()
})

it('renders the app for a signed-in user', async () => {
  mockAuth.status = 'signedIn'
  await render(<SignedInLayout />)
  expect(screen.getByText('signed-in stack')).toBeOnTheScreen()
  expect(Text).toBeDefined()
})
