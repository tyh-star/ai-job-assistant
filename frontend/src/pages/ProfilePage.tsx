import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { changePassword as changePasswordApi, getMe } from '../api/auth'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { evaluatePassword } from '../auth/passwordStrength'
import { Alert } from '../components/Alert'
import { FeedbackForm } from '../components/FeedbackForm'
import { PasswordChecklist } from '../components/PasswordChecklist'
import { Spinner } from '../components/Spinner'

const SECTION_CLASS = 'rounded-xl border border-slate-200 bg-white p-6'
const LABEL_CLASS = 'block text-sm text-slate-700'
const INPUT_CLASS = 'mt-1 min-h-11 w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm'
const SUBMIT_CLASS = 'min-h-11 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60'
const SUCCESS_CLASS = 'rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900'
const NAME_MAX_LENGTH = 100

export function ProfilePage() {
  const { t, i18n } = useTranslation()
  const { user } = useAuth()

  const [createdAt, setCreatedAt] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setLoadError('')
      try {
        const { data } = await getMe()
        if (!cancelled) {
          setCreatedAt(data.createdAt)
        }
      } catch (err) {
        if (!cancelled) {
          setLoadError(t(apiErrorKey(err)))
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [t])

  return (
    <div className="max-w-2xl">
      <h1 className="text-2xl font-semibold text-slate-900">{t('profile.title')}</h1>

      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {loadError ? <div className="mt-6"><Alert message={loadError} /></div> : null}

      {!loading && !loadError ? (
        <div className="mt-6 space-y-6">
          <section className={SECTION_CLASS}>
            <h2 className="text-lg font-medium text-slate-900">{t('profile.accountInfo')}</h2>
            <dl className="mt-3 space-y-2 text-sm">
              <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
                <dt className="text-slate-500">{t('profile.email')}</dt>
                <dd className="min-w-0 break-all text-slate-900">{user?.email}</dd>
              </div>
              <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
                <dt className="text-slate-500">{t('profile.registeredAt')}</dt>
                <dd className="text-slate-900">
                  {createdAt ? new Date(createdAt).toLocaleDateString(i18n.language) : '—'}
                </dd>
              </div>
            </dl>
          </section>

          <NameSection />
          <EmailSection />
          <PasswordSection />
          <FeedbackForm />
        </div>
      ) : null}
    </div>
  )
}

function NameSection() {
  const { t } = useTranslation()
  const { user, updateName } = useAuth()
  const [name, setName] = useState(user?.name ?? '')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [saving, setSaving] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError(t('auth.nameRequired'))
      setSuccess(false)
      return
    }
    if (trimmed.length > NAME_MAX_LENGTH) {
      setError(t('profile.nameTooLong'))
      setSuccess(false)
      return
    }
    setSaving(true)
    setError('')
    setSuccess(false)
    try {
      await updateName(trimmed)
      setName(trimmed)
      setSuccess(true)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className={SECTION_CLASS}>
      <h2 className="text-lg font-medium text-slate-900">{t('profile.nameSection')}</h2>
      <p className="mt-1 text-sm text-slate-500">{t('profile.nameHint')}</p>
      <form className="mt-4 space-y-3" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        {success ? <div className={SUCCESS_CLASS}>{t('profile.nameSaved')}</div> : null}
        <label className={LABEL_CLASS}>
          <span>{t('profile.name')}</span>
          <input
            className={INPUT_CLASS}
            value={name}
            maxLength={NAME_MAX_LENGTH}
            onChange={(event) => {
              setName(event.target.value)
              setSuccess(false)
            }}
            autoComplete="name"
          />
        </label>
        <button type="submit" className={SUBMIT_CLASS} disabled={saving}>
          {saving ? t('common.saving') : t('profile.saveName')}
        </button>
      </form>
    </section>
  )
}

