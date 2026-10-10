import { useEffect, useState, type ReactNode } from 'react'
import { Pressable, StyleSheet, Text, TextInput, View, type KeyboardTypeOptions } from 'react-native'
import { colors, radius } from '../theme'
import { ChevronRightIcon } from '../components/Icons'
import { linesToList, parseCount } from './model'

type InputProps = {
  label: string
  value: string
  onChangeText: (value: string) => void
  error?: string | null
  hint?: string
  placeholder?: string
  multiline?: boolean
  keyboardType?: KeyboardTypeOptions
  autoCapitalize?: 'none' | 'sentences' | 'words'
}

/** A labelled input for the builder form; multi-line fields grow instead of scrolling inside. */
export function FormInput({ label, value, onChangeText, error, hint, placeholder, multiline, keyboardType, autoCapitalize }: InputProps) {
  const [focused, setFocused] = useState(false)
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        aria-label={label}
        aria-invalid={Boolean(error)}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={colors.subtle}
        multiline={multiline}
        keyboardType={keyboardType}
        autoCapitalize={autoCapitalize}
        autoCorrect={false}
        textAlignVertical={multiline ? 'top' : 'center'}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={[styles.input, multiline && styles.multiline, focused && styles.focused, error ? styles.invalid : null]}
      />
      {error ? <Text style={styles.error}>{error}</Text> : hint ? <Text style={styles.hint}>{hint}</Text> : null}
    </View>
  )
}

/**
 * "One per line" list. The typed text stays as typed (blank lines, a trailing newline)
 * and is only replaced when the list itself changes from outside, e.g. an entry above
 * was removed.
 */
export function ListInput({
  label,
  value,
  onChange,
  error,
  hint,
}: {
  label: string
  value: string[]
  onChange: (next: string[]) => void
  error?: string | null
  hint?: string
}) {
  const [text, setText] = useState(() => value.join('\n'))
  useEffect(() => {
    setText((typed) => (JSON.stringify(linesToList(typed)) === JSON.stringify(value) ? typed : value.join('\n')))
  }, [value])
  return (
    <FormInput
      label={label}
      value={text}
      multiline
      error={error}
      hint={hint}
      onChangeText={(next) => {
        setText(next)
        onChange(linesToList(next))
      }}
    />
  )
}

export function CountInput({
  label,
  value,
  onChange,
  error,
}: {
  label: string
  value: number | null
  onChange: (next: number | null) => void
  error?: string | null
}) {
  return (
    <FormInput
      label={label}
      value={value === null ? '' : String(value)}
      keyboardType="number-pad"
      error={error}
      onChangeText={(next) => onChange(parseCount(next))}
    />
  )
}

export function ChoiceChips<T extends string>({
  label,
  value,
  options,
  onChange,
  disabled,
}: {
  label: string
  value: string
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  disabled?: boolean
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <View role="radiogroup" aria-label={label} style={styles.chips}>
        {options.map((option) => {
          const selected = option.value === value
          return (
            <Pressable
              key={option.value}
              role="radio"
              aria-checked={selected}
              aria-label={option.label}
              aria-disabled={disabled}
              disabled={disabled}
              onPress={() => onChange(option.value)}
              style={[styles.chip, selected && styles.chipSelected]}
            >
              <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{option.label}</Text>
            </Pressable>
          )
        })}
      </View>
    </View>
  )
}

/** A whole-row toggle: a larger touch target than a bare switch, and the same on every platform. */
export function SwitchRow({ label, value, onChange }: { label: string; value: boolean; onChange: (next: boolean) => void }) {
  return (
    <Pressable
      role="switch"
      aria-label={label}
      aria-checked={value}
      onPress={() => onChange(!value)}
      style={({ pressed }) => [styles.switchRow, pressed && styles.pressed]}
    >
      <Text style={styles.switchLabel}>{label}</Text>
      <View style={[styles.track, value && styles.trackOn]}>
        <View style={[styles.thumb, value && styles.thumbOn]} />
      </View>
    </Pressable>
  )
}

