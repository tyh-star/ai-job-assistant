import { cx } from './cx'

export function LoadingSkeleton({
  rows = 4,
  label,
  className,
}: {
  rows?: number
  label: string
  className?: string
}) {
  return (
    <div className={cx('space-y-3', className)} aria-busy="true" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div
          key={index}
          className="h-16 animate-pulse rounded-lg border border-line bg-slate-100"
        />
      ))}
    </div>
  )
}
