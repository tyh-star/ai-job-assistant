import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { Spinner } from '../components/Spinner'

export function LoginPage() {
  const { t } = useTranslation()
  const { isAuthenticated, login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  if (isAuthenticated) {
    return <Navigate to="/" replace />
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
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell>
      <h1 className="text-2xl font-semibold text-slate-900">{t('auth.loginTitle')}</h1>
      <p className="mt-1 text-sm text-slate-500">{t('app.tagline')}</p>
      <form className="mt-6 space-y-4" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        <label className="block text-sm">
          <span className="text-slate-700">{t('auth.email')}</span>
          <input
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
          />
        </label>
        <label className="block text-sm">
          <span className="text-slate-700">{t('auth.password')}</span>
          <input
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-slate-900 px-4 py-2 text-white disabled:opacity-60"
        >
          {loading ? t('common.loading') : t('auth.submitLogin')}
        </button>
      </form>
      {loading ? <div className="mt-4"><Spinner label={t('common.loading')} /></div> : null}
      <p className="mt-6 text-sm text-slate-600">
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
    <div className="flex min-h-screen flex-col bg-slate-50">
      <div className="flex justify-end px-6 py-4">
        <LanguageSwitch />
      </div>
      <div className="flex flex-1 items-start justify-center px-4 pb-16">
        <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 shadow-sm">
          <p className="text-sm font-medium text-slate-500">{t('app.name')}</p>
          {children}
        </div>
      </div>
    </div>
  )
}
