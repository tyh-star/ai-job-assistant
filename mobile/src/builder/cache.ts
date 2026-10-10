import type { QueryClient } from '@tanstack/react-query'
import type { ResumeBuilderDraftSummary, ResumeResponse } from '../api/types'
import { resumeKeys } from '../resumes/queries'
import type { BuilderDraftResponse } from './types'

type UserKey = number | null

function summaryOf(draft: BuilderDraftResponse): ResumeBuilderDraftSummary {
  const { id, title, templateType, targetRole, locale, version, createdAt, updatedAt } = draft
  return { id, title, templateType, targetRole, locale, version, createdAt, updatedAt }
}

/** A created or saved draft: seed its detail, then refetch the list the resumes tab and dashboard share. */
export async function afterDraftWritten(queryClient: QueryClient, userId: UserKey, draft: BuilderDraftResponse) {
  queryClient.setQueryData(resumeKeys.draft(userId, draft.id), draft)
  queryClient.setQueryData<ResumeBuilderDraftSummary[]>(resumeKeys.drafts(userId), (current) =>
    current ? [summaryOf(draft), ...current.filter((row) => row.id !== draft.id)] : current,
  )
  await queryClient.invalidateQueries({ queryKey: resumeKeys.drafts(userId), exact: true })
}

export async function afterDraftDeleted(queryClient: QueryClient, userId: UserKey, draftId: number) {
  queryClient.setQueryData<ResumeBuilderDraftSummary[]>(resumeKeys.drafts(userId), (current) =>
    current?.filter((row) => row.id !== draftId),
  )
  queryClient.removeQueries({ queryKey: resumeKeys.draft(userId, draftId) })
  await queryClient.invalidateQueries({ queryKey: resumeKeys.drafts(userId), exact: true })
}

/** Generation adds one row to GET /resumes and changes nothing about the draft itself. */
export async function afterResumeGenerated(queryClient: QueryClient, userId: UserKey, resume: ResumeResponse | null) {
  if (resume) {
    queryClient.setQueryData<ResumeResponse[]>(resumeKeys.files(userId), (current) =>
      current ? [resume, ...current.filter((row) => row.id !== resume.id)] : current,
    )
  }
  await queryClient.invalidateQueries({ queryKey: resumeKeys.files(userId), exact: true })
}
