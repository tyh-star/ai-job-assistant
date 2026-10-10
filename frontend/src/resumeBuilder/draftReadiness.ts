import type { NormalizedResume, ResumeBuilderDraftContent } from '../api/types'
import { professionalLinkHeaderLines } from './professionalLinks'

export interface DraftReadinessLabels {
  name: string
  contact: string
  target: string
}

export interface DraftReadiness {
  hasName: boolean
  hasContact: boolean
  hasTarget: boolean
  readyToGenerate: boolean
  missingKeyFacts: string[]
}

function trimmed(value: string | null | undefined): string {
  return value?.trim() ?? ''
}

/** Draft-list `targetRole` and the form's `jobTarget.targetTitle` are the same fact. */
export function unifiedTargetTitle(content: ResumeBuilderDraftContent, targetRole: string): string {
  return trimmed(targetRole) || trimmed(content.jobTarget.targetTitle)
}

export function unifyDraftTarget(
  content: ResumeBuilderDraftContent,
  targetRole: string,
): { content: ResumeBuilderDraftContent; targetRole: string } {
  const unified = unifiedTargetTitle(content, targetRole)
  const nextContent =
    content.jobTarget.targetTitle === unified
      ? content
      : { ...content, jobTarget: { ...content.jobTarget, targetTitle: unified } }
  return { content: nextContent, targetRole: unified }
}

export function withSyncedTargetRole(
  content: ResumeBuilderDraftContent,
  targetRole: string,
): ResumeBuilderDraftContent {
  return unifyDraftTarget(content, targetRole).content
}

export function computeDraftReadiness(
  content: ResumeBuilderDraftContent,
  targetRole: string,
  labels: DraftReadinessLabels,
): DraftReadiness {
  const hasName = Boolean(trimmed(content.basicInfo.fullName))
  const hasContact = Boolean(trimmed(content.basicInfo.email) || trimmed(content.basicInfo.phone))
  const hasTarget = Boolean(unifiedTargetTitle(content, targetRole))
  const missingKeyFacts: string[] = []
  if (!hasName) {
    missingKeyFacts.push(labels.name)
  }
  if (!hasContact) {
    missingKeyFacts.push(labels.contact)
  }
  if (!hasTarget) {
    missingKeyFacts.push(labels.target)
  }
  return {
    hasName,
    hasContact,
    hasTarget,
    readyToGenerate: hasName && hasContact && hasTarget,
    missingKeyFacts,
  }
}

export function emptyNormalizedResume(): NormalizedResume {
  return {
    header: { fullName: '', location: '', phone: '', email: '', links: [], targetTitle: '' },
    summary: null,
    sections: [],
    omittedSectionIds: [],
    warnings: [],
    missingKeyFacts: [],
    readyToGenerate: false,
  }
}

function liveLocation(content: ResumeBuilderDraftContent): string {
  return [content.basicInfo.city, content.basicInfo.country].map(trimmed).filter(Boolean).join(', ')
}

function liveLinks(content: ResumeBuilderDraftContent): string[] {
  return professionalLinkHeaderLines(content.basicInfo)
}

/**
 * Overlay the current form onto the last server-normalized preview. Header, missing
 * facts, and generate-readiness always follow the form; section bodies stay whatever
 * the backend last normalized so we never invent section content locally.
 */
export function overlayLivePreview(
  serverPreview: NormalizedResume | null,
  content: ResumeBuilderDraftContent,
  targetRole: string,
  labels: DraftReadinessLabels,
): NormalizedResume {
  const readiness = computeDraftReadiness(content, targetRole, labels)
  const base = serverPreview ?? emptyNormalizedResume()
  const summary = trimmed(content.jobTarget.careerSummary)
  return {
    ...base,
    header: {
      fullName: trimmed(content.basicInfo.fullName),
      location: liveLocation(content),
      phone: trimmed(content.basicInfo.phone),
      email: trimmed(content.basicInfo.email),
      links: liveLinks(content),
      targetTitle: unifiedTargetTitle(content, targetRole),
    },
    summary: summary || null,
    missingKeyFacts: readiness.missingKeyFacts,
    readyToGenerate: readiness.readyToGenerate,
  }
}
