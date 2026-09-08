import { useState } from 'react'
import { GenericListField } from './GenericListField'

export type FieldKind = 'text' | 'textarea' | 'list' | 'number' | 'boolean'

export interface FieldDef {
  key: string
  label: string
  kind: FieldKind
  placeholder?: string
}

interface RepeatableSectionProps {
  entries: Record<string, unknown>[]
  onChange: (next: Record<string, unknown>[]) => void
  fields: FieldDef[]
  emptyEntry: () => Record<string, unknown>
  headingFor: (entry: Record<string, unknown>) => string
  addLabel: string
  removeLabel: string
  moveUpLabel: string
  moveDownLabel: string
  collapseLabel: string
  expandLabel: string
  emptyHint: string
}

/**
 * A generic "unlimited repeatable entries" editor -- used for education, work
 * experience, projects, volunteer, certifications, languages, leadership,
 * publications, skill categories, and custom sections. Each entry is a card with:
 * up/down move buttons (the REQUIRED keyboard/screen-reader-accessible reorder
 * mechanism) plus native HTML5 drag-and-drop as a faster alternative for a mouse user
 * -- never the only way to reorder. Collapsed by default beyond the first few entries
 * so a long resume stays scannable.
 */
export function RepeatableSection({
  entries,
  onChange,
  fields,
  emptyEntry,
  headingFor,
  addLabel,
  removeLabel,
  moveUpLabel,
  moveDownLabel,
  collapseLabel,
  expandLabel,
  emptyHint,
}: RepeatableSectionProps) {
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set())
  const [dragIndex, setDragIndex] = useState<number | null>(null)

  function updateEntry(index: number, patch: Record<string, unknown>) {
    const next = entries.slice()
    next[index] = { ...next[index], ...patch }
    onChange(next)
  }

  function removeEntry(index: number) {
    onChange(entries.filter((_, i) => i !== index))
  }

  function move(index: number, delta: number) {
    const target = index + delta
    if (target < 0 || target >= entries.length) {
      return
    }
    const next = entries.slice()
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    onChange(next)
  }

  function reorderByDrag(from: number, to: number) {
    if (from === to) {
      return
    }
    const next = entries.slice()
    const [item] = next.splice(from, 1)
    next.splice(to, 0, item)
    onChange(next)
  }

  function toggleCollapse(index: number) {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(index)) {
        next.delete(index)
      } else {
        next.add(index)
      }
      return next
    })
  }

  return (
    <div className="space-y-3">
      {entries.length === 0 ? <p className="text-sm text-slate-500">{emptyHint}</p> : null}
      {entries.map((entry, index) => {
        const isCollapsed = collapsed.has(index)
        return (
          <div
            key={index}
            className="rounded-lg border border-slate-200 bg-white p-3"
            draggable
            onDragStart={() => setDragIndex(index)}
            onDragOver={(event) => event.preventDefault()}
            onDrop={(event) => {
              event.preventDefault()
              if (dragIndex !== null) {
                reorderByDrag(dragIndex, index)
              }
              setDragIndex(null)
            }}
          >
            <div className="flex items-center justify-between gap-2">
              <button
                type="button"
                className="flex-1 text-left text-sm font-medium text-slate-800"
                onClick={() => toggleCollapse(index)}
              >
                {headingFor(entry) || `#${index + 1}`}
              </button>
              <div className="flex shrink-0 items-center gap-1">
                <button
                  type="button"
                  aria-label={moveUpLabel}
                  className="rounded px-2 py-1 text-xs text-slate-600 disabled:opacity-30"
                  disabled={index === 0}
                  onClick={() => move(index, -1)}
                >
                  ↑
                </button>
                <button
                  type="button"
                  aria-label={moveDownLabel}
                  className="rounded px-2 py-1 text-xs text-slate-600 disabled:opacity-30"
                  disabled={index === entries.length - 1}
                  onClick={() => move(index, 1)}
                >
                  ↓
                </button>
                <button
                  type="button"
                  className="rounded px-2 py-1 text-xs text-slate-600"
                  onClick={() => toggleCollapse(index)}
                >
                  {isCollapsed ? expandLabel : collapseLabel}
                </button>
                <button
                  type="button"
                  className="rounded px-2 py-1 text-xs text-red-700"
                  onClick={() => removeEntry(index)}
                >
                  {removeLabel}
                </button>
              </div>
            </div>
            {isCollapsed ? null : (
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                {fields.map((field) => {
                  const value = entry[field.key]
                  if (field.kind === 'list') {
                    return (
                      <div key={field.key} className="sm:col-span-2">
                        <GenericListField
                          label={field.label}
                          value={Array.isArray(value) ? (value as string[]) : []}
                          onChange={(next) => updateEntry(index, { [field.key]: next })}
                          placeholder={field.placeholder}
                        />
                      </div>
                    )
                  }
                  if (field.kind === 'textarea') {
                    return (
                      <label key={field.key} className="block text-sm sm:col-span-2">
                        <span className="mb-1 block text-slate-600">{field.label}</span>
                        <textarea
                          className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                          rows={3}
                          placeholder={field.placeholder}
                          value={typeof value === 'string' ? value : ''}
                          onChange={(event) => updateEntry(index, { [field.key]: event.target.value })}
                        />
                      </label>
                    )
                  }
                  if (field.kind === 'boolean') {
                    return (
                      <label key={field.key} className="flex items-center gap-2 text-sm text-slate-700">
                        <input
                          type="checkbox"
                          checked={Boolean(value)}
                          onChange={(event) => updateEntry(index, { [field.key]: event.target.checked })}
                        />
                        {field.label}
                      </label>
                    )
                  }
                  if (field.kind === 'number') {
                    return (
                      <label key={field.key} className="block text-sm">
                        <span className="mb-1 block text-slate-600">{field.label}</span>
                        <input
                          type="number"
                          min={0}
                          className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                          placeholder={field.placeholder}
                          value={typeof value === 'number' ? value : ''}
                          onChange={(event) => {
                            const raw = event.target.value
                            updateEntry(index, { [field.key]: raw === '' ? null : Number(raw) })
                          }}
                        />
                      </label>
                    )
                  }
                  return (
                    <label key={field.key} className="block text-sm">
                      <span className="mb-1 block text-slate-600">{field.label}</span>
                      <input
                        type="text"
                        className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
                        placeholder={field.placeholder}
                        value={typeof value === 'string' ? value : ''}
                        onChange={(event) => updateEntry(index, { [field.key]: event.target.value })}
                      />
                    </label>
                  )
                })}
              </div>
            )}
          </div>
        )
      })}
      <button
        type="button"
        className="rounded-lg border border-dashed border-slate-300 px-4 py-2 text-sm text-slate-700 hover:border-slate-400"
        onClick={() => onChange([...entries, emptyEntry()])}
      >
        {addLabel}
      </button>
    </div>
  )
}
