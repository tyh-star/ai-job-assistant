import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ResumeEvidenceItem, V3EvidenceUsage, V3OpportunityMap } from '../api/types'
import {
  entryTitleForKey,
  evidenceDisplayText,
  usageCounts,
  usageReasonKey,
} from '../tailor/evidenceUsage'

export function EnhancedEvidenceUsagePanel({
  usages,
  items,
  opportunity,
  busy,
  onCorrect,
  onDelete,
}: {
  usages: V3EvidenceUsage[]
  items: ResumeEvidenceItem[]
  opportunity: V3OpportunityMap | null
  busy: boolean
  onCorrect: (usage: V3EvidenceUsage, text: string) => Promise<void>
  onDelete: (usage: V3EvidenceUsage) => Promise<void>
}) {
  const { t } = useTranslation()
  const counts = usageCounts(usages)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [deleteId, setDeleteId] = useState<number | null>(null)
  if (counts.saved === 0 && usages.length === 0) {
    return null
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5">
      <p className="text-sm text-slate-800">
        {t('tailor.enhanced.usageSummary', counts)}
      </p>
      <ul className="mt-3 space-y-3">
        {usages.map((usage) => {
          const text = evidenceDisplayText(usage, items)
          const used = (usage.usageStatus ?? '').startsWith('USED_')
          const titles = (usage.usedInEntryKeys ?? [])
            .map((key) => entryTitleForKey(key, opportunity))
            .filter(Boolean)
          const skills = usage.usedInSkillKeys ?? []
          return (
            <li key={usage.evidenceItemId} className="rounded-lg border border-slate-100 p-3">
              {text ? <p className="text-sm text-slate-800">{text}</p> : null}
              <p className="mt-1 text-sm text-slate-600">
                {used ? (
                  <>
                    {titles.length > 0 ? t('tailor.enhanced.usedInEntry', { name: titles.join(', ') }) : null}
                    {titles.length > 0 && skills.length > 0 ? ' · ' : null}
                    {skills.length > 0 ? t('tailor.enhanced.usedInSkills', { names: skills.join(', ') }) : null}
                    {titles.length === 0 && skills.length === 0 ? t('tailor.enhanced.usedInEnhancement') : null}
                  </>
                ) : usage.usageStatus === 'USER_REJECTED' ? (
                  t('tailor.enhanced.userRejected')
                ) : (
                  t(usageReasonKey(usage.reasonCode))
                )}
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                <button type="button" disabled={busy}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                  onClick={() => {
                    setEditingId(usage.evidenceItemId)
                    setDraft(text)
                    setDeleteId(null)
                  }}>
                  {t('tailor.enhanced.correctEvidence')}
                </button>
                <button type="button" disabled={busy}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                  onClick={() => setDeleteId(usage.evidenceItemId)}>
                  {t('tailor.enhanced.deleteEvidence')}
                </button>
              </div>
              {editingId === usage.evidenceItemId ? (
                <div className="mt-2">
                  <textarea
                    className="min-h-20 w-full rounded-md border border-slate-300 p-2 text-sm"
                    value={draft}
                    disabled={busy}
                    onChange={(event) => setDraft(event.target.value)}
                  />
                  <div className="mt-2 flex gap-2">
                    <button type="button" disabled={busy}
                      className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white disabled:opacity-60"
                      onClick={() => void onCorrect(usage, draft).then(() => setEditingId(null))}>
                      {t('tailor.saveEdit')}
                    </button>
                    <button type="button" disabled={busy}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                      onClick={() => setEditingId(null)}>
                      {t('common.no')}
                    </button>
                  </div>
                </div>
              ) : null}
              {deleteId === usage.evidenceItemId ? (
                <div className="mt-2 rounded-md bg-slate-50 p-3 text-sm">
                  <p>{t('tailor.enhanced.deleteConfirm')}</p>
                  <div className="mt-2 flex gap-2">
                    <button type="button" disabled={busy}
                      className="rounded-lg bg-slate-900 px-3 py-1.5 text-xs text-white disabled:opacity-60"
                      onClick={() => void onDelete(usage).then(() => setDeleteId(null))}>
                      {t('tailor.enhanced.deleteEvidence')}
                    </button>
                    <button type="button" disabled={busy}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:opacity-60"
                      onClick={() => setDeleteId(null)}>
                      {t('common.no')}
                    </button>
                  </div>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
