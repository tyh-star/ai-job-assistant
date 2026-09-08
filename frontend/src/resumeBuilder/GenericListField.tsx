import { useState } from 'react'

interface GenericListFieldProps {
  label: string
  value: string[]
  onChange: (next: string[]) => void
  placeholder?: string
  rows?: number
}

/**
 * A "list of short strings" editor: one line per entry in a plain textarea. Chosen
 * over a custom add/remove-per-row widget because a textarea is natively keyboard- and
 * screen-reader-friendly with zero extra markup, and pasting several lines at once
 * (e.g. copying a course list from a transcript) just works.
 *
 * Local text state is authoritative once mounted -- it is never resynced from
 * `value` after the first render, so the parent re-rendering with the just-parsed
 * array (e.g. after trimming) never fights the user's cursor mid-edit. Render this
 * with a `key` that changes per logical entry (e.g. the entry's id) so switching to a
 * DIFFERENT entry mounts a fresh field instead of reusing stale local text.
 */
export function GenericListField({ label, value, onChange, placeholder, rows = 3 }: GenericListFieldProps) {
  const [text, setText] = useState(() => value.join('\n'))

  return (
    <label className="block text-sm">
      <span className="mb-1 block text-slate-600">{label}</span>
      <textarea
        className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
        rows={rows}
        placeholder={placeholder}
        value={text}
        onChange={(event) => {
          const next = event.target.value
          setText(next)
          onChange(
            next
              .split('\n')
              .map((line) => line.trim())
              .filter((line) => line.length > 0),
          )
        }}
      />
    </label>
  )
}
