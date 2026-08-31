import type { V3EnhancedQuestion, V3JdRequirementBreakdown, V3OpportunityEntry, V3OpportunityMap } from '../api/types'

export function actionableEntries(map: V3OpportunityMap | null | undefined): V3OpportunityEntry[] {
  return (map?.entries ?? []).filter((entry) => entry.priority === 'HIGH' || entry.priority === 'MEDIUM')
}

export function mostRelevantTitles(map: V3OpportunityMap | null | undefined): string[] {
  return (map?.entries ?? [])
    .filter((entry) => entry.priority === 'HIGH')
    .map((entry) => entry.entryTitle)
}

export function hasCannotInvent(map: V3OpportunityMap | null | undefined): boolean {
  return (map?.entries ?? []).some((entry) => (entry.cannotInvent ?? []).length > 0)
}

export function priorityLabelKey(priority: string | undefined): string {
  if (priority === 'HIGH') {
    return 'tailor.enhanced.priorityHigh'
  }
  if (priority === 'MEDIUM') {
    return 'tailor.enhanced.priorityMedium'
  }
  return 'tailor.enhanced.priorityNone'
}

export function cleanDisplayItems(items: string[] | undefined): string[] {
  const seen = new Set<string>()
  const cleaned: string[] = []
  for (const item of items ?? []) {
    const value = String(item ?? '')
      .replace(/^[\s•·\-–—]+/g, '')
      .replace(/[\s•·]+$/g, '')
      .replace(/•\s*•/g, ' ')
      .trim()
    if (!value || value === '-' || value === '•' || seen.has(value)) {
      continue
    }
    seen.add(value)
    cleaned.push(value)
  }
  return cleaned
}

export function groupedRequirements(entry: V3OpportunityEntry | undefined): {
  covered: V3JdRequirementBreakdown[]
  partial: V3JdRequirementBreakdown[]
  missing: V3JdRequirementBreakdown[]
} {
  if (!entry) {
    return { covered: [], partial: [], missing: [] }
  }
  if (entry.requirements && entry.requirements.length > 0) {
    return {
      covered: entry.requirements.filter((item) => item.status === 'COVERED'),
      partial: entry.requirements.filter((item) => item.status === 'PARTIALLY_COVERED'),
      missing: entry.requirements.filter((item) => item.status === 'MISSING'),
    }
  }
  const toBreakdown = (text: string, status: V3JdRequirementBreakdown['status']): V3JdRequirementBreakdown => ({
    requirementText: text,
    status,
  })
  return {
    covered: cleanDisplayItems(entry.coveredRequirements).map((text) => toBreakdown(text, 'COVERED')),
    partial: cleanDisplayItems(entry.partiallyCoveredRequirements).map((text) => toBreakdown(text, 'PARTIALLY_COVERED')),
    missing: cleanDisplayItems(entry.missingRequirements).map((text) => toBreakdown(text, 'MISSING')),
  }
}

export function relatedRequirementForQuestion(
  entry: V3OpportunityEntry | undefined,
  question: V3EnhancedQuestion | { topic?: string; requirementText?: string } | undefined,
): string | undefined {
  const fromQuestion = question?.requirementText?.trim()
  if (fromQuestion) {
    return fromQuestion
  }
  if (!entry) {
    return undefined
  }
  const needle = (question?.topic ?? '').toLowerCase()
  const pools = [
    ...cleanDisplayItems(entry.partiallyCoveredRequirements),
    ...cleanDisplayItems(entry.missingRequirements),
    ...cleanDisplayItems(entry.missingFactsThatCanBeConfirmed),
    ...cleanDisplayItems(entry.coveredRequirements),
  ]
  if (!needle) {
    return pools[0]
  }
  return pools.find((item) => item.toLowerCase().includes(needle)) ?? pools[0]
}
