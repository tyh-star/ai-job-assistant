import { parsePendingGeneration, pendingGenerationKey, type PendingGeneration } from './pendingRecord'

export type { PendingGeneration } from './pendingRecord'

/** Web is a development preview only; see session.web.ts. */
export async function loadPendingGeneration(userId: number, draftId: number): Promise<PendingGeneration | null> {
  return parsePendingGeneration(globalThis.localStorage?.getItem(pendingGenerationKey(userId, draftId)) ?? null)
}

export async function savePendingGeneration(userId: number, draftId: number, record: PendingGeneration): Promise<void> {
  globalThis.localStorage?.setItem(pendingGenerationKey(userId, draftId), JSON.stringify(record))
}

export async function clearPendingGeneration(userId: number, draftId: number): Promise<void> {
  globalThis.localStorage?.removeItem(pendingGenerationKey(userId, draftId))
}
