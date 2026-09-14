import type { ResumeBuilderBasicInfo, ResumeBuilderProfessionalLink } from '../api/types'

export const PROFESSIONAL_LINK_TYPES = [
  'LINKEDIN',
  'PORTFOLIO',
  'GITHUB',
  'PERSONAL_WEBSITE',
  'BEHANCE',
  'OTHER',
] as const

export type ProfessionalLinkType = (typeof PROFESSIONAL_LINK_TYPES)[number]

export const MAX_PROFESSIONAL_LINKS = 12

export function canonicalLinkType(type: string | null | undefined): ProfessionalLinkType {
  if (!type) {
    return 'OTHER'
  }
  const upper = type.trim().toUpperCase().replace(/[\s-]/g, '_')
  if (upper === 'WEBSITE' || upper === 'PERSONALWEBSITE') {
    return 'PERSONAL_WEBSITE'
  }
  return (PROFESSIONAL_LINK_TYPES as readonly string[]).includes(upper)
    ? (upper as ProfessionalLinkType)
    : 'OTHER'
}

export function emptyProfessionalLink(type: string): ResumeBuilderProfessionalLink {
  return { type: canonicalLinkType(type), label: '', url: '' }
}

function addFromScalar(links: ResumeBuilderProfessionalLink[], type: ProfessionalLinkType, url: string | undefined) {
  const trimmed = url?.trim() ?? ''
  if (!trimmed) {
    return
  }
  links.push({ type, label: '', url: trimmed })
}

function firstUrlOfType(links: ResumeBuilderProfessionalLink[], type: ProfessionalLinkType): string {
  return links.find((link) => link.type === type && link.url.trim())?.url.trim() ?? ''
}

/**
 * Mirrors the Java helper: `professionalLinks == null` means a pre-upgrade draft, so
 * copy non-blank linkedin/github/website scalars. An explicit empty array means the
 * user cleared every link. Scalars are then rewritten from the list.
 */
export function migrateAndSyncProfessionalLinks(info: ResumeBuilderBasicInfo): ResumeBuilderBasicInfo {
  let links = info.professionalLinks
  if (links == null) {
    links = []
    addFromScalar(links, 'LINKEDIN', info.linkedin)
    addFromScalar(links, 'GITHUB', info.github)
    addFromScalar(links, 'PERSONAL_WEBSITE', info.website)
  }
  const cleaned = links
    .filter((link): link is ResumeBuilderProfessionalLink => Boolean(link))
    .map((link) => ({
      type: canonicalLinkType(link.type),
      label: (link.label ?? '').trim(),
      url: (link.url ?? '').trim(),
    }))
  return {
    ...info,
    professionalLinks: cleaned,
    linkedin: firstUrlOfType(cleaned, 'LINKEDIN'),
    github: firstUrlOfType(cleaned, 'GITHUB'),
    website: firstUrlOfType(cleaned, 'PERSONAL_WEBSITE'),
  }
}

/** Only filled URLs -- never a type name, never "GitHub", never N/A. */
export function professionalLinkHeaderLines(info: ResumeBuilderBasicInfo): string[] {
  const migrated = migrateAndSyncProfessionalLinks(info)
  const seen = new Set<string>()
  const lines: string[] = []
  for (const link of migrated.professionalLinks ?? []) {
    const url = link.url.trim()
    if (!url || seen.has(url)) {
      continue
    }
    seen.add(url)
    const label = link.label.trim()
    lines.push(label ? `${label} — ${url}` : url)
  }
  return lines
}
