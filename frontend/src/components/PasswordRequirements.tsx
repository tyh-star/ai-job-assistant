import { forwardRef, useId, useImperativeHandle, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { evaluatePassword, type PasswordRuleId } from '../auth/passwordStrength'
import { cx } from './ui/cx'

const RULES: PasswordRuleId[] = ['length', 'upper', 'lower', 'digit', 'special', 'noSpaceOrControl']

export type PasswordRequirementsHandle = {
  reportInvalidSubmit: () => void
}

type PasswordRequirementsProps = {
  password: string
  onPasswordChange: (value: string) => void
  label: string
  id?: string
  autoComplete?: string
  inputClassName: string
  labelClassName: string
  disabled?: boolean
}

export const PasswordRequirements = forwardRef<PasswordRequirementsHandle, PasswordRequirementsProps>(
  function PasswordRequirements(
    {
      password,
      onPasswordChange,
      label,
      id,
      autoComplete = 'new-password',
      inputClassName,
      labelClassName,
      disabled = false,
    },
    ref,
  ) {
    const { t } = useTranslation()
    const generatedId = useId()
    const inputId = id ?? generatedId
    const listId = `${inputId}-rules`
    const successId = `${inputId}-ok`
    const inputRef = useRef<HTMLInputElement>(null)
    const [focused, setFocused] = useState(false)
    const [submitAttempted, setSubmitAttempted] = useState(false)
    const [passwordVisible, setPasswordVisible] = useState(false)

    const state = evaluatePassword(password)
    const errorMode = submitAttempted && !state.strong
    const showList = errorMode || (password.length > 0 && !(state.strong && !focused))
    const showSuccess = password.length > 0 && state.strong && !focused

    useImperativeHandle(ref, () => ({
      reportInvalidSubmit() {
        setSubmitAttempted(true)
        inputRef.current?.focus()
      },
    }))

    function handleChange(value: string) {
      if (value.length === 0) {
        setSubmitAttempted(false)
      }
      onPasswordChange(value)
    }

    function describedBy() {
      if (showList) {
        return listId
      }
      if (showSuccess) {
        return successId
      }
      return undefined
    }

    return (
      <div className="min-w-0">
        <label className={labelClassName} htmlFor={inputId}>
          <span className="text-ink">{label}</span>
        </label>
        <div className="relative mt-1">
          <input
            ref={inputRef}
            id={inputId}
            className={cx(inputClassName, 'pr-12')}
            type={passwordVisible ? 'text' : 'password'}
            value={password}
            onChange={(event) => handleChange(event.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            autoComplete={autoComplete}
            disabled={disabled}
            aria-invalid={errorMode || undefined}
            aria-describedby={describedBy()}
          />
          <button
            type="button"
            className="absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-md text-muted hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent disabled:opacity-60"
            aria-label={passwordVisible ? t('auth.hidePassword') : t('auth.showPassword')}
            aria-pressed={passwordVisible}
            aria-controls={inputId}
            disabled={disabled}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => setPasswordVisible((open) => !open)}
          >
            {passwordVisible ? <EyeOffIcon /> : <EyeIcon />}
          </button>
        </div>
        {showList ? (
          <ul
            id={listId}
            className="mt-2 space-y-1 text-sm"
            aria-label={t('auth.passwordRulesLabel')}
          >
            {RULES.map((rule) => {
              const ok = state[rule]
              const tone = ok ? 'success' : errorMode ? 'danger' : 'muted'
              return (
                <li
                  key={rule}
                  className={cx(
                    'flex min-w-0 items-start gap-2 break-words',
                    tone === 'success' && 'text-success',
                    tone === 'muted' && 'text-muted',
                    tone === 'danger' && 'text-danger',
                  )}
                  data-met={ok ? 'true' : 'false'}
                  data-tone={tone}
                >
                  <span aria-hidden="true" className="mt-0.5 shrink-0">
                    {ok ? '✓' : errorMode ? '!' : '○'}
                  </span>
                  <span>{t(`auth.passwordRule.${rule}`)}</span>
                </li>
              )
            })}
          </ul>
        ) : null}
        {showSuccess ? (
          <p id={successId} className="mt-2 flex items-center gap-2 text-sm text-success" role="status">
            <span aria-hidden="true">✓</span>
            {t('auth.passwordMeetsRequirements')}
          </p>
        ) : null}
      </div>
    )
  },
)

function EyeIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M2.5 12s3.5-7 9.5-7 9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7Z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  )
}

function EyeOffIcon() {
  return (
    <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <path d="M3 3l18 18" />
      <path d="M10.6 10.6A3 3 0 0 0 12 15a3 3 0 0 0 2.4-1.2" />
      <path d="M9.9 5.2A11 11 0 0 1 12 5c6 0 9.5 7 9.5 7a16 16 0 0 1-3.2 3.8" />
      <path d="M6.5 6.7A16 16 0 0 0 2.5 12s3.5 7 9.5 7c1.3 0 2.5-.2 3.6-.6" />
    </svg>
  )
}
