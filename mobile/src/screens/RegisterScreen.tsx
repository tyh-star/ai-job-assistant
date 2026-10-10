import { useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/errors'
import { useAuth } from '../auth/AuthContext'
import { PASSWORD_RULES, evaluatePassword } from '../auth/passwordStrength'
import { AuthShell, Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { CheckIcon } from '../components/Icons'
import { TextField } from '../components/TextField'
import { colors } from '../theme'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const NAME_MAX_LENGTH = 100

type FieldErrors = { name?: string; email?: string; password?: string }
type Props = { onGoLogin: () => void; onBrowse?: () => void }

export function RegisterScreen({ onGoLogin, onBrowse }: Props) {
  const { t } = useTranslation()
  const { register } = useAuth()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const rules = evaluatePassword(password)

  async function submit() {
    const nextErrors: FieldErrors = {}
    if (!name.trim()) {
      nextErrors.name = t('auth.nameRequired')
    }
    if (!email.trim()) {
      nextErrors.email = t('auth.emailRequired')
    } else if (!EMAIL_PATTERN.test(email.trim())) {
      nextErrors.email = t('auth.emailInvalid')
    }
    if (!password) {
      nextErrors.password = t('auth.passwordRequired')
    } else if (!rules.strong) {
      nextErrors.password = t('errors.weakPassword')
    }
    setFieldErrors(nextErrors)
    setFormError(null)
    if (Object.keys(nextErrors).length > 0) {
      return
    }

    setSubmitting(true)
    try {
      await register(name, email, password)
    } catch (error) {
      setFormError(t(apiErrorKey(error)))
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      title={t('auth.registerTitle')}
      footer={
        <View style={styles.footer}>
          <View style={styles.switchRow}>
            <Text style={styles.switchText}>{t('auth.hasAccount')}</Text>
            <Pressable role="link" aria-label={t('auth.goLogin')} onPress={onGoLogin} hitSlop={12}>
              <Text style={styles.switchLink}>{t('auth.goLogin')}</Text>
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
      {formError ? <Notice tone="error">{formError}</Notice> : null}
      <TextField
        label={t('auth.name')}
        value={name}
        onChangeText={setName}
        error={fieldErrors.name}
        maxLength={NAME_MAX_LENGTH}
        autoComplete="name"
        textContentType="name"
        returnKeyType="next"
      />
      <TextField
        label={t('auth.email')}
        value={email}
        onChangeText={setEmail}
        error={fieldErrors.email}
        autoCapitalize="none"
        autoComplete="email"
        autoCorrect={false}
        keyboardType="email-address"
        textContentType="emailAddress"
        returnKeyType="next"
      />
      <View style={styles.passwordGroup}>
        <TextField
          label={t('auth.password')}
          value={password}
          onChangeText={setPassword}
          error={fieldErrors.password}
          secure
          autoCapitalize="none"
          autoComplete="new-password"
          textContentType="newPassword"
        />
        <View aria-label={t('auth.passwordRulesLabel')} style={styles.rules}>
          {PASSWORD_RULES.map((rule) => {
            const met = password.length > 0 && rules[rule]
            return (
              <View key={rule} style={styles.rule}>
                <View style={[styles.ruleDot, met && styles.ruleDotMet]}>
                  {met ? <CheckIcon size={9} /> : null}
                </View>
                <Text style={[styles.ruleText, met && styles.ruleTextMet]}>{t(`auth.passwordRule.${rule}`)}</Text>
              </View>
            )
          })}
        </View>
      </View>
      <Button label={t('auth.submitRegister')} onPress={() => void submit()} loading={submitting} />
    </AuthShell>
  )
}

const styles = StyleSheet.create({
  footer: {
    gap: 4,
  },
  passwordGroup: {
    gap: 10,
  },
  rules: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    rowGap: 6,
  },
  rule: {
    width: '50%',
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 6,
    paddingRight: 6,
  },
  ruleDot: {
    marginTop: 2,
    width: 14,
    height: 14,
    borderRadius: 7,
    borderWidth: 1.5,
    borderColor: '#C3D1E3',
    alignItems: 'center',
    justifyContent: 'center',
  },
  ruleDotMet: {
    borderColor: colors.greenFill,
    backgroundColor: colors.greenFill,
  },
  ruleText: {
    flexShrink: 1,
    fontSize: 12,
    color: colors.subtle,
  },
  ruleTextMet: {
    color: colors.green,
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
