import { useTranslation } from 'react-i18next'

export function LanguageSwitch({ size = 'default' }: { size?: 'default' | 'auth' }) {
  const { i18n, t } = useTranslation()
  const current = i18n.resolvedLanguage ?? i18n.language
  const large = size === 'auth'

  return (
    <div
      data-testid="language-switch"
      data-size={size}
      className={`inline-flex shrink-0 rounded-full border border-line bg-card ${
        large ? 'p-1 text-base' : 'p-0.5 text-[length:var(--text-nav)]'
      }`}
    >
      <button
        type="button"
        className={`rounded-full ${large ? 'min-h-11 px-4 py-2' : 'min-h-11 px-3'} ${
          current.startsWith('zh') ? 'bg-brand text-white' : 'text-muted'
        }`}
        onClick={() => void i18n.changeLanguage('zh-CN')}
      >
        {t('nav.languageZh')}
      </button>
      <button
        type="button"
        className={`rounded-full ${large ? 'min-h-11 px-4 py-2' : 'min-h-11 px-3'} ${
          current.startsWith('en') ? 'bg-brand text-white' : 'text-muted'
        }`}
        onClick={() => void i18n.changeLanguage('en')}
      >
        {t('nav.languageEn')}
      </button>
    </div>
  )
}
