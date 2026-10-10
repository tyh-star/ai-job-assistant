import { useTranslation } from 'react-i18next'
import { cx } from './cx'

export function BrandMark({
  size = 'md',
  withName = true,
}: {
  size?: 'sm' | 'md'
  withName?: boolean
}) {
  const { t } = useTranslation()
  const box = size === 'sm' ? 'h-8 w-8' : 'h-10 w-10'
  const icon = size === 'sm' ? 'h-4 w-4' : 'h-5 w-5'

  return (
    <span className="inline-flex items-center gap-2">
      <span
        className={cx(
          'inline-flex items-center justify-center rounded-md bg-brand text-white',
          box,
        )}
        aria-hidden="true"
      >
        <svg className={icon} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6">
          <rect x="2.5" y="7" width="15" height="10" rx="1.5" />
          <path d="M7 7V5.2A1.2 1.2 0 0 1 8.2 4h3.6A1.2 1.2 0 0 1 13 5.2V7" />
          <path d="M2.5 10.5h15" />
          <path d="M14.2 3.2 15 1.8l.8 1.4 1.5.2-1.1 1.1.3 1.5-1.5-.8-1.5.8.3-1.5-1.1-1.1Z" fill="currentColor" stroke="none" />
        </svg>
      </span>
      {withName ? (
        <span className={cx('font-semibold text-ink', size === 'sm' ? 'text-sm' : 'text-base')}>
          {t('app.name')}
        </span>
      ) : (
        <span className="sr-only">{t('app.name')}</span>
      )}
    </span>
  )
}
