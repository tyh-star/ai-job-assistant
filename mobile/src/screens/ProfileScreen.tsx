import { useEffect, useState, type ReactNode } from 'react'
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { LinearGradient } from 'expo-linear-gradient'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { accountErrorKey, apiErrorKey } from '../api/errors'
import type { UserResponse } from '../api/types'
import { SessionStoreError, useAuth } from '../auth/AuthContext'
import { PASSWORD_RULES, evaluatePassword } from '../auth/passwordStrength'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { Skeleton, confirmAction } from '../components/feedback'
import { CheckIcon, ChevronRightIcon } from '../components/Icons'
import { SegmentedControl } from '../components/SegmentedControl'
import { TextField } from '../components/TextField'
import { currentLanguage, setAppLanguage, type AppLanguage } from '../i18n'
import { profileKeys, useDeleteFeedback, useMe, useMyFeedback, useSaveFeedback } from '../profile/queries'
import { colors, radius, space } from '../theme'
import { formatFullDate } from '../utils/dates'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const NAME_MAX_LENGTH = 100
const COMMENT_MAX_LENGTH = 1000

type Message = { tone: 'info' | 'error'; text: string } | null
type OpenForm = 'name' | 'email' | 'password' | null

export function ProfileScreen() {
  const { t, i18n } = useTranslation()
  const { user, syncUser } = useAuth()
  const me = useMe()
  const [openForm, setOpenForm] = useState<OpenForm>(null)

  useEffect(() => {
    if (me.data) {
      syncUser(me.data)
    }
  }, [me.data, syncUser])

  const name = me.data?.name ?? user?.name ?? ''
  const email = me.data?.email ?? user?.email ?? ''
  const toggle = (form: Exclude<OpenForm, null>) => setOpenForm((current) => (current === form ? null : form))

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <Text role="heading" style={styles.title}>
            {t('profile.title')}
          </Text>

          <View style={styles.card}>
            {me.isPending ? (
              <View style={styles.identity} aria-busy>
                <Skeleton width={56} height={56} style={{ borderRadius: 28 }} />
                <View style={styles.flex}>
                  <Skeleton width="50%" height={18} />
                  <Skeleton width="70%" height={14} style={{ marginTop: 8 }} />
                </View>
              </View>
            ) : me.isError && !me.data ? (
              <View>
                <Text role="heading" style={styles.cardTitle}>
                  {t('profile.loadFailedTitle')}
                </Text>
                <Text style={styles.muted}>{t(apiErrorKey(me.error))}</Text>
                <Button
                  variant="secondary"
                  size="sm"
                  label={t('jobs.retry')}
                  onPress={() => void me.refetch()}
                  style={styles.inlineButton}
                />
              </View>
            ) : (
              <View style={styles.identity}>
                <LinearGradient colors={['#1D93FC', '#2461DC']} style={styles.avatar} aria-hidden>
                  <Text style={styles.avatarText}>{(name.trim()[0] ?? '?').toUpperCase()}</Text>
                </LinearGradient>
                <View style={styles.flex}>
                  <Text style={styles.name} numberOfLines={1}>
                    {name}
                  </Text>
                  <Text style={styles.email} numberOfLines={1}>
                    {email}
                  </Text>
                  {me.data?.createdAt ? (
                    <Text style={styles.subtle}>
                      {t('profile.memberSince', { date: formatFullDate(me.data.createdAt, i18n.language) })}
                    </Text>
                  ) : null}
                </View>
              </View>
            )}
          </View>

          <Text style={styles.sectionLabel}>{t('profile.sections.account')}</Text>
          <View style={styles.card}>
            <Expandable
              title={t('profile.name.title')}
              subtitle={name}
              open={openForm === 'name'}
              onToggle={() => toggle('name')}
            >
              <NameForm currentName={name} />
            </Expandable>
            <View style={styles.divider} />
            <Expandable
              title={t('profile.email.title')}
              subtitle={email}
              open={openForm === 'email'}
              onToggle={() => toggle('email')}
            >
              <EmailForm />
            </Expandable>
            <View style={styles.divider} />
            <Expandable
              title={t('profile.password.title')}
              subtitle={t('profile.password.subtitle')}
              open={openForm === 'password'}
              onToggle={() => toggle('password')}
            >
              <PasswordForm />
            </Expandable>
          </View>

          <Text style={styles.sectionLabel}>{t('profile.sections.language')}</Text>
          <View style={styles.card}>
            <LanguageSwitch />
          </View>

          <Text style={styles.sectionLabel}>{t('profile.sections.feedback')}</Text>
          <View style={styles.card}>
            <FeedbackSection />
          </View>

          <LogoutButton />

          <DangerZone email={email} />
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

/* ------------------------------------------------------------------ pieces */

function Expandable({
  title,
  subtitle,
  open,
  onToggle,
  children,
}: {
  title: string
  subtitle: string
  open: boolean
  onToggle: () => void
  children: ReactNode
}) {
  return (
    <View>
      <Pressable
        role="button"
        aria-label={title}
        aria-expanded={open}
        onPress={onToggle}
        style={({ pressed }) => [styles.row, pressed && styles.pressed]}
      >
        <View style={styles.flex}>
          <Text style={styles.rowTitle}>{title}</Text>
          <Text style={styles.rowSubtitle} numberOfLines={1}>
            {subtitle}
          </Text>
        </View>
        <View style={open ? styles.chevronOpen : undefined}>
          <ChevronRightIcon />
        </View>
      </Pressable>
      {open ? <View style={styles.form}>{children}</View> : null}
    </View>
  )
}

function MessageLine({ message }: { message: Message }) {
  return message ? <Notice tone={message.tone}>{message.text}</Notice> : null
}

function NameForm({ currentName }: { currentName: string }) {
  const { t } = useTranslation()
  const { updateName } = useAuth()
  const queryClient = useQueryClient()
  const [value, setValue] = useState(currentName)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<Message>(null)

  async function save() {
    const trimmed = value.trim()
    if (!trimmed) {
      setMessage({ tone: 'error', text: t('profile.name.required') })
      return
    }
    if (trimmed.length > NAME_MAX_LENGTH) {
      setMessage({ tone: 'error', text: t('profile.name.tooLong') })
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      const updated: UserResponse = await updateName(trimmed)
      queryClient.setQueryData(profileKeys.me(updated.id), updated)
      setValue(updated.name)
      setMessage({ tone: 'info', text: t('profile.name.saved') })
    } catch (error) {
      setMessage({ tone: 'error', text: t('profile.flash.nameFailed', { reason: t(accountErrorKey(error)) }) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <TextField label={t('profile.name.label')} value={value} onChangeText={setValue} maxLength={NAME_MAX_LENGTH} />
      <MessageLine message={message} />
      <Button
        label={t('profile.name.save')}
        onPress={() => void save()}
        loading={saving}
        disabled={value.trim() === currentName.trim()}
      />
    </>
  )
}

function EmailForm() {
  const { t } = useTranslation()
  const { updateEmail } = useAuth()
  const queryClient = useQueryClient()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<Message>(null)

  async function save() {
    if (!currentPassword) {
      setMessage({ tone: 'error', text: t('auth.passwordRequired') })
      return
    }
    const email = newEmail.trim()
    if (!EMAIL_PATTERN.test(email)) {
      setMessage({ tone: 'error', text: t('auth.emailInvalid') })
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      // Nothing started with the old token should finish after it is replaced.
      await queryClient.cancelQueries()
      const updated = await updateEmail(currentPassword, email)
      setCurrentPassword('')
      setNewEmail('')
      queryClient.setQueryData(profileKeys.me(updated.id), updated)
      void queryClient.invalidateQueries()
      setMessage({ tone: 'info', text: t('profile.email.saved') })
    } catch (error) {
      if (error instanceof SessionStoreError) {
        return // Already signed out; the login screen explains why.
      }
      setCurrentPassword('')
      setMessage({ tone: 'error', text: t('profile.flash.emailFailed', { reason: t(accountErrorKey(error)) }) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Text style={styles.hint}>{t('profile.email.hint')}</Text>
      <TextField
        label={t('profile.email.currentPassword')}
        value={currentPassword}
        onChangeText={setCurrentPassword}
        secure
        autoCapitalize="none"
        autoComplete="current-password"
        textContentType="password"
      />
      <TextField
        label={t('profile.email.newEmail')}
        value={newEmail}
        onChangeText={setNewEmail}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType="email-address"
        textContentType="emailAddress"
      />
      <MessageLine message={message} />
      <Button label={t('profile.email.save')} onPress={() => void save()} loading={saving} />
    </>
  )
}

function PasswordForm() {
  const { t } = useTranslation()
  const { changePassword } = useAuth()
  const queryClient = useQueryClient()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<Message>(null)
  const rules = evaluatePassword(newPassword)

  async function save() {
    if (!currentPassword) {
      setMessage({ tone: 'error', text: t('auth.passwordRequired') })
      return
    }
    if (!rules.strong) {
      setMessage({ tone: 'error', text: t('errors.weakPassword') })
      return
    }
    setSaving(true)
    setMessage(null)
    try {
      await queryClient.cancelQueries()
      // On success the account signs out here and the login screen explains why.
      await changePassword(currentPassword, newPassword)
    } catch (error) {
      setCurrentPassword('')
      setMessage({ tone: 'error', text: t('profile.flash.passwordFailed', { reason: t(accountErrorKey(error)) }) })
      setSaving(false)
    }
  }

  return (
    <>
      <Text style={styles.hint}>{t('profile.password.hint')}</Text>
      <TextField
        label={t('profile.password.current')}
        value={currentPassword}
        onChangeText={setCurrentPassword}
        secure
        autoCapitalize="none"
        autoComplete="current-password"
        textContentType="password"
      />
      <TextField
        label={t('profile.password.new')}
        value={newPassword}
        onChangeText={setNewPassword}
        secure
        autoCapitalize="none"
        autoComplete="new-password"
        textContentType="newPassword"
      />
      <View style={styles.rules} aria-label={t('auth.passwordRulesLabel')}>
        {PASSWORD_RULES.map((rule) => {
          const met = newPassword.length > 0 && rules[rule]
          return (
            <View key={rule} style={styles.rule}>
              <View style={[styles.ruleDot, met && styles.ruleDotMet]}>{met ? <CheckIcon size={9} /> : null}</View>
              <Text style={[styles.ruleText, met && styles.ruleTextMet]}>{t(`auth.passwordRule.${rule}`)}</Text>
            </View>
          )
        })}
      </View>
      <MessageLine message={message} />
      <Button label={t('profile.password.save')} onPress={() => void save()} loading={saving} />
    </>
  )
}

function LanguageSwitch() {
  const { t, i18n } = useTranslation()
  const [message, setMessage] = useState<Message>(null)
  const value: AppLanguage = i18n.language ? currentLanguage() : 'zh-CN'

  async function change(language: AppLanguage) {
    setMessage(null)
    try {
      await setAppLanguage(language)
    } catch {
      setMessage({ tone: 'error', text: t('profile.language.saveFailed') })
    }
  }

  return (
    <View style={styles.formGap}>
      <SegmentedControl
        label={t('profile.language.label')}
        value={value}
        onChange={(language) => void change(language)}
        options={[
          { value: 'zh-CN', label: '中文' },
          { value: 'en', label: 'English' },
        ]}
      />
      <MessageLine message={message} />
    </View>
  )
}

function FeedbackSection() {
  const { t, i18n } = useTranslation()
  const feedback = useMyFeedback()
  const save = useSaveFeedback()
  const remove = useDeleteFeedback()
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [message, setMessage] = useState<Message>(null)

  useEffect(() => {
    if (feedback.data) {
      setRating(feedback.data.rating)
      setComment(feedback.data.comment ?? '')
    }
  }, [feedback.data])

  if (feedback.isPending) {
    return (
      <View aria-busy style={styles.formGap}>
        <Skeleton width="40%" height={16} />
        <Skeleton width="100%" height={80} />
      </View>
    )
  }

  if (feedback.isError) {
    return (
      <View role="alert">
        <Text style={styles.cardTitle}>{t('feedback.loadFailed')}</Text>
        <Text style={styles.muted}>{t(apiErrorKey(feedback.error))}</Text>
        <Button
          variant="secondary"
          size="sm"
          label={t('jobs.retry')}
          onPress={() => void feedback.refetch()}
          style={styles.inlineButton}
        />
      </View>
    )
  }

  const existing = feedback.data
  const busy = save.isPending || remove.isPending

  async function submit() {
    if (rating < 1 || rating > 5) {
      setMessage({ tone: 'error', text: t('feedback.ratingRequired') })
      return
    }
    setMessage(null)
    const trimmed = comment.trim()
    try {
      await save.mutateAsync({ rating, comment: trimmed ? trimmed : null })
      setMessage({ tone: 'info', text: existing ? t('feedback.updated') : t('feedback.thankYou') })
    } catch (error) {
      setMessage({ tone: 'error', text: t('feedback.failed', { reason: t(apiErrorKey(error)) }) })
    }
  }

  async function onDelete() {
    const ok = await confirmAction({
      title: t('feedback.deleteConfirmTitle'),
      message: t('feedback.deleteConfirmBody'),
      confirmLabel: t('feedback.deleteYes'),
      cancelLabel: t('profile.cancel'),
      destructive: true,
    })
    if (!ok) {
      return
    }
    setMessage(null)
    try {
      await remove.mutateAsync()
      setRating(0)
      setComment('')
      setMessage({ tone: 'info', text: t('feedback.deleted') })
    } catch (error) {
      setMessage({ tone: 'error', text: t('feedback.deleteFailed', { reason: t(apiErrorKey(error)) }) })
    }
  }

  return (
    <View style={styles.formGap}>
      <Text style={styles.muted}>
        {existing
          ? t('feedback.current', {
              rating: existing.rating,
              date: formatFullDate(existing.updatedAt, i18n.language),
            })
          : t('feedback.none')}
      </Text>

      <Text style={styles.fieldLabel}>{t('feedback.ratingLabel')}</Text>
      <View style={styles.stars} role="radiogroup" aria-label={t('feedback.ratingLabel')}>
        {[1, 2, 3, 4, 5].map((value) => {
          const active = value <= rating
          return (
            <Pressable
              key={value}
              role="radio"
              aria-checked={value === rating}
              aria-label={t('feedback.star', { count: value })}
              onPress={() => setRating(value)}
              hitSlop={4}
              style={styles.starHit}
            >
              <Text style={[styles.star, active && styles.starActive]}>★</Text>
            </Pressable>
          )
        })}
      </View>

      <Text style={styles.fieldLabel}>{t('feedback.commentLabel')}</Text>
      <TextInput
        aria-label={t('feedback.commentLabel')}
        value={comment}
        onChangeText={setComment}
        placeholder={t('feedback.commentPlaceholder')}
        placeholderTextColor={colors.subtle}
        multiline
        maxLength={COMMENT_MAX_LENGTH}
        style={styles.comment}
      />
      <Text style={styles.subtle}>{t('feedback.remaining', { count: COMMENT_MAX_LENGTH - comment.length })}</Text>

      <MessageLine message={message} />
      <Button
        label={existing ? t('feedback.update') : t('feedback.submit')}
        onPress={() => void submit()}
        loading={save.isPending}
        disabled={busy}
      />
      {existing ? (
        <Button
          variant="secondary"
          size="sm"
          label={t('feedback.delete')}
          onPress={() => void onDelete()}
          loading={remove.isPending}
          disabled={busy}
        />
      ) : null}
    </View>
  )
}

function LogoutButton() {
  const { t } = useTranslation()
  const { logout } = useAuth()
  const queryClient = useQueryClient()

  async function onLogout() {
    const ok = await confirmAction({
      title: t('profile.logout.confirmTitle'),
      message: t('profile.logout.confirmBody'),
      confirmLabel: t('profile.logout.confirmYes'),
      cancelLabel: t('profile.cancel'),
    })
    if (!ok) {
      return
    }
    await queryClient.cancelQueries()
    await logout()
  }

  return <Button variant="secondary" label={t('profile.logout.button')} onPress={() => void onLogout()} style={styles.logout} />
}

function DangerZone({ email }: { email: string }) {
  const { t } = useTranslation()
  const { deleteAccount } = useAuth()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [message, setMessage] = useState<Message>(null)

  async function onConfirm() {
    const ok = await confirmAction({
      title: t('profile.danger.finalTitle'),
      message: t('profile.danger.finalBody', { email }),
      confirmLabel: t('profile.danger.finalYes'),
      cancelLabel: t('profile.cancel'),
      destructive: true,
    })
    if (!ok) {
      return
    }
    setDeleting(true)
    setMessage(null)
    try {
      await queryClient.cancelQueries()
      // On success the account is signed out and the login screen says it was deleted.
      await deleteAccount()
    } catch (error) {
      setMessage({ tone: 'error', text: t('profile.danger.failed', { reason: t(apiErrorKey(error)) }) })
      setDeleting(false)
    }
  }

  return (
    <View style={styles.danger} aria-label={t('profile.danger.title')}>
      <Text role="heading" style={styles.dangerTitle}>
        {t('profile.danger.title')}
      </Text>
      <Text style={styles.dangerBody}>{t('profile.danger.body')}</Text>

      {open ? (
        <View style={styles.formGap}>
          <Text style={styles.dangerScopeTitle}>{t('profile.danger.scopeTitle')}</Text>
          <Text style={styles.dangerBody}>{t('profile.danger.removes')}</Text>
          <Text style={styles.dangerBody}>{t('profile.danger.keeps')}</Text>
          <Text style={[styles.dangerBody, styles.dangerStrong]}>{t('profile.danger.irreversible')}</Text>
          <MessageLine message={message} />
          <View style={styles.dangerButtons}>
            <Button
              variant="secondary"
              size="sm"
              label={t('profile.cancel')}
              onPress={() => {
                setOpen(false)
                setMessage(null)
              }}
              disabled={deleting}
              style={styles.flex}
            />
            <Button
              variant="danger"
              size="sm"
              label={t('profile.danger.confirm')}
              onPress={() => void onConfirm()}
              loading={deleting}
              style={styles.flex}
            />
          </View>
        </View>
      ) : (
        <Button variant="danger" size="sm" label={t('profile.danger.open')} onPress={() => setOpen(true)} style={styles.inlineButton} />
      )}
    </View>
  )
}

/* ------------------------------------------------------------------ styles */

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1, minWidth: 0 },
  content: { paddingHorizontal: space.screenX, paddingTop: 10, paddingBottom: 40 },
  pressed: { opacity: 0.7 },
  title: { fontSize: 28, fontWeight: '700', color: colors.ink, letterSpacing: -0.6, marginBottom: 14 },
  card: {
    padding: 16,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
  },
  cardTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  identity: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  avatar: { width: 56, height: 56, borderRadius: 28, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: '#FFFFFF', fontSize: 22, fontWeight: '700' },
  name: { fontSize: 20, fontWeight: '700', color: colors.ink },
  email: { marginTop: 2, fontSize: 14, color: colors.muted },
  subtle: { marginTop: 4, fontSize: 12, color: colors.subtle },
  muted: { marginTop: 4, fontSize: 14, lineHeight: 20, color: colors.muted },
  hint: { fontSize: 13, lineHeight: 19, color: colors.muted },
  sectionLabel: { marginTop: 22, marginBottom: 8, fontSize: 13, fontWeight: '600', color: colors.muted },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 56, paddingVertical: 6 },
  rowTitle: { fontSize: 15, fontWeight: '600', color: colors.ink },
  rowSubtitle: { marginTop: 2, fontSize: 13, color: colors.muted },
  chevronOpen: { transform: [{ rotate: '90deg' }] },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: colors.hairline },
  form: { gap: 12, paddingBottom: 12 },
  formGap: { gap: 10 },
  inlineButton: { marginTop: 12, alignSelf: 'flex-start' },
  fieldLabel: { fontSize: 14, fontWeight: '600', color: colors.ink2 },
  stars: { flexDirection: 'row', gap: 4 },
  starHit: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  star: { fontSize: 30, color: '#D3DEEB' },
  starActive: { color: '#F5A524' },
  comment: {
    minHeight: 96,
    padding: 12,
    borderRadius: radius.button,
    borderWidth: 1,
    borderColor: colors.inputBorder,
    backgroundColor: '#FFFFFF',
    fontSize: 15,
    color: colors.ink,
    textAlignVertical: 'top',
  },
  rules: { flexDirection: 'row', flexWrap: 'wrap', rowGap: 6 },
  rule: { width: '50%', flexDirection: 'row', alignItems: 'flex-start', gap: 6, paddingRight: 6 },
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
  ruleDotMet: { borderColor: colors.greenFill, backgroundColor: colors.greenFill },
  ruleText: { flexShrink: 1, fontSize: 12, color: colors.subtle },
  ruleTextMet: { color: colors.green },
  logout: { marginTop: 24 },
  danger: {
    marginTop: 32,
    gap: 8,
    padding: 16,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.dangerBorder,
    backgroundColor: colors.dangerSurface,
  },
  dangerTitle: { fontSize: 16, fontWeight: '700', color: colors.danger },
  dangerBody: { fontSize: 13, lineHeight: 19, color: '#7A271A' },
  dangerScopeTitle: { marginTop: 4, fontSize: 14, fontWeight: '700', color: '#7A271A' },
  dangerStrong: { fontWeight: '700' },
  dangerButtons: { flexDirection: 'row', gap: 10, marginTop: 4 },
})

