import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { changePassword as changePasswordApi, getMe } from '../api/auth'
import { apiErrorKey } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { evaluatePassword } from '../auth/passwordStrength'
import { Alert } from '../components/Alert'
import { FeedbackForm } from '../components/FeedbackForm'
import { PasswordRequirements, type PasswordRequirementsHandle } from '../components/PasswordRequirements'
import { Spinner } from '../components/Spinner'

const SECTION_CLASS = 'rounded-lg border border-line bg-card p-6 shadow-card'
const LABEL_CLASS = 'block text-sm text-ink'
const INPUT_CLASS = 'ui-input mt-1'
const SUBMIT_CLASS = 'min-h-11 rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-brand disabled:opacity-60'
const SUCCESS_CLASS = 'rounded-md border border-success/30 bg-success-soft px-4 py-3 text-sm text-success'
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
      <h1 className="page-title">{t('profile.title')}</h1>

      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {loadError ? <div className="mt-6"><Alert message={loadError} /></div> : null}

      {!loading && !loadError ? (
        <div className="mt-6 space-y-6">
          <section className={SECTION_CLASS}>
            <CardHeading icon="user" title={t('profile.accountInfo')} hint={t('profile.accountInfoHint')} />
            <dl className="mt-3 space-y-2 text-sm">
              <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
                <dt className="text-muted">{t('profile.email')}</dt>
                <dd className="min-w-0 break-all text-ink">{user?.email}</dd>
              </div>
              <div className="flex flex-wrap justify-between gap-x-4 gap-y-1">
                <dt className="text-muted">{t('profile.registeredAt')}</dt>
                <dd className="text-ink">
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
      <CardHeading icon="id" title={t('profile.nameSection')} hint={t('profile.nameHint')} />
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
      <CardHeading icon="mail" title={t('profile.emailSection')} hint={t('profile.emailHint')} />
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
  const passwordFieldRef = useRef<PasswordRequirementsHandle>(null)

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
      passwordFieldRef.current?.reportInvalidSubmit()
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
      <CardHeading icon="lock" title={t('profile.passwordSection')} hint={t('profile.passwordHint')} />
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
        <PasswordRequirements
          ref={passwordFieldRef}
          label={t('profile.newPassword')}
          password={newPassword}
          onPasswordChange={(value) => {
            setNewPassword(value)
            setSuccess(false)
          }}
          labelClassName={LABEL_CLASS}
          inputClassName="ui-input"
          autoComplete="new-password"
          disabled={success}
        />
        <button type="submit" className={SUBMIT_CLASS} disabled={saving || success}>
          {saving ? t('common.saving') : t('profile.savePassword')}
        </button>
      </form>
    </section>
  )
}

function CardHeading({
  icon,
  title,
  hint,
}: {
  icon: 'user' | 'id' | 'mail' | 'lock'
  title: string
  hint?: string
}) {
  return (
    <div className="flex gap-3">
      <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-accent-soft text-brand" aria-hidden="true">
        {icon === 'user' ? (
          <svg className="h-4 w-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="10" cy="7" r="3" /><path d="M4 16c0-3 2.7-5 6-5s6 2 6 5" /></svg>
        ) : null}
        {icon === 'id' ? (
          <svg className="h-4 w-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="3" y="5" width="14" height="10" rx="1.5" /><path d="M7 9h6M7 12h4" /></svg>
        ) : null}
        {icon === 'mail' ? (
          <svg className="h-4 w-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="3" y="5" width="14" height="10" rx="1.5" /><path d="m4 6 6 5 6-5" /></svg>
        ) : null}
        {icon === 'lock' ? (
          <svg className="h-4 w-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="5" y="9" width="10" height="8" rx="1.5" /><path d="M7.5 9V7a2.5 2.5 0 0 1 5 0v2" /></svg>
        ) : null}
      </span>
      <div>
        <h2 className="text-lg font-semibold text-ink">{title}</h2>
        {hint ? <p className="mt-1 text-sm text-muted">{hint}</p> : null}
      </div>
    </div>
  )
}
