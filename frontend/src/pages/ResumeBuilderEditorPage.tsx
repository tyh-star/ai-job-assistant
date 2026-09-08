import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  generateResumeBuilderDraft,
  getResumeBuilderDraft,
  newIdempotencyKey,
  previewResumeBuilderDraft,
  updateResumeBuilderDraft,
} from '../api/resumeBuilder'
import type {
  NormalizedResume,
  ResumeBuilderDraftContent,
  ResumeBuilderTemplateType,
} from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'
import { CareerMaterialsTab } from '../resumeBuilder/CareerMaterialsTab'
import {
  DEFAULT_SECTION_ORDER,
  SECTION_TITLE_KEY,
  TEMPLATE_TYPES,
  blankDraftContent,
  customSectionIdFromRef,
  customSectionRef,
  isCustomSectionRef,
} from '../resumeBuilder/constants'
import { GenericListField } from '../resumeBuilder/GenericListField'
import { PreviewPanel } from '../resumeBuilder/PreviewPanel'
import { RepeatableSection } from '../resumeBuilder/RepeatableSection'
import { buildSectionSchemas, customSectionSchema, skillCategorySchema } from '../resumeBuilder/sectionSchemas'

type SaveStatus = 'idle' | 'saving' | 'saved' | 'conflict' | 'error'
type Tab = 'sections' | 'careerMaterials' | 'preview'

const AUTOSAVE_DELAY_MS = 1200

