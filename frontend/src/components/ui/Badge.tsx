import type { ReactNode } from 'react'
import { cx } from './cx'

type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger'

const TONE: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-800',
  accent: 'bg-accent-soft text-brand',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  danger: 'bg-danger-soft text-danger',
}

export function Badge({
  tone = 'neutral',
  icon,
  children,
  className,
}: {
  tone?: Tone
  icon?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-sm font-medium',
        TONE[tone],
        className,
      )}
    >
      {icon}
      {children}
    </span>
  )
}
