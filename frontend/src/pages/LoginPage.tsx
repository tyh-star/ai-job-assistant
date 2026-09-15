import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { LOGIN_REASON_EXPIRED, LOGIN_REASON_REQUIRED, sanitizeReturnTo } from '../auth/returnTo'
import { Alert } from '../components/Alert'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { Spinner } from '../components/Spinner'
import { BrandMark } from '../components/ui/BrandMark'

export const AUTH_SHELL_CLASS =
  'auth-shell flex min-h-screen flex-col bg-gradient-to-br from-slate-50 via-sky-50 to-indigo-50'
export const AUTH_CARD_CLASS =
  'auth-card w-full max-w-[min(92vw,540px)] rounded-2xl border border-line bg-card px-8 py-10 shadow-card sm:px-10'
export const AUTH_TITLE_CLASS =
  'auth-title text-3xl font-semibold tracking-tight text-ink'
export const AUTH_SUBTITLE_CLASS = 'auth-subtitle mt-2 text-base text-muted'
export const AUTH_LABEL_CLASS = 'auth-label block text-base'
export const AUTH_INPUT_CLASS =
  'auth-input mt-1 min-h-12 w-full rounded-lg border border-line px-3 py-3 text-base'
export const AUTH_SUBMIT_CLASS =
  'auth-submit min-h-12 w-full rounded-lg bg-accent px-4 py-3 text-base font-medium text-white hover:bg-brand disabled:opacity-60'
export const AUTH_FOOTER_CLASS = 'auth-footer mt-6 text-base text-muted'

export function LoginPage() {
  const { t } = useTranslation()
  const { isAuthenticated, login } = useAuth()
  const [searchParams] = useSearchParams()
  const returnTo = sanitizeReturnTo(searchParams.get('returnTo'))
  const reason = searchParams.get('reason')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [formError, setFormError] = useState('')
  const [loading, setLoading] = useState(false)

  const reasonNotice =
    reason === LOGIN_REASON_EXPIRED
      ? t('auth.sessionExpired')
      : reason === LOGIN_REASON_REQUIRED
        ? t('auth.loginRequired')
        : ''
  const error = formError || reasonNotice

  if (isAuthenticated) {
    return <Navigate to={returnTo} replace />
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!email.trim()) {
      setFormError(t('auth.emailRequired'))
      return
    }
    if (!email.includes('@')) {
      setFormError(t('auth.emailInvalid'))
      return
    }
    if (!password) {
      setFormError(t('auth.passwordRequired'))
      return
    }
    setLoading(true)
    setFormError('')
    try {
      await login(email.trim(), password)
    } catch (err) {
      // A 401 HERE is always this login attempt's own wrong-credentials rejection
      // (see rule 4 in api/client.ts's handleResponseError -- /auth/login's own 401
      // never goes through that generic handling), so it must show the real
      // "wrong email or password" message, never apiErrorKey's generic
      // "errors.unauthorized" ("your session expired"), which is correct for a 401
      // from any OTHER call but actively misleading here -- this login never had a
      // session to expire in the first place.
      const status = (err as { response?: { status?: number } }).response?.status
      setFormError(status === 401 ? t('auth.invalidCredentials') : t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell>
      <h1 className={AUTH_TITLE_CLASS}>{t('auth.loginTitle')}</h1>
      <p className={AUTH_SUBTITLE_CLASS}>{t('app.tagline')}</p>
      <form className="mt-6 space-y-4" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        <label className={AUTH_LABEL_CLASS}>
          <span className="text-ink">{t('auth.email')}</span>
          <input
            className={AUTH_INPUT_CLASS}
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
          />
        </label>
        <label className={AUTH_LABEL_CLASS}>
          <span className="text-ink">{t('auth.password')}</span>
          <input
            className={AUTH_INPUT_CLASS}
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          className={AUTH_SUBMIT_CLASS}
        >
          {loading ? t('common.loading') : t('auth.submitLogin')}
        </button>
      </form>
      {loading ? <div className="mt-4"><Spinner label={t('common.loading')} /></div> : null}
      <p className={AUTH_FOOTER_CLASS}>
        {t('auth.noAccount')}{' '}
        <Link
          className="font-medium text-brand underline"
          to={`/register?returnTo=${encodeURIComponent(returnTo)}`}
        >
          {t('auth.goRegister')}
        </Link>
      </p>
    </AuthShell>
  )
}

export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className={AUTH_SHELL_CLASS} data-testid="auth-shell">
      <div className="flex justify-end px-6 py-4">
        <LanguageSwitch size="auth" />
      </div>
      <div className="flex flex-1 items-center justify-center px-4 pb-16">
        <div className={AUTH_CARD_CLASS} data-testid="auth-card">
          <Link to="/" className="inline-block">
            <BrandMark />
          </Link>
          {children}
        </div>
      </div>
    </div>
  )
}
