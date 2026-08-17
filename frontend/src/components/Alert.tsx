import { useTranslation } from 'react-i18next'

export function Alert({
  message,
  onRetry,
}: {
  message: string
  onRetry?: () => void
}) {
  const { t } = useTranslation()
  if (!message) {
    return null
  }
  return (
    <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="mt-2 font-medium underline" onClick={onRetry}>
          {t('common.retry')}
        </button>
      ) : null}
    </div>
  )
}
