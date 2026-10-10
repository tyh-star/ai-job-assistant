import type { ReactNode } from 'react'
import { ActivityIndicator, Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native'
import { LinearGradient } from 'expo-linear-gradient'
import { colors, gradients, radius } from '../theme'

type Props = {
  label: string
  onPress: () => void
  /** `danger` is only for irreversible account actions. */
  variant?: 'primary' | 'secondary' | 'danger'
  size?: 'md' | 'sm'
  disabled?: boolean
  loading?: boolean
  icon?: ReactNode
  style?: StyleProp<ViewStyle>
}

export function Button({
  label,
  onPress,
  variant = 'primary',
  size = 'md',
  disabled = false,
  loading = false,
  icon,
  style,
}: Props) {
  const inactive = disabled || loading
  const height = size === 'md' ? 48 : 38
  const lightText = variant === 'primary' || variant === 'danger'
  const content = (
    <View style={[styles.inner, { height }]}>
      {loading ? (
        <ActivityIndicator color={lightText ? '#FFFFFF' : colors.blue} />
      ) : (
        <>
          {icon}
          <Text
            style={[
              styles.label,
              size === 'sm' && styles.labelSm,
              lightText ? styles.labelPrimary : styles.labelSecondary,
            ]}
            numberOfLines={1}
          >
            {label}
          </Text>
        </>
      )}
    </View>
  )

  return (
    <Pressable
      role="button"
      aria-label={label}
      aria-disabled={inactive}
      aria-busy={loading}
      disabled={inactive}
      onPress={onPress}
      hitSlop={size === 'sm' ? 4 : 0}
      style={({ pressed }) => [
        styles.base,
        variant === 'secondary' && styles.secondary,
        variant === 'danger' && styles.danger,
        inactive && styles.inactive,
        pressed && !inactive && styles.pressed,
        style,
      ]}
    >
      {variant === 'primary' ? (
        <LinearGradient colors={gradients.primary} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.fill}>
          {content}
        </LinearGradient>
      ) : (
        content
      )}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  base: {
    borderRadius: radius.button,
    overflow: 'hidden',
  },
  fill: {
    borderRadius: radius.button,
  },
  secondary: {
    borderWidth: 1,
    borderColor: '#77ADF7',
    backgroundColor: 'rgba(255,255,255,0.86)',
  },
  danger: {
    backgroundColor: colors.danger,
  },
  inner: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 14,
  },
  label: {
    fontSize: 16,
    fontWeight: '600',
  },
  labelSm: {
    fontSize: 14,
  },
  labelPrimary: {
    color: '#FFFFFF',
  },
  labelSecondary: {
    color: '#0666E7',
  },
  inactive: {
    opacity: 0.55,
  },
  pressed: {
    opacity: 0.85,
  },
})
