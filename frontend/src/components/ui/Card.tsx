import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from './cx'

export function Card({
  className,
  children,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { children: ReactNode }) {
  return (
    <div
      className={cx(
        'min-w-0 rounded-lg border border-line bg-card p-5 shadow-card',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  )
}
