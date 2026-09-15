import { useRef, useState, type FormEvent } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import i18n from '../i18n'
import { evaluatePassword } from '../auth/passwordStrength'
import {
  PasswordRequirements,
  type PasswordRequirementsHandle,
} from './PasswordRequirements'

const STRONG = 'NewPassword456!'

function Harness({
  onSubmit,
}: {
  onSubmit?: (password: string) => void
} = {}) {
  const ref = useRef<PasswordRequirementsHandle>(null)
  const [password, setPassword] = useState('')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!evaluatePassword(password).strong) {
      ref.current?.reportInvalidSubmit()
      return
    }
    onSubmit?.(password)
  }

  return (
    <I18nextProvider i18n={i18n}>
      <form onSubmit={handleSubmit}>
        <PasswordRequirements
          ref={ref}
          password={password}
          onPasswordChange={setPassword}
          label="Password"
          labelClassName="block text-base"
          inputClassName="min-h-12 w-full rounded-lg border px-3"
        />
        <button type="submit">Submit</button>
      </form>
    </I18nextProvider>
  )
}

function rulesList() {
  return screen.queryByRole('list', { name: 'Password requirements' })
}

describe('PasswordRequirements', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  afterEach(async () => {
    cleanup()
    await i18n.changeLanguage('en')
  })

  it('hides the rules until the first character is typed', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    expect(rulesList()).not.toBeInTheDocument()
    expect(screen.queryByText('8–72 characters')).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('Password'))
    expect(rulesList()).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('Password'), 'a')
    expect(rulesList()).toBeInTheDocument()
    expect(screen.getByText('8–72 characters').closest('li')).toHaveAttribute('data-tone', 'muted')
    expect(screen.getByText('At least one lowercase letter').closest('li')).toHaveAttribute('data-met', 'true')
    expect(screen.getByText('At least one lowercase letter').closest('li')).toHaveAttribute('data-tone', 'success')
  })

  it('turns each rule green as it is met, without using error styling while typing', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const input = screen.getByLabelText('Password')
    await user.type(input, STRONG)
    const list = rulesList()
    expect(list).toBeInTheDocument()
    for (const item of within(list!).getAllByRole('listitem')) {
      expect(item).toHaveAttribute('data-met', 'true')
      expect(item).toHaveAttribute('data-tone', 'success')
    }
    expect(screen.queryByText('Password meets the requirements')).not.toBeInTheDocument()
  })

  it('collapses to a success message after a valid password blurs', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.type(screen.getByLabelText('Password'), STRONG)
    await user.tab()
    expect(rulesList()).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Password meets the requirements')
  })

  it('keeps showing unmet rules after an invalid password blurs', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.tab()
    expect(rulesList()).toBeInTheDocument()
    expect(screen.getByText('8–72 characters').closest('li')).toHaveAttribute('data-tone', 'muted')
    expect(screen.queryByText('Password meets the requirements')).not.toBeInTheDocument()
  })

  it('hides the rules again after the password is cleared without submitting', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const input = screen.getByLabelText('Password')
    await user.type(input, 'short')
    expect(rulesList()).toBeInTheDocument()
    await user.clear(input)
    expect(rulesList()).not.toBeInTheDocument()
  })

  it('expands unmet rules in red and focuses the input after an invalid submit', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Submit' }))
    expect(rulesList()).toBeInTheDocument()
    expect(screen.getByText('8–72 characters').closest('li')).toHaveAttribute('data-tone', 'danger')
    expect(screen.getByText('At least one lowercase letter').closest('li')).toHaveAttribute('data-tone', 'success')
    expect(screen.getByLabelText('Password')).toHaveFocus()
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-invalid', 'true')
  })

  it('toggles visibility without changing the password value, including from the keyboard', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const input = screen.getByLabelText('Password')
    await user.type(input, STRONG)
    expect(input).toHaveValue(STRONG)
    expect(input).toHaveAttribute('type', 'password')

    const toggle = screen.getByRole('button', { name: 'Show password' })
    await user.click(toggle)
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveValue(STRONG)
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true')

    await user.tab()
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveValue(STRONG)
  })

  it('uses Chinese aria-labels and success copy', async () => {
    await i18n.changeLanguage('zh-CN')
    const user = userEvent.setup()
    function ZhHarness() {
      const [password, setPassword] = useState('')
      return (
        <I18nextProvider i18n={i18n}>
          <PasswordRequirements
            password={password}
            onPasswordChange={setPassword}
            label="密码"
            labelClassName="block"
            inputClassName="w-full"
          />
        </I18nextProvider>
      )
    }
    render(<ZhHarness />)
    expect(screen.getByRole('button', { name: '显示密码' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('密码'), STRONG)
    await user.tab()
    expect(screen.getByRole('status')).toHaveTextContent('密码强度符合要求')
  })
})
