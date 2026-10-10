import type { ReactNode } from 'react'

export function EmptyState({
  title,
  hint,
  action,
}: {
  title: string
  hint?: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed border-line bg-card px-6 py-12 text-center">
      <p className="text-base font-medium text-ink">{title}</p>
      {hint ? <p className="mt-2 text-sm text-muted">{hint}</p> : null}
      {action ? <div className="mt-4 flex flex-wrap justify-center gap-2">{action}</div> : null}
    </div>
  )
}
