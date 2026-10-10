import type { ReactNode } from 'react'
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useTranslation } from 'react-i18next'
import { colors, radius, space } from '../theme'
import { ChevronLeftIcon } from './Icons'

type Props = {
  /** Omitted on a bottom-tab screen, which has nothing to go back to. */
  onBack?: () => void
  children: ReactNode
  /** Pinned under the scroll content, above the home indicator. */
  actions?: ReactNode
  /** For screens with text inputs: keeps the focused field and the actions above the keyboard. */
  avoidKeyboard?: boolean
}

export function DetailLayout({ onBack, children, actions, avoidKeyboard = false }: Props) {
  const { t } = useTranslation()
  if (avoidKeyboard) {
    return (
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        {onBack ? <BackBar onBack={onBack} label={t('jobs.detail.back')} /> : <View style={styles.barless} />}
        <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          <ScrollView
            style={styles.flex}
            contentContainerStyle={styles.content}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode={Platform.OS === 'ios' ? 'interactive' : 'on-drag'}
          >
            {children}
          </ScrollView>
          {actions ? <View style={styles.actions}>{actions}</View> : null}
        </KeyboardAvoidingView>
      </SafeAreaView>
    )
  }
  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
      {onBack ? <BackBar onBack={onBack} label={t('jobs.detail.back')} /> : <View style={styles.barless} />}
      <ScrollView contentContainerStyle={styles.content}>{children}</ScrollView>
      {actions ? <View style={styles.actions}>{actions}</View> : null}
    </SafeAreaView>
  )
}

function BackBar({ onBack, label }: { onBack: () => void; label: string }) {
  return (
    <View style={styles.bar}>
      <Pressable role="button" aria-label={label} onPress={onBack} hitSlop={8} style={({ pressed }) => [styles.back, pressed && styles.pressed]}>
        <ChevronLeftIcon />
        <Text style={styles.backText}>{label}</Text>
      </Pressable>
    </View>
  )
}

export function InfoRow({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) {
    return null
  }
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  )
}

export const detailStyles = StyleSheet.create({
  title: { fontSize: 22, lineHeight: 30, fontWeight: '700', color: colors.ink, letterSpacing: -0.4 },
  company: { marginTop: 4, fontSize: 16, color: colors.muted },
  hint: { marginTop: 4, fontSize: 13, color: colors.subtle },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 12 },
  section: {
    marginTop: 16,
    padding: 16,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
  },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: colors.ink, marginBottom: 8 },
  body: { fontSize: 15, lineHeight: 23, color: colors.ink2 },
  muted: { fontSize: 14, color: colors.muted },
  actionRow: { flexDirection: 'row', gap: 10 },
  flex: { flex: 1 },
})

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  bar: { height: 48, justifyContent: 'center', paddingHorizontal: 10 },
  barless: { height: 16 },
  back: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44, paddingRight: 12, alignSelf: 'flex-start' },
  backText: { fontSize: 16, fontWeight: '600', color: colors.blue },
  pressed: { opacity: 0.6 },
  content: { paddingHorizontal: space.screenX, paddingBottom: 24 },
  actions: {
    gap: 10,
    paddingHorizontal: space.screenX,
    paddingTop: 12,
    paddingBottom: 8,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.hairline,
    backgroundColor: 'rgba(255,255,255,0.96)',
  },
  row: {
    flexDirection: 'row',
    gap: 12,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  rowLabel: { width: 76, fontSize: 14, color: colors.muted },
  rowValue: { flex: 1, fontSize: 14, lineHeight: 20, color: colors.ink },
})
