import { useTranslation } from 'react-i18next'
import type { V3JdRequirementBreakdown, V3OpportunityMap } from '../api/types'
import {
  actionableEntries,
  cleanDisplayItems,
  groupedRequirements,
  hasCannotInvent,
  mostRelevantTitles,
  priorityLabelKey,
} from '../tailor/opportunityCopy'

export function EnhancedOpportunityMap({ map }: { map: V3OpportunityMap }) {
  const { t } = useTranslation()
  const actionable = actionableEntries(map)
  const relevant = mostRelevantTitles(map)
  const entries = map.entries ?? []

  return (
    <section className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="text-lg font-medium">{t('tailor.enhanced.opportunityTitle')}</h2>
      <ul className="mt-3 space-y-1 text-sm text-slate-700">
        <li>{t('tailor.enhanced.checkedEntries', { count: entries.length })}</li>
        <li>{t('tailor.enhanced.worthEnhancing', { count: actionable.length })}</li>
        <li>{t('tailor.enhanced.optionalQuestions', { count: (map.questions ?? []).length })}</li>
        {relevant.length > 0 ? (
          <li>{t('tailor.enhanced.mostRelevant', { names: relevant.join('、') })}</li>
        ) : null}
      </ul>
      {hasCannotInvent(map) ? (
        <p className="mt-3 text-sm text-slate-600">{t('tailor.enhanced.cannotInventHint')}</p>
      ) : null}
      <ul className="mt-4 space-y-4">
        {actionable.map((entry) => {
          const groups = groupedRequirements(entry)
          return (
            <li key={entry.canonicalEntryKey} className="rounded-lg border border-slate-200 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="font-medium text-slate-900">{entry.entryTitle}</h3>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                  {t(priorityLabelKey(entry.priority))}
                </span>
              </div>
              <RequirementGroup title={t('tailor.enhanced.covered')} items={groups.covered} />
              <RequirementGroup title={t('tailor.enhanced.partial')} items={groups.partial} />
              <RequirementGroup title={t('tailor.enhanced.missing')} items={groups.missing} />
              <RequirementList
                title={t('tailor.enhanced.canStrengthen')}
                items={entry.canStrengthenWithExistingFacts}
              />
              {entry.requirements && entry.requirements.length > 0 ? null : (
                <RequirementList
                  title={t('tailor.enhanced.canConfirm')}
                  items={entry.missingFactsThatCanBeConfirmed}
                />
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function RequirementGroup({ title, items }: { title: string; items: V3JdRequirementBreakdown[] }) {
  const { t } = useTranslation()
  if (!items || items.length === 0) {
    return null
  }
  return (
    <div className="mt-3">
      <p className="text-xs font-medium text-slate-500">{title}</p>
      <ul className="mt-1 list-disc space-y-2 pl-5 text-sm text-slate-700">
        {items.map((item) => {
          const text = cleanDisplayItems([item.requirementText])[0]
          if (!text) {
            return null
          }
          const covered = cleanDisplayItems(item.coveredAtoms)
          const missing = cleanDisplayItems(item.missingAtoms)
          return (
            <li key={item.requirementId || text}>
              <span>{text}</span>
              {covered.length > 0 ? (
                <p className="mt-1 text-xs text-slate-500">
                  {t('tailor.enhanced.coveredAtoms')}: {covered.join('、')}
                </p>
              ) : null}
              {missing.length > 0 ? (
                <p className="mt-1 text-xs text-slate-500">
                  {t('tailor.enhanced.missingAtoms')}: {missing.join('、')}
                </p>
              ) : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function RequirementList({ title, items }: { title: string; items: string[] | undefined }) {
  const cleaned = cleanDisplayItems(items)
  if (cleaned.length === 0) {
    return null
  }
  return (
    <div className="mt-3">
      <p className="text-xs font-medium text-slate-500">{title}</p>
      <ul className="mt-1 list-disc space-y-1 pl-5 text-sm text-slate-700">
        {cleaned.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}
