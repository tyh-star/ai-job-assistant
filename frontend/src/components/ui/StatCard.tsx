import type { ReactNode } from 'react'

export function StatCard({
  label,
  value,
  hint,
}: {
  label: string
  value: ReactNode
  hint?: string
}) {
  return (
    <div className="min-w-0 rounded-lg border border-line bg-card px-4 py-3 shadow-card">
      <p className="truncate text-sm text-muted">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-ink">{value}</p>
      {hint ? <p className="mt-1 text-sm text-muted">{hint}</p> : null}
    </div>
  )
}
