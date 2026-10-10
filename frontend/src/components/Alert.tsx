import { useTranslation } from 'react-i18next'
import { cx } from './ui/cx'

type Tone = 'error' | 'success' | 'warning' | 'info'

const TONE: Record<Tone, string> = {
  error: 'border-danger/30 bg-danger-soft text-danger',
  success: 'border-success/30 bg-success-soft text-success',
  warning: 'border-warning/40 bg-warning-soft text-warning',
  info: 'border-line bg-accent-soft text-brand',
}

export function Alert({
  message,
  onRetry,
  tone = 'error',
}: {
  message: string
  onRetry?: () => void
  tone?: Tone
}) {
  const { t } = useTranslation()
  if (!message) {
    return null
  }
  return (
    <div role="alert" className={cx('rounded-md border px-4 py-3 text-sm', TONE[tone])}>
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="mt-2 font-medium underline" onClick={onRetry}>
          {t('common.retry')}
        </button>
      ) : null}
    </div>
  )
}
