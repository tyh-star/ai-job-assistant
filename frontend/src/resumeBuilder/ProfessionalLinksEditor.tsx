import type { ResumeBuilderBasicInfo, ResumeBuilderProfessionalLink } from '../api/types'
import {
  MAX_PROFESSIONAL_LINKS,
  PROFESSIONAL_LINK_TYPES,
  emptyProfessionalLink,
  migrateAndSyncProfessionalLinks,
} from './professionalLinks'
import { suggestedLinkTypes } from './templateLayout'

interface ProfessionalLinksEditorProps {
  basicInfo: ResumeBuilderBasicInfo
  templateType: string
  onChange: (next: ResumeBuilderBasicInfo) => void
  t: (key: string) => string
}

export function ProfessionalLinksEditor({
  basicInfo,
  templateType,
  onChange,
  t,
}: ProfessionalLinksEditorProps) {
  const info = migrateAndSyncProfessionalLinks(basicInfo)
  const links = info.professionalLinks ?? []
  const suggested = suggestedLinkTypes(templateType)
  const missingSuggested = suggested.filter((type) => !links.some((link) => link.type === type))

  function commit(nextLinks: ResumeBuilderProfessionalLink[]) {
    onChange(
      migrateAndSyncProfessionalLinks({
        ...info,
        professionalLinks: nextLinks,
        linkedin: '',
        github: '',
        website: '',
      }),
    )
  }

  function updateLink(index: number, patch: Partial<ResumeBuilderProfessionalLink>) {
    const next = links.map((link, i) => (i === index ? { ...link, ...patch } : link))
    commit(next)
  }

  function addLink(type: string) {
    if (links.length >= MAX_PROFESSIONAL_LINKS) {
      return
    }
    commit([...links, emptyProfessionalLink(type)])
  }

  return (
    <div className="min-w-0 space-y-3">
      <p className="text-sm font-medium text-slate-800">{t('resumeBuilder.editor.professionalLinks')}</p>
      <p className="text-sm text-slate-500">{t('resumeBuilder.editor.professionalLinksHint')}</p>
      {links.map((link, index) => (
        <div
          key={`${link.type}-${index}`}
          className="grid min-w-0 grid-cols-1 gap-3 rounded-lg border border-slate-200 p-3 md:grid-cols-2"
        >
          <label className="block min-w-0 text-sm">
            <span className="mb-1 block text-slate-600">{t('resumeBuilder.fields.linkType')}</span>
            <select
              className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm"
              value={link.type}
              onChange={(event) => updateLink(index, { type: event.target.value })}
            >
              {PROFESSIONAL_LINK_TYPES.map((type) => (
                <option key={type} value={type}>
                  {t(`resumeBuilder.linkTypes.${type}`)}
                </option>
              ))}
            </select>
          </label>
          <label className="block min-w-0 text-sm">
            <span className="mb-1 block text-slate-600">{t('resumeBuilder.fields.linkLabel')}</span>
            <input
              className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm"
              value={link.label}
              onChange={(event) => updateLink(index, { label: event.target.value })}
            />
          </label>
          <label className="block min-w-0 text-sm md:col-span-2">
            <span className="mb-1 block text-slate-600">{t('resumeBuilder.fields.linkUrl')}</span>
            <input
              className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm"
              value={link.url}
              placeholder="https://"
              onChange={(event) => updateLink(index, { url: event.target.value })}
            />
          </label>
          <button
            type="button"
            className="justify-self-start rounded px-2 py-1 text-xs text-red-700"
            onClick={() => commit(links.filter((_, i) => i !== index))}
          >
            {t('resumeBuilder.editor.remove')}
          </button>
        </div>
      ))}
      <div className="flex min-w-0 flex-wrap gap-2">
        <button
          type="button"
          className="rounded-lg border border-dashed border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-50"
          disabled={links.length >= MAX_PROFESSIONAL_LINKS}
          onClick={() => addLink(suggested[0] ?? 'LINKEDIN')}
        >
          {t('resumeBuilder.editor.addProfessionalLink')}
        </button>
        {missingSuggested.map((type) => (
          <button
            key={type}
            type="button"
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-50"
            disabled={links.length >= MAX_PROFESSIONAL_LINKS}
            onClick={() => addLink(type)}
          >
            {t('resumeBuilder.editor.addSuggestedLink')}: {t(`resumeBuilder.linkTypes.${type}`)}
          </button>
        ))}
      </div>
    </div>
  )
}