export function ResumeBuilderEditorPage() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const draftId = Number(id)

  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [title, setTitle] = useState('')
  const [templateType, setTemplateType] = useState<ResumeBuilderTemplateType>('GENERIC_ATS')
  const [targetRole, setTargetRole] = useState('')
  const [locale, setLocale] = useState<'en' | 'zh-CN'>('en')
  const [content, setContent] = useState<ResumeBuilderDraftContent>(blankDraftContent())
  const [version, setVersion] = useState(0)
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle')
  const [frozen, setFrozen] = useState(false)
  const [tab, setTab] = useState<Tab>('sections')
  const [preview, setPreview] = useState<NormalizedResume | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [generateError, setGenerateError] = useState('')
  const [generatedResumeId, setGeneratedResumeId] = useState<number | null>(null)
  const [careerMaterialsRefreshToken, setCareerMaterialsRefreshToken] = useState(0)

  const loadedRef = useRef(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Always-current snapshot of everything a save needs, read ONLY inside flushSave --
  // never from the effect's own closure. This is what makes two rapid edits in the
  // SAME tab safe: without it, a save already in flight when a second edit lands would
  // capture a stale `version` in its closure and could 409 against itself once the
  // first save's response arrives, even though nothing outside this tab ever touched
  // the draft. See flushSave's javadoc-style comment below for the full mechanism.
  const latestRef = useRef({ title, templateType, targetRole, locale, content, version })
  const savingInFlightRef = useRef(false)
  const dirtySinceLastSaveRef = useRef(false)
  const frozenRef = useRef(false)
  const schemas = useMemo(() => buildSectionSchemas(t), [t])
  const skillSchema = useMemo(() => skillCategorySchema(t), [t])
  const customSchema = useMemo(() => customSectionSchema(t), [t])

  async function load() {
    setLoading(true)
    setLoadError('')
    loadedRef.current = false
    try {
      const { data } = await getResumeBuilderDraft(draftId)
      setTitle(data.title)
      setTemplateType(data.templateType as ResumeBuilderTemplateType)
      setTargetRole(data.targetRole ?? '')
      setLocale((data.locale as 'en' | 'zh-CN') ?? 'en')
      setContent(data.content)
      setVersion(data.version)
      setFrozen(false)
      setSaveStatus('idle')
    } catch (err) {
      setLoadError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
      // Allow the autosave effect to react to future changes, but not to this load.
      setTimeout(() => {
        loadedRef.current = true
      }, 0)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId])

  // Keep the ref snapshot current on every render -- flushSave reads ONLY this, never
  // the enclosing effect's own closure (see latestRef's declaration above for why).
  useEffect(() => {
    latestRef.current = { title, templateType, targetRole, locale, content, version }
  })
  useEffect(() => {
    frozenRef.current = frozen
  }, [frozen])

  /**
   * Sends exactly one PUT, always built from {@link latestRef} (never from a stale
   * closure), and never overlaps a second one: if a request is already in flight when
   * this is called again, it just records "a newer edit exists" and returns -- the
   * IN-FLIGHT request's own completion handler notices that flag and immediately
   * fires the next save itself, by which point latestRef.current.version already
   * reflects the version the first save just returned. This is what makes two edits
   * typed in quick succession in the SAME tab never 409 against each other: without
   * serializing like this, a save whose debounce timer fires while an earlier save
   * is still in flight would capture a `version` that the earlier save is about to
   * make stale, and would fail once the earlier one's response lands.
   */
  async function flushSave() {
    if (savingInFlightRef.current || frozenRef.current) {
      dirtySinceLastSaveRef.current = true
      return
    }
    savingInFlightRef.current = true
    dirtySinceLastSaveRef.current = false
    setSaveStatus('saving')
    const snapshot = latestRef.current
    try {
      const { data } = await updateResumeBuilderDraft(draftId, {
        title: snapshot.title.trim() || t('resumeBuilder.list.untitled'),
        templateType: snapshot.templateType,
        targetRole: snapshot.targetRole.trim() || null,
        locale: snapshot.locale,
        content: snapshot.content,
        version: snapshot.version,
      })
      // Update the ref SYNCHRONOUSLY (not just via setVersion + a future render) so a
      // save queued immediately below already sees the new version.
      latestRef.current = { ...latestRef.current, version: data.version }
      setVersion(data.version)
      setSaveStatus('saved')
    } catch (err) {
      const key = apiErrorKey(err)
      if (key === 'resumeBuilder.saveConflict') {
        setFrozen(true)
        frozenRef.current = true
        setSaveStatus('conflict')
      } else {
        setSaveStatus('error')
      }
    } finally {
      savingInFlightRef.current = false
    }
    if (dirtySinceLastSaveRef.current && !frozenRef.current) {
      dirtySinceLastSaveRef.current = false
      void flushSave()
    }
  }

  // Autosave: debounced PUT whenever title/template/targetRole/locale/content change,
  // after the initial load. Frozen (a save conflict happened) stops further autosaves
  // until the user explicitly reloads -- never silently overwrites a newer version.
  useEffect(() => {
    if (!loadedRef.current || frozen) {
      return
    }
    setSaveStatus('saving')
    if (debounceRef.current) {
      clearTimeout(debounceRef.current)
    }
    debounceRef.current = setTimeout(() => {
      void flushSave()
    }, AUTOSAVE_DELAY_MS)
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title, templateType, targetRole, locale, content])

  async function loadPreview() {
    setPreviewLoading(true)
    try {
      const { data } = await previewResumeBuilderDraft(draftId)
      setPreview(data)
    } catch {
      setPreview(null)
    } finally {
      setPreviewLoading(false)
    }
  }

  useEffect(() => {
    if (tab === 'preview' && loadedRef.current) {
      void loadPreview()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  async function onGenerate() {
    setGenerating(true)
    setGenerateError('')
    try {
      const { data } = await generateResumeBuilderDraft(draftId, { idempotencyKey: newIdempotencyKey() })
      setGeneratedResumeId(data.id)
    } catch (err) {
      setGenerateError(t(apiErrorKey(err)))
    } finally {
      setGenerating(false)
    }
  }

  function patchContent(patch: Partial<ResumeBuilderDraftContent>) {
    setContent((prev) => ({ ...prev, ...patch }))
  }

  function moveSectionOrder(sectionId: string, delta: number) {
    const order = content.sectionOrder
    const index = order.indexOf(sectionId)
    const target = index + delta
    if (index < 0 || target < 0 || target >= order.length) {
      return
    }
    const next = order.slice()
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    patchContent({ sectionOrder: next })
  }

  function toggleSectionEnabled(sectionId: string) {
    patchContent({
      sectionEnabled: { ...content.sectionEnabled, [sectionId]: !(content.sectionEnabled[sectionId] ?? true) },
    })
  }

  function sectionLabel(sectionId: string): string {
    if (isCustomSectionRef(sectionId)) {
      const customId = customSectionIdFromRef(sectionId)
      const section = content.customSections.find((s) => s.id === customId)
      return section?.title || t('resumeBuilder.sections.customFallback')
    }
    return t(SECTION_TITLE_KEY[sectionId] ?? sectionId)
  }

  function addCustomSection() {
    const entry = customSchema.emptyEntry()
    const newId = entry.id as string
    patchContent({
      customSections: [...content.customSections, entry as unknown as ResumeBuilderDraftContent['customSections'][number]],
      sectionOrder: [...content.sectionOrder, customSectionRef(newId)],
      sectionEnabled: { ...content.sectionEnabled, [customSectionRef(newId)]: true },
    })
  }

  if (loading) {
    return <Spinner label={t('common.loading')} />
  }
  if (loadError) {
    return <Alert message={loadError} onRetry={() => void load()} />
  }

  const orderedSectionIds = content.sectionOrder.length > 0 ? content.sectionOrder : DEFAULT_SECTION_ORDER

  return (
    <div className="pb-24">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link to="/resumes/builder" className="text-sm text-slate-500 underline">
            {t('resumeBuilder.list.backToList')}
          </Link>
          <h1 className="mt-1 text-2xl font-semibold text-slate-900">{t('resumeBuilder.editor.title')}</h1>
        </div>
        <SaveStatusBadge status={saveStatus} onReload={() => void load()} />
      </div>

      {frozen ? (
        <div className="mt-4">
          <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            <p>{t('resumeBuilder.editor.conflictBanner')}</p>
            <button type="button" className="mt-2 font-medium underline" onClick={() => void load()}>
              {t('resumeBuilder.editor.reload')}
            </button>
          </div>
        </div>
      ) : null}

      <div className="mt-4 flex gap-2 border-b border-slate-200">
        {(['sections', 'careerMaterials', 'preview'] as Tab[]).map((tabId) => (
          <button
            key={tabId}
            type="button"
            className={`px-3 py-2 text-sm ${tab === tabId ? 'border-b-2 border-slate-900 font-medium text-slate-900' : 'text-slate-500'}`}
            onClick={() => setTab(tabId)}
          >
            {t(`resumeBuilder.editor.tab.${tabId}`)}
          </button>
        ))}
      </div>

      {tab === 'sections' ? (
        <div className="mt-4 grid grid-cols-1 gap-6 md:grid-cols-[220px_1fr]">
          {/* Desktop sidebar: vertical section list with reorder/enable controls. */}
          <nav className="hidden md:block">
            <div className="sticky top-4 space-y-1 rounded-xl border border-slate-200 bg-white p-3">
              <p className="px-1 text-xs font-semibold uppercase text-slate-400">{t('resumeBuilder.editor.jumpTo')}</p>
              <SectionOrderList
                sectionIds={orderedSectionIds}
                labelFor={sectionLabel}
                enabled={content.sectionEnabled}
                onMove={moveSectionOrder}
                onToggle={toggleSectionEnabled}
                onReorderTo={(from, to) => {
                  const next = orderedSectionIds.slice()
                  const [item] = next.splice(from, 1)
                  next.splice(to, 0, item)
                  patchContent({ sectionOrder: next })
                }}
                layout="vertical"
              />
              <button
                type="button"
                className="mt-2 w-full rounded-lg border border-dashed border-slate-300 px-2 py-1.5 text-xs text-slate-600"
                onClick={addCustomSection}
              >
                {t('resumeBuilder.editor.addCustomSection')}
              </button>
            </div>
          </nav>

          {/* Mobile: horizontal "jump to section" strip instead of a sidebar. */}
          <div className="-mx-1 mb-2 flex gap-1 overflow-x-auto pb-2 md:hidden">
            <SectionOrderList
              sectionIds={orderedSectionIds}
              labelFor={sectionLabel}
              enabled={content.sectionEnabled}
              onMove={moveSectionOrder}
              onToggle={toggleSectionEnabled}
              onReorderTo={(from, to) => {
                const next = orderedSectionIds.slice()
                const [item] = next.splice(from, 1)
                next.splice(to, 0, item)
                patchContent({ sectionOrder: next })
              }}
              layout="horizontal"
            />
          </div>

          <div className="space-y-6">
            <details open className="rounded-xl border border-slate-200 bg-white p-4">
              <summary className="cursor-pointer text-sm font-semibold text-slate-800">
                {t('resumeBuilder.editor.metaHeading')}
              </summary>
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block text-sm">
                  <span className="mb-1 block text-slate-600">{t('resumeBuilder.editor.draftTitle')}</span>
                  <input
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                    value={title}
                    onChange={(event) => setTitle(event.target.value)}
                  />
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block text-slate-600">{t('resumeBuilder.list.templateType')}</span>
                  <select
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                    value={templateType}
                    onChange={(event) => setTemplateType(event.target.value as ResumeBuilderTemplateType)}
                  >
                    {TEMPLATE_TYPES.map((type) => (
                      <option key={type} value={type}>
                        {type}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block text-slate-600">{t('resumeBuilder.editor.targetRole')}</span>
                  <input
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                    value={targetRole}
                    onChange={(event) => setTargetRole(event.target.value)}
                  />
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block text-slate-600">{t('resumeBuilder.editor.locale')}</span>
                  <select
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                    value={locale}
                    onChange={(event) => setLocale(event.target.value as 'en' | 'zh-CN')}
                  >
                    <option value="en">English</option>
                    <option value="zh-CN">中文</option>
                  </select>
                </label>
              </div>
            </details>

            <details open id="section-basic-info" className="rounded-xl border border-slate-200 bg-white p-4">
              <summary className="cursor-pointer text-sm font-semibold text-slate-800">
                {t('resumeBuilder.sections.basicInfo')}
              </summary>
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                {(['fullName', 'city', 'country', 'phone', 'email', 'linkedin', 'github', 'website'] as const).map((key) => (
                  <label key={key} className="block text-sm">
                    <span className="mb-1 block text-slate-600">{t(`resumeBuilder.fields.${key}`)}</span>
                    <input
                      className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                      value={content.basicInfo[key]}
                      onChange={(event) =>
                        patchContent({ basicInfo: { ...content.basicInfo, [key]: event.target.value } })
                      }
                    />
                  </label>
                ))}
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    checked={content.basicInfo.legallyEligibleToWork === true}
                    onChange={(event) =>
                      patchContent({
                        basicInfo: {
                          ...content.basicInfo,
                          legallyEligibleToWork: event.target.checked ? true : null,
                        },
                      })
                    }
                  />
                  {t('resumeBuilder.fields.legallyEligibleToWork')}
                </label>
              </div>
            </details>

            <details open id="section-job-target" className="rounded-xl border border-slate-200 bg-white p-4">
              <summary className="cursor-pointer text-sm font-semibold text-slate-800">
                {t('resumeBuilder.sections.jobTarget')}
              </summary>
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                {(['targetTitle', 'jobLevel', 'targetIndustry', 'workMode', 'targetCity'] as const).map((key) => (
                  <label key={key} className="block text-sm">
                    <span className="mb-1 block text-slate-600">{t(`resumeBuilder.fields.${key}`)}</span>
                    <input
                      className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                      value={content.jobTarget[key]}
                      onChange={(event) =>
                        patchContent({ jobTarget: { ...content.jobTarget, [key]: event.target.value } })
                      }
                    />
                  </label>
                ))}
                <label className="block text-sm sm:col-span-2">
                  <span className="mb-1 block text-slate-600">{t('resumeBuilder.fields.careerSummary')}</span>
                  <textarea
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                    rows={3}
                    value={content.jobTarget.careerSummary}
                    onChange={(event) =>
                      patchContent({ jobTarget: { ...content.jobTarget, careerSummary: event.target.value } })
                    }
                  />
                </label>
              </div>
            </details>

            {orderedSectionIds.map((sectionId) => (
              <details
                key={sectionId}
                open
                id={`section-${sectionId.replace(/[^a-zA-Z0-9]/g, '-')}`}
                className="rounded-xl border border-slate-200 bg-white p-4"
              >
                <summary className="cursor-pointer text-sm font-semibold text-slate-800">
                  {sectionLabel(sectionId)}
                  {content.sectionEnabled[sectionId] === false ? (
                    <span className="ml-2 text-xs font-normal text-slate-400">
                      ({t('resumeBuilder.editor.disabled')})
                    </span>
                  ) : null}
                </summary>
                <div className="mt-3">{renderSectionBody(sectionId)}</div>
              </details>
            ))}
          </div>
        </div>
      ) : null}

      {tab === 'careerMaterials' ? (
        <div className="mt-4">
          <CareerMaterialsTab
            draftId={draftId}
            materials={content.careerMaterials}
            onChange={(next) => patchContent({ careerMaterials: next })}
            refreshToken={careerMaterialsRefreshToken}
          />
          <button
            type="button"
            className="mt-4 rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
            onClick={() => setCareerMaterialsRefreshToken((n) => n + 1)}
          >
            {t('resumeBuilder.careerMaterials.refresh')}
          </button>
        </div>
      ) : null}

      {tab === 'preview' ? (
        <div className="mt-4 space-y-4">
          <PreviewPanel preview={preview} loading={previewLoading} />
          {generateError ? <Alert message={generateError} /> : null}
          {generatedResumeId ? (
            <div className="rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
              {t('resumeBuilder.editor.generatedSuccess')}{' '}
              <Link className="underline" to="/resumes">
                {t('resumeBuilder.editor.viewInMyResumes')}
              </Link>
            </div>
          ) : null}
          <button
            type="button"
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm text-white disabled:opacity-60"
            disabled={generating || preview === null || !preview.readyToGenerate}
            onClick={() => void onGenerate()}
          >
            {generating ? t('common.processing') : t('resumeBuilder.editor.generate')}
          </button>
        </div>
      ) : null}
    </div>
  )

  function renderSectionBody(sectionId: string) {
    if (isCustomSectionRef(sectionId)) {
      const customId = customSectionIdFromRef(sectionId)
      const section = content.customSections.find((s) => s.id === customId)
      if (!section) {
        return null
      }
      return (
        <RepeatableSection
          entries={[section as unknown as Record<string, unknown>]}
          onChange={(next) => {
            const updated = next[0] as unknown as ResumeBuilderDraftContent['customSections'][number] | undefined
            patchContent({
              customSections: updated
                ? content.customSections.map((s) => (s.id === customId ? updated : s))
                : content.customSections.filter((s) => s.id !== customId),
              sectionOrder: updated ? content.sectionOrder : content.sectionOrder.filter((id) => id !== sectionId),
            })
          }}
          fields={customSchema.fields}
          emptyEntry={customSchema.emptyEntry}
          headingFor={customSchema.headingFor}
          addLabel={t('resumeBuilder.editor.addEntry')}
          removeLabel={t('resumeBuilder.editor.removeSection')}
          moveUpLabel={t('resumeBuilder.editor.moveUp')}
          moveDownLabel={t('resumeBuilder.editor.moveDown')}
          collapseLabel={t('resumeBuilder.editor.collapse')}
          expandLabel={t('resumeBuilder.editor.expand')}
          emptyHint=""
        />
      )
    }

    if (sectionId === 'SKILLS') {
      return (
        <RepeatableSection
          entries={content.skillCategories as unknown as Record<string, unknown>[]}
          onChange={(next) =>
            patchContent({ skillCategories: next as unknown as ResumeBuilderDraftContent['skillCategories'] })
          }
          fields={skillSchema.fields}
          emptyEntry={skillSchema.emptyEntry}
          headingFor={skillSchema.headingFor}
          addLabel={t('resumeBuilder.editor.addCategory')}
          removeLabel={t('resumeBuilder.editor.remove')}
          moveUpLabel={t('resumeBuilder.editor.moveUp')}
          moveDownLabel={t('resumeBuilder.editor.moveDown')}
          collapseLabel={t('resumeBuilder.editor.collapse')}
          expandLabel={t('resumeBuilder.editor.expand')}
          emptyHint={t('resumeBuilder.editor.emptySkills')}
        />
      )
    }

    if (sectionId === 'HOBBIES') {
      return (
        <GenericListField
          label={t('resumeBuilder.sections.hobbies')}
          value={content.hobbies}
          onChange={(next) => patchContent({ hobbies: next })}
        />
      )
    }

    const schema = schemas[sectionId]
    if (!schema) {
      return null
    }
    const entriesKey = SECTION_TO_CONTENT_KEY[sectionId]
    if (!entriesKey) {
      return null
    }
    const entries = content[entriesKey] as unknown as Record<string, unknown>[]
    return (
      <RepeatableSection
        entries={entries}
        onChange={(next) => patchContent({ [entriesKey]: next } as Partial<ResumeBuilderDraftContent>)}
        fields={schema.fields}
        emptyEntry={schema.emptyEntry}
        headingFor={schema.headingFor}
        addLabel={t('resumeBuilder.editor.addEntry')}
        removeLabel={t('resumeBuilder.editor.remove')}
        moveUpLabel={t('resumeBuilder.editor.moveUp')}
        moveDownLabel={t('resumeBuilder.editor.moveDown')}
        collapseLabel={t('resumeBuilder.editor.collapse')}
        expandLabel={t('resumeBuilder.editor.expand')}
        emptyHint={t('resumeBuilder.editor.emptySection')}
      />
    )
  }
}

const SECTION_TO_CONTENT_KEY: Record<string, keyof ResumeBuilderDraftContent | undefined> = {
  EDUCATION: 'education',
  EXPERIENCE: 'experience',
  PROJECTS: 'projects',
  VOLUNTEER: 'volunteer',
  CERTIFICATIONS: 'certifications',
  LANGUAGES: 'languages',
  LEADERSHIP: 'leadership',
  PUBLICATIONS: 'publications',
}

function SaveStatusBadge({ status, onReload }: { status: SaveStatus; onReload: () => void }) {
  const { t } = useTranslation()
  if (status === 'idle') {
    return null
  }
  const map: Record<SaveStatus, { text: string; className: string }> = {
    idle: { text: '', className: '' },
    saving: { text: t('resumeBuilder.editor.status.saving'), className: 'text-slate-500' },
    saved: { text: t('resumeBuilder.editor.status.saved'), className: 'text-emerald-700' },
    conflict: { text: t('resumeBuilder.editor.status.conflict'), className: 'text-amber-700' },
    error: { text: t('resumeBuilder.editor.status.error'), className: 'text-red-700' },
  }
  const info = map[status]
  return (
    <span className={`text-sm ${info.className}`}>
      {info.text}
      {status === 'error' ? (
        <button type="button" className="ml-2 underline" onClick={onReload}>
          {t('common.retry')}
        </button>
      ) : null}
    </span>
  )
}

interface SectionOrderListProps {
  sectionIds: string[]
  labelFor: (id: string) => string
  enabled: Record<string, boolean>
  onMove: (id: string, delta: number) => void
  onToggle: (id: string) => void
  onReorderTo: (from: number, to: number) => void
  layout: 'vertical' | 'horizontal'
}

function SectionOrderList({ sectionIds, labelFor, enabled, onMove, onToggle, onReorderTo, layout }: SectionOrderListProps) {
  const { t } = useTranslation()
  const [dragIndex, setDragIndex] = useState<number | null>(null)
  const containerClass = layout === 'vertical' ? 'flex flex-col gap-1' : 'flex flex-row gap-1'

  return (
    <div className={containerClass}>
      {sectionIds.map((sectionId, index) => (
        <div
          key={sectionId}
          className="flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-xs"
          draggable
          onDragStart={() => setDragIndex(index)}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault()
            if (dragIndex !== null) {
              onReorderTo(dragIndex, index)
            }
            setDragIndex(null)
          }}
        >
          <input
            type="checkbox"
            checked={enabled[sectionId] !== false}
            onChange={() => onToggle(sectionId)}
            aria-label={t('resumeBuilder.editor.toggleSection')}
          />
          <a href={`#section-${sectionId.replace(/[^a-zA-Z0-9]/g, '-')}`} className="whitespace-nowrap text-slate-700">
            {labelFor(sectionId)}
          </a>
          <button
            type="button"
            aria-label={t('resumeBuilder.editor.moveUp')}
            className="text-slate-500 disabled:opacity-30"
            disabled={index === 0}
            onClick={() => onMove(sectionId, -1)}
          >
            ↑
          </button>
          <button
            type="button"
            aria-label={t('resumeBuilder.editor.moveDown')}
            className="text-slate-500 disabled:opacity-30"
            disabled={index === sectionIds.length - 1}
            onClick={() => onMove(sectionId, 1)}
          >
            ↓
          </button>
        </div>
      ))}
    </div>
  )
}
