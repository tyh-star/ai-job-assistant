import { Pressable, StyleSheet, Text, View } from 'react-native'
import { colors } from '../theme'

type Option<T extends string> = { value: T; label: string }

type Props<T extends string> = {
  label: string
  value: T
  options: Option<T>[]
  onChange: (value: T) => void
}

export function SegmentedControl<T extends string>({ label, value, options, onChange }: Props<T>) {
  return (
    <View role="tablist" aria-label={label} style={styles.track}>
      {options.map((option) => {
        const selected = option.value === value
        return (
          <Pressable
            key={option.value}
            role="tab"
            aria-selected={selected}
            aria-label={option.label}
            onPress={() => onChange(option.value)}
            style={[styles.segment, selected && styles.selected]}
          >
            <Text style={[styles.text, selected && styles.textSelected]}>{option.label}</Text>
          </Pressable>
        )
      })}
    </View>
  )
}

const styles = StyleSheet.create({
  track: {
    flexDirection: 'row',
    padding: 3,
    borderRadius: 12,
    backgroundColor: '#E2EDF9',
  },
  segment: {
    flex: 1,
    minHeight: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
  },
  selected: {
    backgroundColor: '#FFFFFF',
    shadowColor: '#23406A',
    shadowOpacity: 0.08,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 1 },
  },
  text: { fontSize: 14, fontWeight: '500', color: colors.muted },
  textSelected: { fontWeight: '700', color: colors.blue },
})
