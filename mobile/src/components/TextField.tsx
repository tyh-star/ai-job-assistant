import { useState } from 'react'
import { Pressable, StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native'
import { useTranslation } from 'react-i18next'
import { colors, radius } from '../theme'
import { EyeIcon } from './Icons'

type Props = Omit<TextInputProps, 'style' | 'secureTextEntry'> & {
  label: string
  error?: string | null
  secure?: boolean
}

export function TextField({ label, error, secure = false, ...input }: Props) {
  const { t } = useTranslation()
  const [focused, setFocused] = useState(false)
  const [revealed, setRevealed] = useState(false)

  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <View style={[styles.box, focused && styles.boxFocused, error ? styles.boxError : null]}>
        <TextInput
          {...input}
          aria-label={label}
          aria-invalid={Boolean(error)}
          secureTextEntry={secure && !revealed}
          placeholderTextColor={colors.subtle}
          style={styles.input}
          onFocus={(event) => {
            setFocused(true)
            input.onFocus?.(event)
          }}
          onBlur={(event) => {
            setFocused(false)
            input.onBlur?.(event)
          }}
        />
        {secure ? (
          <Pressable
            role="button"
            aria-label={revealed ? t('auth.hidePassword') : t('auth.showPassword')}
            onPress={() => setRevealed((value) => !value)}
            hitSlop={8}
            style={styles.reveal}
          >
            <EyeIcon off={revealed} />
          </Pressable>
        ) : null}
      </View>
      {error ? <Text style={styles.error}>{error}</Text> : null}
    </View>
  )
}

const styles = StyleSheet.create({
  field: {
    gap: 6,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.ink2,
  },
  box: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 50,
    borderRadius: radius.button,
    borderWidth: 1,
    borderColor: colors.inputBorder,
    backgroundColor: '#FFFFFF',
  },
  boxFocused: {
    borderColor: colors.blue,
  },
  boxError: {
    borderColor: colors.danger,
  },
  input: {
    flex: 1,
    minHeight: 48,
    paddingHorizontal: 14,
    fontSize: 16,
    color: colors.ink,
  },
  reveal: {
    width: 44,
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  error: {
    fontSize: 13,
    color: colors.danger,
  },
})
