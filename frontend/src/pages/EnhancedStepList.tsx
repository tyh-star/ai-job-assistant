import { useTranslation } from 'react-i18next'
import type { EnhancedPhase } from '../tailor/productMode'
import { ENHANCED_STEPS, stepStatus } from '../tailor/productMode'

const STEP_LABEL: Record<(typeof ENHANCED_STEPS)[number], string> = {
  scan: 'tailor.enhanced.stepScan',
  questions: 'tailor.enhanced.stepQuestions',
  review: 'tailor.enhanced.stepReview',
  generate: 'tailor.enhanced.stepGenerate',
}

export function EnhancedStepList({ phase }: { phase: EnhancedPhase }) {
  const { t } = useTranslation()
  return (
    <ol className="mt-4 flex flex-wrap gap-2" aria-label={t('tailor.enhanced.stepsLabel')}>
      {ENHANCED_STEPS.map((step, index) => {
        const status = stepStatus(step, phase)
        return (
          <li key={step}>
            <span
              className={`inline-flex items-center rounded-full px-3 py-1 text-xs ${
                status === 'current'
                  ? 'border border-slate-900 bg-slate-900 text-white'
                  : status === 'complete'
                    ? 'border border-slate-400 bg-white text-slate-800'
                    : 'border border-slate-200 bg-slate-50 text-slate-500'
              }`}
              aria-current={status === 'current' ? 'step' : undefined}
            >
              {index + 1}. {t(STEP_LABEL[step])}
              <span className="sr-only">
                {status === 'current'
                  ? t('tailor.enhanced.stepCurrent')
                  : status === 'complete'
                    ? t('tailor.enhanced.stepComplete')
                    : t('tailor.enhanced.stepUpcoming')}
              </span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
