import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { cx } from './cx'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'md' | 'sm'

const VARIANT: Record<Variant, string> = {
  primary:
    'bg-accent text-white hover:bg-brand active:bg-brand-deep disabled:bg-slate-300 disabled:text-slate-600',
  secondary:
    'border border-line bg-card text-ink hover:bg-slate-50 active:bg-slate-100 disabled:text-muted',
  ghost: 'text-ink hover:bg-slate-100 active:bg-slate-200 disabled:text-muted',
  danger:
    'border border-danger/30 bg-danger-soft text-danger hover:bg-red-100 disabled:opacity-60',
}

const SIZE: Record<Size, string> = {
  md: 'min-h-11 px-4 text-sm',
  sm: 'min-h-9 px-3 text-sm',
}

export function Button({
  variant = 'primary',
  size = 'md',
  className,
  children,
  type = 'button',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: Size
  children: ReactNode
}) {
  return (
    <button
      type={type}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-md font-medium disabled:cursor-not-allowed disabled:opacity-60',
        VARIANT[variant],
        SIZE[size],
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  )
}
