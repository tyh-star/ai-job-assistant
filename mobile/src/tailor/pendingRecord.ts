/** An AGGRESSIVE preview waiting for the user's decision. Identifiers only -- no resume or job text. */
export type PendingProposal = {
  proposalId: number
  jobId: number
  /** The file the user picked (a PDF keeps its own id here, not the internal copy). */
  resumeId: number
  expiresAt: string | null
  /** When this device received the proposal; older records have none. */
  savedAt?: string | null
}

/** SecureStore keys may only contain letters, digits, ".", "-" and "_". */
export function pendingProposalKey(userId: number, jobId: number): string {
  return `aijob.tailor.${userId}.${jobId}`
}

export function parsePendingProposal(raw: string | null): PendingProposal | null {
  if (!raw) {
    return null
  }
  try {
    const value = JSON.parse(raw) as Partial<PendingProposal>
    const ids = [value.proposalId, value.jobId, value.resumeId]
    if (!ids.every((id) => typeof id === 'number' && Number.isSafeInteger(id) && id > 0)) {
      return null
    }
    return {
      proposalId: value.proposalId as number,
      jobId: value.jobId as number,
      resumeId: value.resumeId as number,
      expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
      savedAt: typeof value.savedAt === 'string' ? value.savedAt : null,
    }
  } catch {
    return null
  }
}