function EmailSection() {
  const { t } = useTranslation()
  const { updateEmail } = useAuth()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [saving, setSaving] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!currentPassword) {
      setError(t('auth.passwordRequired'))
      setSuccess(false)
      return
    }
    const trimmedEmail = newEmail.trim()
    if (!trimmedEmail || !trimmedEmail.includes('@')) {
      setError(t('auth.emailInvalid'))
      setSuccess(false)
      return
    }
    setSaving(true)
    setError('')
    setSuccess(false)
    try {
      await updateEmail(currentPassword, trimmedEmail)
      setCurrentPassword('')
      setNewEmail('')
      setSuccess(true)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className={SECTION_CLASS}>
      <h2 className="text-lg font-medium text-slate-900">{t('profile.emailSection')}</h2>
      <p className="mt-1 text-sm text-slate-500">{t('profile.emailHint')}</p>
      <form className="mt-4 space-y-3" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        {success ? <div className={SUCCESS_CLASS}>{t('profile.emailSaved')}</div> : null}
        <label className={LABEL_CLASS}>
          <span>{t('profile.currentPassword')}</span>
          <input
            className={INPUT_CLASS}
            type="password"
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            autoComplete="current-password"
          />
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('profile.newEmail')}</span>
          <input
            className={INPUT_CLASS}
            type="email"
            value={newEmail}
            onChange={(event) => setNewEmail(event.target.value)}
            autoComplete="email"
          />
        </label>
        <button type="submit" className={SUBMIT_CLASS} disabled={saving}>
          {saving ? t('common.saving') : t('profile.saveEmail')}
        </button>
      </form>
    </section>
  )
}

function PasswordSection() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { logout } = useAuth()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [saving, setSaving] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!currentPassword) {
      setError(t('auth.passwordRequired'))
      setSuccess(false)
      return
    }
    if (!evaluatePassword(newPassword).strong) {
      setError(t('errors.weakPassword'))
      setSuccess(false)
      return
    }
    setSaving(true)
    setError('')
    setSuccess(false)
    try {
      // Never log currentPassword/newPassword -- see changePassword's javadoc chain
      // (UserController -> UserServiceImpl) for why.
      await changePasswordApi({ currentPassword, newPassword })
      setCurrentPassword('')
      setNewPassword('')
      setSuccess(true)
      // auth_version bump invalidates every previously issued JWT, including other
      // devices. This browser still needs an explicit logout because the token is
      // already stored in localStorage.
      setSaving(false)
      window.setTimeout(() => {
        logout()
        navigate('/login', { replace: true })
      }, 1800)
    } catch (err) {
      setError(t(apiErrorKey(err)))
      setSaving(false)
    }
  }

  return (
    <section className={SECTION_CLASS}>
      <h2 className="text-lg font-medium text-slate-900">{t('profile.passwordSection')}</h2>
      <p className="mt-1 text-sm text-slate-500">{t('profile.passwordHint')}</p>
      <form className="mt-4 space-y-3" onSubmit={onSubmit}>
        {error ? <Alert message={error} /> : null}
        {success ? <div className={SUCCESS_CLASS}>{t('profile.passwordSavedRedirecting')}</div> : null}
        <label className={LABEL_CLASS}>
          <span>{t('profile.currentPassword')}</span>
          <input
            className={INPUT_CLASS}
            type="password"
            value={currentPassword}
            onChange={(event) => setCurrentPassword(event.target.value)}
            autoComplete="current-password"
            disabled={success}
          />
        </label>
        <label className={LABEL_CLASS}>
          <span>{t('profile.newPassword')}</span>
          <input
            className={INPUT_CLASS}
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            autoComplete="new-password"
            disabled={success}
          />
        </label>
        <PasswordChecklist password={newPassword} />
        <button type="submit" className={SUBMIT_CLASS} disabled={saving || success}>
          {saving ? t('common.saving') : t('profile.savePassword')}
        </button>
      </form>
    </section>
  )
}
