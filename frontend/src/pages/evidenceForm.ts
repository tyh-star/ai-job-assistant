import type { EntryOption } from '../api/types'

const YES_ONLY = new Set(['yes', 'y', 'true', 'ok', 'used', '是'])

export type QuestionUiStatus = 'idle' | 'editing' | 'saving' | 'success' | 'error'

export function sortEntryOptions(options: EntryOption[]): EntryOption[] {
  const rest = options.filter((option) => option.entryKey !== 'GENERAL')
  const general = options.filter((option) => option.entryKey === 'GENERAL')
  return [...rest, ...general]
}

export function trimEvidenceField(value: string): string {
  return value.trim()
}

export function isYesOnlyAnswer(values: string[]): boolean {
  const filled = values.map(trimEvidenceField).filter((value) => value.length > 0)
  if (filled.length === 0) {
    return false
  }
  return filled.every((value) => YES_ONLY.has(value.toLowerCase()) || YES_ONLY.has(value))
}

export function hasSubstantiveEvidence(context: string, action: string, result: string): boolean {
  return [context, action, result].some((value) => trimEvidenceField(value).length > 0)
}

export function entryKeyAllowed(entryKey: string, options: EntryOption[]): boolean {
  return options.some((option) => option.entryKey === entryKey)
}
