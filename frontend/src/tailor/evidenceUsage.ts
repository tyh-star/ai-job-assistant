import type { ResumeEvidenceItem, V3EvidenceUsage, V3OpportunityMap } from '../api/types'

export function usageCounts(usages: V3EvidenceUsage[] | undefined): {
  saved: number
  used: number
  unused: number
} {
  const items = usages ?? []
  const saved = items.filter((item) => item.usageStatus !== 'USER_REJECTED')
  const used = saved.filter((item) => (item.usageStatus ?? '').startsWith('USED_'))
  const unused = saved.filter((item) => item.usageStatus === 'SAVED_NOT_USED')
  return { saved: saved.length, used: used.length, unused: unused.length }
}

export function usageReasonKey(reasonCode: string | undefined): string {
  switch (reasonCode) {
    case 'NO_ADMITTED_REWRITE':
      return 'tailor.enhanced.reasonNoAdmitted'
    case 'FACT_ALREADY_PRESENT':
      return 'tailor.enhanced.reasonAlreadyPresent'
    case 'NOT_A_SKILL':
      return 'tailor.enhanced.reasonNotASkill'
    case 'NO_SKILLS_SECTION':
      return 'tailor.enhanced.reasonNoSkillsSection'
    case 'NOT_RELEVANT_TO_JD':
      return 'tailor.enhanced.reasonNotRelevant'
    case 'VALIDATOR_REJECTED':
      return 'tailor.enhanced.reasonValidator'
    case 'CONFIRMED_FACT_NOT_USED':
      return 'tailor.enhanced.reasonConfirmedNotUsed'
    default:
      return 'tailor.enhanced.reasonNoAdmitted'
  }
}

export function evidenceDisplayText(
  usage: V3EvidenceUsage,
  items: ResumeEvidenceItem[] | undefined,
): string {
  const item = (items ?? []).find((row) => row.id === usage.evidenceItemId)
  if (!item) {
    return ''
  }
  return [item.action, item.result, item.metric, item.context].filter((part) => part && part.trim()).join(' ').trim()
}

export function entryTitleForKey(
  key: string | undefined,
  opportunity: V3OpportunityMap | null | undefined,
): string {
  if (!key) {
    return ''
  }
  const entry = opportunity?.entries.find((item) => item.canonicalEntryKey === key)
  return entry?.entryTitle ?? key
}
