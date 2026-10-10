import * as SecureStore from 'expo-secure-store'
import { parsePendingGeneration, pendingGenerationKey, type PendingGeneration } from './pendingRecord'

export type { PendingGeneration } from './pendingRecord'

/**
 * A generate request whose outcome is unknown, kept per account and draft so the same
 * idempotency key can be resent after leaving the screen or restarting the app. Only
 * request identifiers are stored -- never resume content or the token.
 */
export async function loadPendingGeneration(userId: number, draftId: number): Promise<PendingGeneration | null> {
  return parsePendingGeneration(await SecureStore.getItemAsync(pendingGenerationKey(userId, draftId)))
}

export async function savePendingGeneration(userId: number, draftId: number, record: PendingGeneration): Promise<void> {
  await SecureStore.setItemAsync(pendingGenerationKey(userId, draftId), JSON.stringify(record))
}

export async function clearPendingGeneration(userId: number, draftId: number): Promise<void> {
  await SecureStore.deleteItemAsync(pendingGenerationKey(userId, draftId))
}
