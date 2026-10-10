export type PendingGeneration = {
  idempotencyKey: string
  /** The saved draft version the server generated from; a different version means edited since. */
  draftVersion: number
}

/** SecureStore keys may only contain letters, digits, ".", "-" and "_". */
export function pendingGenerationKey(userId: number, draftId: number): string {
  return `aijob.generate.${userId}.${draftId}`
}

export function parsePendingGeneration(raw: string | null): PendingGeneration | null {
  if (!raw) {
    return null
  }
  try {
    const value = JSON.parse(raw) as Partial<PendingGeneration>
    return typeof value.idempotencyKey === 'string' && value.idempotencyKey.length > 0 && typeof value.draftVersion === 'number'
      ? { idempotencyKey: value.idempotencyKey, draftVersion: value.draftVersion }
      : null
  } catch {
    return null
  }
}
