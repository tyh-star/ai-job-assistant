import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/client'
import {
  getResumeStructure,
  updateResumeStructure,
} from '../api/resumes'
import type {
  ResumeBullet,
  ResumeEducation,
  ResumeExperience,
  ResumeProject,
  ResumeSkill,
  ResumeStructure,
} from '../api/types'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'

export function ResumeStructurePage() {
  const { t } = useTranslation()
  const params = useParams()
  const [searchParams] = useSearchParams()
  const resumeId = Number(params.id)
  const nextTailor = searchParams.get('next') === 'tailor'
  const [structure, setStructure] = useState<ResumeStructure | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)

  async function load() {
    setLoading(true)
    setError('')
    try {
      const { data } = await getResumeStructure(resumeId)
      setStructure(normalizeStructure(data))
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!Number.isFinite(resumeId)) {
      return
    }
    void load()
  }, [resumeId])

  async function save() {
    if (!structure) {
      return
    }
    if (!structure.contact.fullName.trim()) {
      setError(t('resumes.fullNameRequired'))
      return
    }
    setSaving(true)
    setError('')
    try {
      const { data } = await updateResumeStructure(resumeId, structure)
      setStructure(normalizeStructure(data))
      setSaved(true)
    } catch (err) {
      setError(t(apiErrorKey(err)))
    } finally {
      setSaving(false)
    }
  }

  if (!Number.isFinite(resumeId)) {
    return <Alert message={t('errors.notFound')} />
  }

  return (
    <div>
      <Link className="text-sm text-slate-600 underline" to="/resumes">
        {t('resumes.backToList')}
      </Link>
      <h1 className="page-title mt-3">{t('resumes.structureTitle')}</h1>
      <p className="mt-1 text-sm text-slate-500">{t('resumes.structureHint')}</p>
      {loading ? <div className="mt-6"><Spinner label={t('common.loading')} /></div> : null}
      {error ? <div className="mt-4"><Alert message={error} /></div> : null}
      {saved ? (
        <div className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
          {t('resumes.structureSaved')}
          <Link className="ml-3 underline" to={`/tailor?resumeId=${resumeId}`}>
            {t('resumes.startTailor')}
          </Link>
        </div>
      ) : null}
      {structure ? (
        <div className="mt-6 space-y-8">
          <Section title={t('resumes.contact')}>
            <Field label={t('resumes.fullName')} value={structure.contact.fullName}
              onChange={(value) => setStructure({
                ...structure,
                contact: { ...structure.contact, fullName: value },
              })} />
            <Field label={t('auth.email')} value={structure.contact.email}
              onChange={(value) => setStructure({
                ...structure,
                contact: { ...structure.contact, email: value },
              })} />
            <Field label={t('resumes.phone')} value={structure.contact.phone}
              onChange={(value) => setStructure({
                ...structure,
                contact: { ...structure.contact, phone: value },
              })} />
            <Field label={t('resumes.location')} value={structure.contact.location}
              onChange={(value) => setStructure({
                ...structure,
                contact: { ...structure.contact, location: value },
              })} />
            <ListEditor
              label={t('resumes.links')}
              values={structure.contact.links}
              onChange={(links) => setStructure({
                ...structure,
                contact: { ...structure.contact, links },
              })}
            />
          </Section>

          <Section title={t('resumes.summary')}>
            <ListEditor
              label={t('resumes.bullets')}
              values={structure.summaryBullets.map((item) => item.text)}
              onChange={(texts) => setStructure({
                ...structure,
                summaryBullets: mergeBulletTexts(structure.summaryBullets, texts),
              })}
            />
          </Section>

          <Section title={t('resumes.skills')}>
            <ListEditor
              label={t('resumes.skillName')}
              values={structure.skills.map((item) => item.name)}
              onChange={(names) => setStructure({
                ...structure,
                skills: mergeSkillNames(structure.skills, names),
              })}
            />
          </Section>

          <Section title={t('resumes.experience')}>
            {structure.experience.map((job, index) => (
              <div key={index} className="mb-4 rounded-lg border border-slate-200 p-4">
                <Field label={t('resumes.company')} value={job.company}
                  onChange={(value) => updateExperience(structure, setStructure, index, { company: value })} />
                <Field label={t('resumes.jobTitle')} value={job.title}
                  onChange={(value) => updateExperience(structure, setStructure, index, { title: value })} />
                <Field label={t('resumes.startDate')} value={job.startDate}
                  onChange={(value) => updateExperience(structure, setStructure, index, { startDate: value })} />
                <Field label={t('resumes.endDate')} value={job.endDate}
                  onChange={(value) => updateExperience(structure, setStructure, index, { endDate: value })} />
                <Field label={t('resumes.location')} value={job.location}
                  onChange={(value) => updateExperience(structure, setStructure, index, { location: value })} />
                <ListEditor
                  label={t('resumes.bullets')}
                  values={job.bullets}
                  onChange={(bullets) => updateExperience(structure, setStructure, index, { bullets })}
                />
                <button type="button" className="mt-2 text-sm text-red-700 underline"
                  onClick={() => setStructure({
                    ...structure,
                    experience: structure.experience.filter((_, i) => i !== index),
                  })}>
                  {t('resumes.remove')}
                </button>
              </div>
            ))}
            <button type="button" className="text-sm underline" onClick={() => setStructure({
              ...structure,
              experience: [...structure.experience, emptyExperience()],
            })}>{t('resumes.addItem')}</button>
          </Section>

          <Section title={t('resumes.projects')}>
            {structure.projects.map((project, index) => (
              <div key={index} className="mb-4 rounded-lg border border-slate-200 p-4">
                <Field label={t('resumes.projectName')} value={project.name}
                  onChange={(value) => updateProject(structure, setStructure, index, { name: value })} />
                <Field label={t('resumes.organization')} value={project.organizationOrType}
                  onChange={(value) => updateProject(structure, setStructure, index, { organizationOrType: value })} />
                <Field label={t('resumes.dates')} value={project.dates}
                  onChange={(value) => updateProject(structure, setStructure, index, { dates: value })} />
                <ListEditor
                  label={t('resumes.bullets')}
                  values={project.bullets}
                  onChange={(bullets) => updateProject(structure, setStructure, index, { bullets })}
                />
                <ListEditor
                  label={t('resumes.links')}
                  values={project.links}
                  onChange={(links) => updateProject(structure, setStructure, index, { links })}
                />
                <button type="button" className="mt-2 text-sm text-red-700 underline"
                  onClick={() => setStructure({
                    ...structure,
                    projects: structure.projects.filter((_, i) => i !== index),
                  })}>
                  {t('resumes.remove')}
                </button>
              </div>
            ))}
            <button type="button" className="text-sm underline" onClick={() => setStructure({
              ...structure,
              projects: [...structure.projects, emptyProject()],
            })}>{t('resumes.addItem')}</button>
          </Section>

          <Section title={t('resumes.education')}>
            {structure.education.map((school, index) => (
              <div key={index} className="mb-4 rounded-lg border border-slate-200 p-4">
                <Field label={t('resumes.school')} value={school.school}
                  onChange={(value) => updateEducation(structure, setStructure, index, { school: value })} />
                <Field label={t('resumes.degree')} value={school.degree}
                  onChange={(value) => updateEducation(structure, setStructure, index, { degree: value })} />
                <Field label={t('resumes.dates')} value={school.dates}
                  onChange={(value) => updateEducation(structure, setStructure, index, { dates: value })} />
                <Field label={t('resumes.details')} value={school.details}
                  onChange={(value) => updateEducation(structure, setStructure, index, { details: value })} />
                <button type="button" className="mt-2 text-sm text-red-700 underline"
                  onClick={() => setStructure({
                    ...structure,
                    education: structure.education.filter((_, i) => i !== index),
                  })}>
                  {t('resumes.remove')}
                </button>
              </div>
            ))}
            <button type="button" className="text-sm underline" onClick={() => setStructure({
              ...structure,
              education: [...structure.education, emptyEducation()],
            })}>{t('resumes.addItem')}</button>
          </Section>

          {structure.unassigned && structure.unassigned.length > 0 ? (
            <Section title={t('resumes.unassigned')}>
              <div className="space-y-2 text-sm whitespace-pre-wrap text-slate-700">
                {structure.unassigned.map((block) => (
                  <p key={block.id}>{block.text}</p>
                ))}
              </div>
            </Section>
          ) : null}

          <div className="flex flex-wrap gap-3">
            <button type="button" disabled={saving}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm disabled:opacity-60"
              onClick={() => void save()}>
              {saving ? t('common.saving') : t('resumes.saveStructure')}
            </button>
            <Link
              className={`rounded-lg px-4 py-2 text-sm text-white ${
                nextTailor ? 'bg-slate-900' : 'bg-slate-800'
              }`}
              to={`/tailor?resumeId=${resumeId}`}
            >
              {t('resumes.startTailor')}
            </Link>
          </div>
        </div>
      ) : null}
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="mb-4 text-lg font-medium text-slate-900">{title}</h2>
      {children}
    </section>
  )
}

