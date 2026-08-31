import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import {
  AUTH_FOOTER_CLASS,
  AUTH_INPUT_CLASS,
  AUTH_LABEL_CLASS,
  AUTH_SUBMIT_CLASS,
  AUTH_TITLE_CLASS,
  AuthShell,
} from './LoginPage'

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
      <h1 className={AUTH_TITLE_CLASS}>{t('auth.registerTitle')}</h1>
      <form className="mt-6 space-y-4" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        <label className={AUTH_LABEL_CLASS}>
          <span className="text-slate-700">{t('auth.name')}</span>
          <input
            className={AUTH_INPUT_CLASS}
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="name"
          />
        </label>
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
            autoComplete="new-password"
          />
        </label>
        <button
          type="submit"
          disabled={loading}
          className={AUTH_SUBMIT_CLASS}
        >
          {loading ? t('common.loading') : t('auth.submitRegister')}
        </button>
      </form>
      {loading ? <div className="mt-4"><Spinner label={t('common.loading')} /></div> : null}
      <p className={AUTH_FOOTER_CLASS}>
        {t('auth.hasAccount')}{' '}
        <Link className="font-medium text-slate-900 underline" to="/login">
          {t('auth.goLogin')}
        </Link>
      </p>
    </AuthShell>
  )
}
