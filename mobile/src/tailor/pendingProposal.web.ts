import { parsePendingProposal, pendingProposalKey, type PendingProposal } from './pendingRecord'

export type { PendingProposal } from './pendingRecord'

/** Web is a development preview only; see session.web.ts. */
export async function loadPendingProposal(userId: number, jobId: number): Promise<PendingProposal | null> {
  return parsePendingProposal(globalThis.localStorage?.getItem(pendingProposalKey(userId, jobId)) ?? null)
}

export async function savePendingProposal(userId: number, record: PendingProposal): Promise<void> {
  globalThis.localStorage?.setItem(pendingProposalKey(userId, record.jobId), JSON.stringify(record))
}

export async function clearPendingProposal(userId: number, jobId: number): Promise<void> {
  globalThis.localStorage?.removeItem(pendingProposalKey(userId, jobId))
}
