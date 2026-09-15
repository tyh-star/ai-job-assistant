import type { ReactNode } from 'react'

export function SectionHeader({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="page-title">{title}</h1>
        {description ? <p className="mt-1 text-sm text-muted">{description}</p> : null}
      </div>
      {action ? <div className="flex flex-wrap gap-2">{action}</div> : null}
    </div>
  )
}
