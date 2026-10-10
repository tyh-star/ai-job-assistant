import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Alert, Platform, StyleSheet, Text, View } from 'react-native'
import { colors, radius } from '../theme'
import { Button } from './Button'

type ConfirmOptions = {
  title: string
  message: string
  confirmLabel: string
  cancelLabel: string
  destructive?: boolean
}

/** Resolves true only when the user explicitly confirms. */
export function confirmAction(options: ConfirmOptions): Promise<boolean> {
  if (Platform.OS === 'web') {
    const ask = (globalThis as { confirm?: (message: string) => boolean }).confirm
    return Promise.resolve(ask ? ask(`${options.title}\n\n${options.message}`) : false)
  }
  return new Promise((resolve) => {
    Alert.alert(
      options.title,
      options.message,
      [
        { text: options.cancelLabel, style: 'cancel', onPress: () => resolve(false) },
        {
          text: options.confirmLabel,
          style: options.destructive ? 'destructive' : 'default',
          onPress: () => resolve(true),
        },
      ],
      { cancelable: true, onDismiss: () => resolve(false) },
    )
  })
}

export type Flash = { tone: 'success' | 'error'; text: string }

const FLASH_MS = 3200

/** A short-lived result message for an action on the current screen. */
export function useFlash() {
  const [flash, setFlash] = useState<Flash | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const show = useCallback((tone: Flash['tone'], text: string) => {
    if (timer.current) {
      clearTimeout(timer.current)
    }
    setFlash({ tone, text })
    timer.current = setTimeout(() => setFlash(null), FLASH_MS)
  }, [])

  useEffect(
    () => () => {
      if (timer.current) {
        clearTimeout(timer.current)
      }
    },
    [],
  )

  return { flash, show }
}

export function FlashBanner({ flash }: { flash: Flash | null }) {
  if (!flash) {
    return null
  }
  const success = flash.tone === 'success'
  return (
    <View
      accessible
      role={success ? 'status' : 'alert'}
      aria-live="polite"
      accessibilityLiveRegion="polite"
      style={[styles.flash, success ? styles.flashSuccess : styles.flashError]}
    >
      <Text style={[styles.flashText, success ? styles.flashTextSuccess : styles.flashTextError]}>{flash.text}</Text>
    </View>
  )
}

type StateViewProps = {
  tone: 'empty' | 'error'
  title: string
  body?: string
  icon?: ReactNode
  actionLabel?: string
  onAction?: () => void
}

/** One component for "nothing here" and "could not load" -- they read differently on purpose. */
export function StateView({ tone, title, body, icon, actionLabel, onAction }: StateViewProps) {
  return (
    <View role={tone === 'error' ? 'alert' : undefined} style={[styles.state, tone === 'error' && styles.stateError]}>
      {icon ? <View style={styles.stateIcon}>{icon}</View> : null}
      <Text role="heading" style={styles.stateTitle}>
        {title}
      </Text>
      {body ? <Text style={styles.stateBody}>{body}</Text> : null}
      {actionLabel && onAction ? (
        <Button variant="secondary" size="sm" label={actionLabel} onPress={onAction} style={styles.stateButton} />
      ) : null}
    </View>
  )
}

export function Skeleton({ width, height, style }: { width: number | `${number}%`; height: number; style?: object }) {
  return <View style={[styles.skeleton, { width, height }, style]} />
}

const styles = StyleSheet.create({
  flash: {
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  flashSuccess: { backgroundColor: '#ECFDF3', borderColor: '#A6E9C4' },
  flashError: { backgroundColor: colors.dangerSurface, borderColor: colors.dangerBorder },
  flashText: { fontSize: 14, lineHeight: 20, fontWeight: '600' },
  flashTextSuccess: { color: '#067647' },
  flashTextError: { color: colors.danger },

  state: {
    alignItems: 'flex-start',
    padding: 18,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  stateError: { backgroundColor: '#FFFBFA', borderColor: '#F5C9C2' },
  stateIcon: { marginBottom: 10 },
  stateTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  stateBody: { marginTop: 6, fontSize: 14, lineHeight: 20, color: colors.muted },
  stateButton: { marginTop: 14 },

  skeleton: { borderRadius: 6, backgroundColor: colors.skeleton },
})