function Field({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label className="mb-3 block text-sm">
      <span className="text-slate-600">{label}</span>
      <input
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function ListEditor({
  label,
  values,
  onChange,
}: {
  label: string
  values: string[]
  onChange: (values: string[]) => void
}) {
  const { t } = useTranslation()
  return (
    <div className="mb-3">
      <p className="text-sm text-slate-600">{label}</p>
      {values.map((value, index) => (
        <div key={index} className="mt-2 flex gap-2">
          <input
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            value={value}
            onChange={(event) => {
              const next = [...values]
              next[index] = event.target.value
              onChange(next)
            }}
          />
          <button type="button" className="text-sm text-red-700 underline"
            onClick={() => onChange(values.filter((_, i) => i !== index))}>
            {t('resumes.remove')}
          </button>
        </div>
      ))}
      <button type="button" className="mt-2 text-sm underline"
        onClick={() => onChange([...values, ''])}>
        {t('resumes.addItem')}
      </button>
    </div>
  )
}

function normalizeStructure(data: ResumeStructure): ResumeStructure {
  return {
    ...data,
    contact: {
      fullName: data.contact?.fullName ?? '',
      email: data.contact?.email ?? '',
      phone: data.contact?.phone ?? '',
      location: data.contact?.location ?? '',
      links: data.contact?.links ?? [],
    },
    summaryBullets: data.summaryBullets ?? [],
    skills: data.skills ?? [],
    experience: (data.experience ?? []).map((item) => ({
      ...item,
      bullets: item.bullets ?? [],
    })),
    education: data.education ?? [],
    projects: (data.projects ?? []).map((item) => ({
      ...item,
      bullets: item.bullets ?? [],
      links: item.links ?? [],
    })),
    unassigned: data.unassigned ?? [],
  }
}

function mergeBulletTexts(existing: ResumeBullet[], texts: string[]): ResumeBullet[] {
  return texts.map((text, index) => ({
    ...(existing[index] ?? {}),
    text,
  }))
}

function mergeSkillNames(existing: ResumeSkill[], names: string[]): ResumeSkill[] {
  return names.map((name, index) => ({
    ...(existing[index] ?? {}),
    name,
  }))
}

function emptyExperience(): ResumeExperience {
  return {
    company: '',
    title: '',
    startDate: '',
    endDate: '',
    location: '',
    bullets: [],
  }
}

function emptyProject(): ResumeProject {
  return {
    name: '',
    organizationOrType: '',
    dates: '',
    bullets: [],
    links: [],
  }
}

function emptyEducation(): ResumeEducation {
  return { school: '', degree: '', dates: '', details: '' }
}

function updateExperience(
  structure: ResumeStructure,
  setStructure: (value: ResumeStructure) => void,
  index: number,
  patch: Partial<ResumeExperience>,
) {
  const next = [...structure.experience]
  next[index] = { ...next[index], ...patch }
  setStructure({ ...structure, experience: next })
}

function updateProject(
  structure: ResumeStructure,
  setStructure: (value: ResumeStructure) => void,
  index: number,
  patch: Partial<ResumeProject>,
) {
  const next = [...structure.projects]
  next[index] = { ...next[index], ...patch }
  setStructure({ ...structure, projects: next })
}

function updateEducation(
  structure: ResumeStructure,
  setStructure: (value: ResumeStructure) => void,
  index: number,
  patch: Partial<ResumeEducation>,
) {
  const next = [...structure.education]
  next[index] = { ...next[index], ...patch }
  setStructure({ ...structure, education: next })
}
