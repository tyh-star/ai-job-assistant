import { Pressable, StyleSheet, Text, type StyleProp, type ViewStyle } from 'react-native'
import { colors } from '../theme'
import { ArrowRightIcon } from './Icons'

type Props = {
  label: string
  onPress: () => void
  arrow?: boolean
  style?: StyleProp<ViewStyle>
}

/** A text action with a 44pt tall hit area. */
export function TextLink({ label, onPress, arrow = false, style }: Props) {
  return (
    <Pressable
      role="link"
      aria-label={label}
      onPress={onPress}
      hitSlop={{ left: 8, right: 8 }}
      style={({ pressed }) => [styles.link, pressed && styles.pressed, style]}
    >
      <Text style={styles.label}>{label}</Text>
      {arrow ? <ArrowRightIcon /> : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  link: {
    minHeight: 44,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.blue,
  },
  pressed: {
    opacity: 0.6,
  },
})
