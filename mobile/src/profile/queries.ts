import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { feedbackApi, usersApi } from '../api/endpoints'
import { isNotFound } from '../api/errors'
import type { FeedbackRequest, FeedbackResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'

type UserKey = number | null

export const profileKeys = {
  me: (userId: UserKey) => ['me', userId] as const,
  feedback: (userId: UserKey) => ['feedback', userId] as const,
}

function useUserKey(): UserKey {
  return useAuth().user?.userId ?? null
}

/** The account as the server has it now, not as it was stored at login. */
export function useMe() {
  const userId = useUserKey()
  return useQuery({ queryKey: profileKeys.me(userId), queryFn: () => usersApi.me() })
}

/** null means "no feedback yet" (the server answers 404); any other failure stays an error. */
export function useMyFeedback() {
  const userId = useUserKey()
  return useQuery({
    queryKey: profileKeys.feedback(userId),
    queryFn: async (): Promise<FeedbackResponse | null> => {
      try {
        return await feedbackApi.me()
      } catch (error) {
        if (isNotFound(error)) {
          return null
        }
        throw error
      }
    },
  })
}

export function useSaveFeedback() {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: FeedbackRequest) => feedbackApi.save(body),
    onSuccess: (saved) => queryClient.setQueryData(profileKeys.feedback(userId), saved),
  })
}

export function useDeleteFeedback() {
  const userId = useUserKey()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => feedbackApi.remove(),
    onSuccess: () => queryClient.setQueryData(profileKeys.feedback(userId), null),
  })
}
