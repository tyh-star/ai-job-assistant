import * as SecureStore from 'expo-secure-store'
import { parsePendingProposal, pendingProposalKey, type PendingProposal } from './pendingRecord'

export type { PendingProposal } from './pendingRecord'

/** Kept per account and job so the preview can be reopened with GET .../tailor-proposals/{id}. */
export async function loadPendingProposal(userId: number, jobId: number): Promise<PendingProposal | null> {
  return parsePendingProposal(await SecureStore.getItemAsync(pendingProposalKey(userId, jobId)))
}

export async function savePendingProposal(userId: number, record: PendingProposal): Promise<void> {
  await SecureStore.setItemAsync(pendingProposalKey(userId, record.jobId), JSON.stringify(record))
}

export async function clearPendingProposal(userId: number, jobId: number): Promise<void> {
  await SecureStore.deleteItemAsync(pendingProposalKey(userId, jobId))
}
