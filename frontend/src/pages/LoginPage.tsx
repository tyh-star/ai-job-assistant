import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { sanitizeReturnTo } from '../auth/returnTo'
import { Alert } from '../components/Alert'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { Spinner } from '../components/Spinner'

export const AUTH_SHELL_CLASS =
  'auth-shell flex min-h-screen flex-col bg-slate-50'
export const AUTH_CARD_CLASS =
  'auth-card w-full max-w-[min(92vw,540px)] rounded-2xl border border-slate-200 bg-white px-8 py-10 shadow-sm sm:px-10'
export const AUTH_TITLE_CLASS =
  'auth-title text-3xl font-semibold tracking-tight text-slate-900'
export const AUTH_SUBTITLE_CLASS = 'auth-subtitle mt-2 text-base text-slate-500'
export const AUTH_LABEL_CLASS = 'auth-label block text-base'
export const AUTH_INPUT_CLASS =
  'auth-input mt-1 min-h-12 w-full rounded-lg border border-slate-300 px-3 py-3 text-base'
export const AUTH_SUBMIT_CLASS =
  'auth-submit min-h-12 w-full rounded-lg bg-slate-900 px-4 py-3 text-base font-medium text-white disabled:opacity-60'
export const AUTH_FOOTER_CLASS = 'auth-footer mt-6 text-base text-slate-600'

export function LoginPage() {
  const { t } = useTranslation()
  const { isAuthenticated, login } = useAuth()
  const [searchParams] = useSearchParams()
  const returnTo = sanitizeReturnTo(searchParams.get('returnTo'))
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // The axios interceptor's own forced redirect (see api/client.ts) carries this so
  // the visitor sees an honest reason, not a blank form that looks like nothing
  // happened -- initialized once from the URL, not re-read on every render, so it
  // does not reappear after the user has cleared or replaced it with their own error.
  const [error, setError] = useState(() =>
    searchParams.get('reason') === 'session_expired' ? t('auth.sessionExpired') : '',
  )
  const [loading, setLoading] = useState(false)

  if (isAuthenticated) {
    return <Navigate to={returnTo} replace />
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!email.trim()) {
      setError(t('auth.emailRequired'))
      return
    }
    if (!email.includes('@')) {
      setError(t('auth.emailInvalid'))
      return
    }
    if (!password) {
      setError(t('auth.passwordRequired'))
      return
    }
    setLoading(true)
    setError('')
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
      setError(status === 401 ? t('auth.invalidCredentials') : t(apiErrorKey(err)))
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
          <span className="text-slate-700">{t('auth.email')}</span>
          <input
            className={AUTH_INPUT_CLASS}
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
          />
        </label>
        <label className={AUTH_LABEL_CLASS}>
          <span className="text-slate-700">{t('auth.password')}</span>
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
        <Link className="font-medium text-slate-900 underline" to="/register">
          {t('auth.goRegister')}
        </Link>
      </p>
    </AuthShell>
  )
}

export function AuthShell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  return (
    <div className={AUTH_SHELL_CLASS} data-testid="auth-shell">
      <div className="flex justify-end px-6 py-4">
        <LanguageSwitch size="auth" />
      </div>
      <div className="flex flex-1 items-center justify-center px-4 pb-16">
        <div className={AUTH_CARD_CLASS} data-testid="auth-card">
          <p className="text-base font-medium text-slate-500">{t('app.name')}</p>
          {children}
        </div>
      </div>
    </div>
  )
}
