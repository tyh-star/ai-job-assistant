export type ProductUiMode = 'QUICK' | 'ENHANCED'

export type EnhancedPhase =
  | 'IDLE'
  | 'SCANNING'
  | 'QUESTIONS'
  | 'GENERATING_PROPOSAL'
  | 'REVIEW'
  | 'CONFIRMING'
  | 'COMPLETED'

export const ENHANCED_STEPS = ['scan', 'questions', 'review', 'generate'] as const

export type EnhancedStepId = (typeof ENHANCED_STEPS)[number]

export function stepStatus(step: EnhancedStepId, phase: EnhancedPhase): 'complete' | 'current' | 'upcoming' {
  const phaseRank: Record<EnhancedPhase, number> = {
    IDLE: -1,
    SCANNING: 0,
    QUESTIONS: 1,
    GENERATING_PROPOSAL: 1,
    REVIEW: 2,
    CONFIRMING: 3,
    COMPLETED: 4,
  }
  const stepRank: Record<EnhancedStepId, number> = {
    scan: 0,
    questions: 1,
    review: 2,
    generate: 3,
  }
  const current = phaseRank[phase]
  const target = stepRank[step]
  if (current < 0) {
    return 'upcoming'
  }
  if (current > target || (phase === 'COMPLETED' && target <= 3)) {
    return current === 4 && target === 3 ? 'complete' : current > target ? 'complete' : 'current'
  }
  if (current === target) {
    return 'current'
  }
  return 'upcoming'
}
