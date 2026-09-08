import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import { getResumeBuilderCareerMaterials } from '../api/resumeBuilder'
import type { CareerMaterialsResponse, ResumeBuilderCareerMaterials } from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import { GenericListField } from './GenericListField'

interface CareerMaterialsTabProps {
  draftId: number
  materials: ResumeBuilderCareerMaterials
  onChange: (next: ResumeBuilderCareerMaterials) => void
  /** Bumped by the parent after a save actually lands, so the generated materials
   * (computed server-side from the last SAVED content) can be refreshed. */
  refreshToken: number
}

/**
 * Facts the user enters here feed {@link CareerMaterialsResponse} -- deterministic
 * templates, zero OpenAI calls. The generated text is shown read-only below the facts
 * form; it is copy/download only and is NEVER folded into the formal ATS resume (see
 * the backend's CareerMaterialsGenerator javadoc).
 */
export function CareerMaterialsTab({ draftId, materials, onChange, refreshToken }: CareerMaterialsTabProps) {
  const { t } = useTranslation()
  const [generated, setGenerated] = useState<CareerMaterialsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    getResumeBuilderCareerMaterials(draftId)
      .then(({ data }) => {
        if (!cancelled) {
          setGenerated(data)
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(t(apiErrorKey(err)))
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId, refreshToken])

  function patch(fields: Partial<ResumeBuilderCareerMaterials>) {
    onChange({ ...materials, ...fields })
  }

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <div className="space-y-4">
        <p className="text-sm text-slate-500">{t('resumeBuilder.careerMaterials.factsHint')}</p>
        <GenericListField
          label={t('resumeBuilder.careerMaterials.selfIntroFacts')}
          value={materials.selfIntroFacts}
          onChange={(next) => patch({ selfIntroFacts: next })}
        />
        <GenericListField
          label={t('resumeBuilder.careerMaterials.aiUsageScenarios')}
          value={materials.aiUsageScenarios}
          onChange={(next) => patch({ aiUsageScenarios: next })}
        />
        <GenericListField
          label={t('resumeBuilder.careerMaterials.aiStrengths')}
          value={materials.aiStrengths}
          onChange={(next) => patch({ aiStrengths: next })}
        />
        <GenericListField
          label={t('resumeBuilder.careerMaterials.aiRisks')}
          value={materials.aiRisks}
          onChange={(next) => patch({ aiRisks: next })}
        />
        <GenericListField
          label={t('resumeBuilder.careerMaterials.aiVerificationApproach')}
          value={materials.aiVerificationApproach}
          onChange={(next) => patch({ aiVerificationApproach: next })}
        />
        <label className="block text-sm">
          <span className="mb-1 block text-slate-600">{t('resumeBuilder.careerMaterials.oneYearGoal')}</span>
          <textarea
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            rows={2}
            value={materials.oneYearGoal}
            onChange={(event) => patch({ oneYearGoal: event.target.value })}
          />
        </label>
        <label className="block text-sm">
          <span className="mb-1 block text-slate-600">{t('resumeBuilder.careerMaterials.threeYearGoal')}</span>
          <textarea
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            rows={2}
            value={materials.threeYearGoal}
            onChange={(event) => patch({ threeYearGoal: event.target.value })}
          />
        </label>
        <GenericListField
          label={t('resumeBuilder.careerMaterials.skillsToImprove')}
          value={materials.skillsToImprove}
          onChange={(next) => patch({ skillsToImprove: next })}
        />
      </div>

      <div className="space-y-4">
        <p className="text-sm text-slate-500">{t('resumeBuilder.careerMaterials.generatedHint')}</p>
        {loading ? <Spinner label={t('common.loading')} /> : null}
        {error ? <Alert message={error} /> : null}
        {generated ? (
          <div className="space-y-4">
            <GeneratedBlock title={t('resumeBuilder.careerMaterials.positioningSummary')} text={generated.positioningSummary} />
            <GeneratedBlock title={t('resumeBuilder.careerMaterials.selfIntroduction')} text={generated.selfIntroduction} />
            <GeneratedBlock title={t('resumeBuilder.careerMaterials.aiReflection')} text={generated.aiReflection} />
            <GeneratedBlock
              title={t('resumeBuilder.careerMaterials.strengthsAndWeaknesses')}
              text={generated.strengthsAndWeaknesses}
            />
            <GeneratedBlock title={t('resumeBuilder.careerMaterials.developmentPlan')} text={generated.developmentPlan} />
          </div>
        ) : null}
      </div>
    </div>
  )
}

function GeneratedBlock({ title, text }: { title: string; text: string }) {
  const { t } = useTranslation()
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="mb-1 flex items-center justify-between gap-2">
        <h4 className="text-sm font-semibold text-slate-800">{title}</h4>
        <button
          type="button"
          className="text-xs text-slate-600 underline"
          onClick={() => {
            void navigator.clipboard?.writeText(text)
          }}
        >
          {t('resumeBuilder.careerMaterials.copy')}
        </button>
      </div>
      <p className="whitespace-pre-line text-sm text-slate-700">{text}</p>
    </div>
  )
}
