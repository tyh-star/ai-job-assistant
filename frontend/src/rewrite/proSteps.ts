import type { ProRewriteSession } from '../api/types'

/** The six Pro steps, in order. */
export const PRO_STEPS = ['analyze', 'check', 'questions', 'review', 'confirm', 'generate'] as const

/** How many Pro steps are done for this session state (0 = nothing yet). */
export function proStepsDone(session: ProRewriteSession | null): number {
  if (!session) {
    return 0
  }
  switch (session.status) {
    case 'ANALYZED':
      return session.questions.length > 0 ? 2 : 3
    case 'ANSWERED':
      return 3
    case 'PREVIEWED':
      return 4
    case 'COMPLETED':
      return PRO_STEPS.length
  }
}
