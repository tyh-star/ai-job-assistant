import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import { AuthShell } from './LoginPage'

export function RegisterPage() {
  const { t } = useTranslation()
  const { isAuthenticated, register } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  if (isAuthenticated) {
    return <Navigate to="/" replace />
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) {
      setError(t('auth.nameRequired'))
      return
    }
    if (!email.trim()) {
      setError(t('auth.emailRequired'))
      return
    }
    if (!email.includes('@')) {
      setError(t('auth.emailInvalid'))
      return
    }
    if (password.length < 8) {
      setError(t('auth.passwordMin'))
      return
    }
    setLoading(true)
    setError('')
    try {
      await register(name.trim(), email.trim(), password)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell>
      <h1 className="text-2xl font-semibold text-slate-900">{t('auth.registerTitle')}</h1>
      <form className="mt-6 space-y-4" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        <label className="block text-sm">
          <span className="text-slate-700">{t('auth.name')}</span>
          <input
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="name"
          />
        </label>
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
            autoComplete="new-password"
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-slate-900 px-4 py-2 text-white disabled:opacity-60"
        >
          {loading ? t('common.loading') : t('auth.submitRegister')}
        </button>
      </form>
      {loading ? <div className="mt-4"><Spinner label={t('common.loading')} /></div> : null}
      <p className="mt-6 text-sm text-slate-600">
        {t('auth.hasAccount')}{' '}
        <Link className="font-medium text-slate-900 underline" to="/login">
          {t('auth.goLogin')}
        </Link>
      </p>
    </AuthShell>
  )
}
