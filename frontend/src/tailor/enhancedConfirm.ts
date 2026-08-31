import type { EditedSkillReview, EditedTailorEntry, V3EntryReview, V3SkillReview } from '../api/types'

export function eligibleEntryKeys(reviews: V3EntryReview[] | undefined): string[] {
  return (reviews ?? [])
    .filter((review) => (review.proposedNodes ?? []).some((node) => node.nodeId))
    .map((review) => review.canonicalEntryKey)
}

export function defaultSelectedEntryKeys(reviews: V3EntryReview[] | undefined): string[] {
  return eligibleEntryKeys(reviews)
}

export function reviewSuggestionMap(reviews: V3EntryReview[] | undefined): Record<string, string> {
  const map: Record<string, string> = {}
  for (const review of reviews ?? []) {
    for (const node of review.proposedNodes ?? []) {
      if (node.nodeId) {
        map[node.nodeId] = node.text ?? ''
      }
    }
  }
  return map
}

export function selectedNodeIdsForEntries(
  reviews: V3EntryReview[] | undefined,
  selectedEntryKeys: string[],
): string[] {
  const selected = new Set(selectedEntryKeys)
  const ids: string[] = []
  for (const review of reviews ?? []) {
    if (!selected.has(review.canonicalEntryKey)) {
      continue
    }
    for (const node of review.proposedNodes ?? []) {
      if (node.nodeId) {
        ids.push(node.nodeId)
      }
    }
  }
  return ids
}

export function buildEditedEntries(
  reviews: V3EntryReview[] | undefined,
  selectedEntryKeys: string[],
  currentText: Record<string, string>,
  aiOriginal: Record<string, string>,
): EditedTailorEntry[] {
  const selected = new Set(selectedEntryKeys)
  const edited: EditedTailorEntry[] = []
  for (const review of reviews ?? []) {
    if (!selected.has(review.canonicalEntryKey)) {
      continue
    }
    const nodes = review.proposedNodes ?? []
    const changed = nodes.some((node) => (currentText[node.nodeId] ?? node.text) !== (aiOriginal[node.nodeId] ?? node.text))
    if (!changed) {
      continue
    }
    edited.push({
      canonicalEntryKey: review.canonicalEntryKey,
      nodes: nodes.map((node) => ({
        nodeId: node.nodeId,
        newText: currentText[node.nodeId] ?? node.text ?? '',
      })),
    })
  }
  return edited
}

export function suggestionTypeLabelKey(suggestionType: string | undefined): string {
  if (suggestionType === 'JD_TARGETED' || suggestionType === 'JD_ALIGNED') {
    return 'tailor.enhanced.suggestionJd'
  }
  if (suggestionType === 'CLARITY' || suggestionType === 'CLARITY_FIRST' || suggestionType === 'EXPRESSION') {
    return 'tailor.enhanced.suggestionClarity'
  }
  return 'tailor.enhanced.suggestionEvidence'
}

export function isEnhancedProposal(result: {
  productMode?: string | null
  entryReviews?: V3EntryReview[]
  skillReviews?: V3SkillReview[]
} | null | undefined): boolean {
  if (!result) {
    return false
  }
  if (result.productMode === 'ENHANCED') {
    return true
  }
  return (result.entryReviews?.length ?? 0) > 0 || (result.skillReviews?.length ?? 0) > 0
}

export function hasNoHighValueRewrite(result: {
  productMode?: string | null
  hasSuggestions?: boolean
  entryReviews?: V3EntryReview[]
  skillReviews?: V3SkillReview[]
} | null | undefined): boolean {
  if (!isEnhancedProposal(result)) {
    return false
  }
  return !result?.hasSuggestions
    && (result?.entryReviews?.length ?? 0) === 0
    && (result?.skillReviews?.length ?? 0) === 0
}

export function defaultSelectedSkillReviewIds(reviews: V3SkillReview[] | undefined): string[] {
  return (reviews ?? [])
    .filter((review) => review.admitted !== false && (review.proposedSkills || (review.proposedNodes ?? []).length > 0))
    .map((review) => review.skillReviewId)
}

export function buildEditedSkillReviews(
  reviews: V3SkillReview[] | undefined,
  selectedSkillReviewIds: string[],
  currentText: Record<string, string>,
  aiOriginal: Record<string, string>,
): EditedSkillReview[] {
  const selected = new Set(selectedSkillReviewIds)
  const edited: EditedSkillReview[] = []
  for (const review of reviews ?? []) {
    if (!selected.has(review.skillReviewId)) {
      continue
    }
    const nodes = review.proposedNodes ?? []
    if (nodes.length > 0) {
      const changed = nodes.some((node) => (currentText[node.nodeId] ?? node.text) !== (aiOriginal[node.nodeId] ?? node.text))
      if (!changed) {
        continue
      }
      edited.push({
        skillReviewId: review.skillReviewId,
        proposedSkills: nodes.map((node) => currentText[node.nodeId] ?? node.text ?? '').join('\n'),
        nodes: nodes.map((node) => ({
          nodeId: node.nodeId,
          newText: currentText[node.nodeId] ?? node.text ?? '',
        })),
      })
      continue
    }
    const current = currentText[review.skillReviewId] ?? review.proposedSkills
    const original = aiOriginal[review.skillReviewId] ?? review.proposedSkills
    if (current !== original) {
      edited.push({
        skillReviewId: review.skillReviewId,
        proposedSkills: current,
      })
    }
  }
  return edited
}

export function skillSuggestionMap(reviews: V3SkillReview[] | undefined): Record<string, string> {
  const map: Record<string, string> = {}
  for (const review of reviews ?? []) {
    for (const node of review.proposedNodes ?? []) {
      if (node.nodeId) {
        map[node.nodeId] = node.text ?? ''
      }
    }
    if (review.skillReviewId) {
      map[review.skillReviewId] = review.proposedSkills ?? ''
    }
  }
  return map
}
