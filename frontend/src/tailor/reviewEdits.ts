import type { TailorChange } from '../api/types'

export interface EditedTailorChange {
  nodeId: string
  newText: string
}

export function defaultSelectedNodeIds(changes: TailorChange[] | undefined): string[] {
  return (changes ?? [])
    .map((change) => change.nodeId)
    .filter((nodeId): nodeId is string => Boolean(nodeId))
}

export function suggestionMap(changes: TailorChange[] | undefined): Record<string, string> {
  const map: Record<string, string> = {}
  for (const change of changes ?? []) {
    if (change.nodeId) {
      map[change.nodeId] = change.newText ?? ''
    }
  }
  return map
}

export function buildEditedChanges(
  selectedNodeIds: string[],
  currentText: Record<string, string>,
  aiOriginal: Record<string, string>,
): EditedTailorChange[] {
  const selected = new Set(selectedNodeIds)
  const edited: EditedTailorChange[] = []
  for (const nodeId of selectedNodeIds) {
    if (!selected.has(nodeId)) {
      continue
    }
    const text = currentText[nodeId]
    if (text == null || text === aiOriginal[nodeId]) {
      continue
    }
    edited.push({ nodeId, newText: text })
  }
  return edited
}

export function hasUnsavedEdit(
  editingNodeId: string | null,
  draft: string,
  savedText: Record<string, string>,
): boolean {
  if (!editingNodeId) {
    return false
  }
  return draft !== (savedText[editingNodeId] ?? '')
}
