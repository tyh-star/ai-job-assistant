import { useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import { loginErrorKey } from '../api/errors'
import { useAuth, type SignOutReason } from '../auth/AuthContext'
import { AuthShell, Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { TextField } from '../components/TextField'
import { colors } from '../theme'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** What the login screen says after the app signed out on its own. */
const SIGN_OUT_NOTICE: Record<SignOutReason, string> = {
  expired: 'auth.sessionExpired',
  passwordChanged: 'auth.notice.passwordChanged',
  accountDeleted: 'auth.notice.accountDeleted',
  sessionStoreFailed: 'auth.notice.sessionStoreFailed',
}

type Props = {
  onGoRegister: () => void
  /** Leave without logging in, back to the public welcome page. */
  onBrowse?: () => void
  /** A feature that needs an account sent the visitor here. */
  loginRequired?: boolean
}

export function LoginScreen({ onGoRegister, onBrowse, loginRequired = false }: Props) {
  const { t } = useTranslation()
  const { login, signOutReason, acknowledgeSignOutReason } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit() {
    const nextErrors: { email?: string; password?: string } = {}
    if (!email.trim()) {
      nextErrors.email = t('auth.emailRequired')
    } else if (!EMAIL_PATTERN.test(email.trim())) {
      nextErrors.email = t('auth.emailInvalid')
    }
    if (!password) {
      nextErrors.password = t('auth.passwordRequired')
    }
    setFieldErrors(nextErrors)
    setFormError(null)
    if (nextErrors.email || nextErrors.password) {
      return
    }

    acknowledgeSignOutReason()
    setSubmitting(true)
    try {
      await login(email, password)
    } catch (error) {
      setFormError(t(loginErrorKey(error)))
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      title={t('auth.loginTitle')}
      footer={
        <View style={styles.footer}>
          <View style={styles.switchRow}>
            <Text style={styles.switchText}>{t('auth.noAccount')}</Text>
            <Pressable role="link" aria-label={t('auth.goRegister')} onPress={onGoRegister} hitSlop={12}>
              <Text style={styles.switchLink}>{t('auth.goRegister')}</Text>
            </Pressable>
          </View>
          {onBrowse ? (
            <Pressable role="link" aria-label={t('auth.browse')} onPress={onBrowse} hitSlop={12} style={styles.switchRow}>
              <Text style={styles.switchLink}>{t('auth.browse')}</Text>
            </Pressable>
          ) : null}
        </View>
      }
    >
      {signOutReason ? <Notice tone="info">{t(SIGN_OUT_NOTICE[signOutReason])}</Notice> : null}
      {!signOutReason && loginRequired ? <Notice tone="info">{t('auth.loginRequired')}</Notice> : null}
      {formError ? <Notice tone="error">{formError}</Notice> : null}
      <TextField
        label={t('auth.email')}
        value={email}
        onChangeText={setEmail}
        error={fieldErrors.email}
        autoCapitalize="none"
        autoComplete="email"
        autoCorrect={false}
        keyboardType="email-address"
        textContentType="username"
        returnKeyType="next"
      />
      <TextField
        label={t('auth.password')}
        value={password}
        onChangeText={setPassword}
        error={fieldErrors.password}
        secure
        autoCapitalize="none"
        autoComplete="current-password"
        textContentType="password"
        returnKeyType="go"
        onSubmitEditing={() => void submit()}
      />
      <Button label={t('auth.submitLogin')} onPress={() => void submit()} loading={submitting} />
    </AuthShell>
  )
}

const styles = StyleSheet.create({
  footer: {
    gap: 4,
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    minHeight: 44,
  },
  switchText: {
    fontSize: 15,
    color: colors.muted,
  },
  switchLink: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.blue,
  },
})
