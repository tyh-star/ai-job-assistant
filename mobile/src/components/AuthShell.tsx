import type { ReactNode } from 'react'
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { LinearGradient } from 'expo-linear-gradient'
import { useTranslation } from 'react-i18next'
import { colors, gradients, radius } from '../theme'
import { BrandGlyph } from './Icons'

type Props = {
  title: string
  children: ReactNode
  footer: ReactNode
}

export function AuthShell({ title, children, footer }: Props) {
  const { t } = useTranslation()
  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
          <View style={styles.brandRow}>
            <LinearGradient colors={gradients.brand} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.mark}>
              <BrandGlyph size={24} />
            </LinearGradient>
            <Text style={styles.brand}>{t('brand')}</Text>
          </View>
          <Text style={styles.tagline}>{t('auth.tagline')}</Text>

          <View style={styles.card}>
            <Text role="heading" style={styles.title}>
              {title}
            </Text>
            <View style={styles.body}>{children}</View>
          </View>

          <View style={styles.footer}>{footer}</View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

export function Notice({ tone, children }: { tone: 'error' | 'info'; children: string }) {
  return (
    <View role="alert" style={[styles.notice, tone === 'error' ? styles.noticeError : styles.noticeInfo]}>
      <Text style={[styles.noticeText, tone === 'error' ? styles.noticeTextError : styles.noticeTextInfo]}>
        {children}
      </Text>
    </View>
  )
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: colors.background,
  },
  flex: {
    flex: 1,
  },
  scroll: {
    flexGrow: 1,
    paddingHorizontal: 20,
    paddingTop: 28,
    paddingBottom: 24,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  mark: {
    width: 44,
    height: 44,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  brand: {
    fontSize: 20,
    fontWeight: '700',
    color: colors.ink,
    letterSpacing: -0.2,
  },
  tagline: {
    marginTop: 14,
    fontSize: 15,
    lineHeight: 22,
    color: '#5573A9',
  },
  card: {
    marginTop: 24,
    padding: 20,
    borderRadius: radius.card + 2,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
    shadowColor: '#4083C0',
    shadowOpacity: 0.1,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 10 },
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: colors.ink,
    letterSpacing: -0.4,
  },
  body: {
    marginTop: 18,
    gap: 16,
  },
  footer: {
    marginTop: 18,
    alignItems: 'center',
  },
  notice: {
    borderRadius: 10,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  noticeError: {
    backgroundColor: colors.dangerSurface,
    borderColor: colors.dangerBorder,
  },
  noticeInfo: {
    backgroundColor: '#EFF6FF',
    borderColor: '#BFDBFE',
  },
  noticeText: {
    fontSize: 14,
    lineHeight: 20,
  },
  noticeTextError: {
    color: colors.danger,
  },
  noticeTextInfo: {
    color: colors.blueStrong,
  },
})
