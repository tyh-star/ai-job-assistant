import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { draftsApi, resumesApi } from '../api/endpoints'
import type { ResumeResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { deletesFamily, type ValidFile } from './model'

type UserKey = number | null

/**
 * GET /resumes and GET /resume-builder/drafts are cached once per user and shared by
 * the dashboard and the resumes tab. File-scoped keys nest under the list key.
 */
export const resumeKeys = {
  files: (userId: UserKey) => ['resumes', userId] as const,
  file: (userId: UserKey, resumeId: number) => ['resumes', userId, 'detail', resumeId] as const,
  quality: (userId: UserKey, resumeId: number) => ['resumes', userId, 'quality', resumeId] as const,
  drafts: (userId: UserKey) => ['resume-drafts', userId] as const,
  draft: (userId: UserKey, draftId: number) => ['resume-drafts', userId, 'detail', draftId] as const,
}

function useUserKey(): UserKey {
  return useAuth().user?.userId ?? null
}

export function useResumeFiles() {
  const userId = useUserKey()
  return useQuery({ queryKey: resumeKeys.files(userId), queryFn: () => resumesApi.list() })
}

export function useResumeFile(resumeId: number | null) {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: resumeKeys.file(userId, resumeId ?? 0),
    queryFn: () => resumesApi.get(resumeId as number),
    enabled: resumeId !== null,
    placeholderData: () =>
      queryClient.getQueryData<ResumeResponse[]>(resumeKeys.files(userId))?.find((row) => row.id === resumeId),
  })
}

export function useQualityScore(resumeId: number | null) {
  const userId = useUserKey()
  return useQuery({
    queryKey: resumeKeys.quality(userId, resumeId ?? 0),
    queryFn: () => resumesApi.qualityScore(resumeId as number),
    enabled: resumeId !== null,
  })
}

export function useDrafts() {
  const userId = useUserKey()
  return useQuery({ queryKey: resumeKeys.drafts(userId), queryFn: () => draftsApi.list() })
}

export function useDraft(draftId: number | null) {
  const userId = useUserKey()
  return useQuery({
    queryKey: resumeKeys.draft(userId, draftId ?? 0),
    queryFn: () => draftsApi.get(draftId as number),
    enabled: draftId !== null,
  })
}

/** Refetches the shared file list, which also refreshes the dashboard's resume center. */
export async function refreshAfterFileChange(queryClient: QueryClient, userId: UserKey): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: resumeKeys.files(userId) })
}

export function useUploadResume(onProgress: (fraction: number) => void) {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: ValidFile) => resumesApi.upload(file, onProgress),
    onSuccess: async (created) => {
      queryClient.setQueryData<ResumeResponse[]>(resumeKeys.files(userId), (current) =>
        current ? [created, ...current.filter((row) => row.id !== created.id)] : current,
      )
      await refreshAfterFileChange(queryClient, userId)
    },
  })
}

export function useDeleteResume() {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (resume: ResumeResponse) =>
      deletesFamily(resume) ? resumesApi.removeFamily(resume.id) : resumesApi.remove(resume.id),
    onSuccess: async (_data, resume) => {
      queryClient.setQueryData<ResumeResponse[]>(resumeKeys.files(userId), (current) =>
        current?.filter((row) => row.id !== resume.id && row.parentResumeId !== resume.id),
      )
      queryClient.removeQueries({ queryKey: resumeKeys.file(userId, resume.id) })
      queryClient.removeQueries({ queryKey: resumeKeys.quality(userId, resume.id) })
      await refreshAfterFileChange(queryClient, userId)
    },
  })
}