export function SectionCard({
  title,
  summary,
  expanded,
  onToggle,
  children,
}: {
  title: string
  summary?: string
  expanded: boolean
  onToggle: () => void
  children: ReactNode
}) {
  return (
    <View style={styles.card}>
      <Pressable
        role="button"
        aria-label={title}
        aria-expanded={expanded}
        onPress={onToggle}
        style={({ pressed }) => [styles.cardHeader, pressed && styles.pressed]}
      >
        <View style={styles.flex}>
          <Text role="heading" style={styles.cardTitle}>
            {title}
          </Text>
          {summary ? <Text style={styles.cardSummary}>{summary}</Text> : null}
        </View>
        <View style={{ transform: [{ rotate: expanded ? '90deg' : '0deg' }] }}>
          <ChevronRightIcon />
        </View>
      </Pressable>
      {expanded ? <View style={styles.cardBody}>{children}</View> : null}
    </View>
  )
}

export function EntryCard({
  heading,
  removeLabel,
  onRemove,
  children,
}: {
  heading: string
  removeLabel: string
  onRemove: () => void
  children: ReactNode
}) {
  return (
    <View style={styles.entry}>
      <View style={styles.entryHeader}>
        <Text style={styles.entryHeading} numberOfLines={1}>
          {heading}
        </Text>
        <Pressable role="button" aria-label={removeLabel} onPress={onRemove} hitSlop={8} style={styles.remove}>
          <Text style={styles.removeText}>{removeLabel}</Text>
        </Pressable>
      </View>
      {children}
    </View>
  )
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  field: { gap: 6 },
  label: { fontSize: 14, fontWeight: '600', color: colors.ink2 },
  input: {
    minHeight: 48,
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: radius.button,
    borderWidth: 1,
    borderColor: colors.inputBorder,
    backgroundColor: '#FFFFFF',
    fontSize: 16,
    color: colors.ink,
  },
  multiline: { minHeight: 96, paddingTop: 12 },
  focused: { borderColor: colors.blue },
  invalid: { borderColor: colors.danger },
  error: { fontSize: 13, color: colors.danger },
  hint: { fontSize: 12, color: colors.subtle },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: {
    minHeight: 36,
    justifyContent: 'center',
    paddingHorizontal: 12,
    borderRadius: radius.pill,
    borderWidth: 1,
    borderColor: colors.inputBorder,
    backgroundColor: '#FFFFFF',
  },
  chipSelected: { borderColor: colors.blue, backgroundColor: colors.chip },
  chipText: { fontSize: 14, color: colors.ink2 },
  chipTextSelected: { color: colors.blueStrong, fontWeight: '600' },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12, minHeight: 44 },
  switchLabel: { flex: 1, fontSize: 14, color: colors.ink2 },
  track: { width: 46, height: 28, padding: 3, borderRadius: 14, backgroundColor: colors.inputBorder },
  trackOn: { backgroundColor: colors.blue },
  thumb: { width: 22, height: 22, borderRadius: 11, backgroundColor: '#FFFFFF' },
  thumbOn: { transform: [{ translateX: 18 }] },
  card: {
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
    overflow: 'hidden',
  },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 56, paddingHorizontal: 16, paddingVertical: 12 },
  pressed: { opacity: 0.7 },
  cardTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  cardSummary: { marginTop: 2, fontSize: 13, color: colors.muted },
  cardBody: { gap: 14, paddingHorizontal: 16, paddingBottom: 16 },
  entry: { gap: 12, padding: 12, borderRadius: radius.tile, borderWidth: 1, borderColor: colors.hairline, backgroundColor: colors.surface },
  entryHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  entryHeading: { flex: 1, fontSize: 14, fontWeight: '700', color: colors.ink2 },
  remove: { minHeight: 32, justifyContent: 'center', paddingHorizontal: 4 },
  removeText: { fontSize: 14, fontWeight: '600', color: colors.danger },
})
