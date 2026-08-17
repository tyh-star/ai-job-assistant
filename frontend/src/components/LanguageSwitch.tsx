import { useTranslation } from 'react-i18next'

export function LanguageSwitch() {
  const { i18n, t } = useTranslation()
  const current = i18n.resolvedLanguage ?? i18n.language

  return (
    <div className="inline-flex rounded-full border border-slate-200 bg-white p-0.5 text-sm">
      <button
        type="button"
        className={`rounded-full px-3 py-1 ${
          current.startsWith('zh') ? 'bg-slate-900 text-white' : 'text-slate-600'
        }`}
        onClick={() => void i18n.changeLanguage('zh-CN')}
      >
        {t('nav.languageZh')}
      </button>
      <button
        type="button"
        className={`rounded-full px-3 py-1 ${
          current.startsWith('en') ? 'bg-slate-900 text-white' : 'text-slate-600'
        }`}
        onClick={() => void i18n.changeLanguage('en')}
      >
        {t('nav.languageEn')}
      </button>
    </div>
  )
}
