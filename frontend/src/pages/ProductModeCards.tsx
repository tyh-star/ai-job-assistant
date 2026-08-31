import { useTranslation } from 'react-i18next'
import type { KeyboardEvent } from 'react'
import type { ProductUiMode } from '../tailor/productMode'

export function ProductModeCards({
  value,
  onChange,
  disabled,
}: {
  value: ProductUiMode
  onChange: (mode: ProductUiMode) => void
  disabled?: boolean
}) {
  const { t } = useTranslation()

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled) {
      return
    }
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault()
      onChange(value === 'QUICK' ? 'ENHANCED' : 'QUICK')
    }
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault()
      onChange(value === 'ENHANCED' ? 'QUICK' : 'ENHANCED')
    }
  }

  return (
    <div
      role="radiogroup"
      aria-label={t('tailor.mode')}
      className="grid gap-3 md:grid-cols-2"
      onKeyDown={onKeyDown}
    >
      <ModeCard
        mode="QUICK"
        selected={value === 'QUICK'}
        disabled={disabled}
        title={t('tailor.quickOptimize')}
        description={t('tailor.quickOptimizeHelp')}
        onSelect={() => onChange('QUICK')}
      />
      <ModeCard
        mode="ENHANCED"
        selected={value === 'ENHANCED'}
        disabled={disabled}
        title={t('tailor.deepEnhance')}
        description={t('tailor.deepEnhanceHelp')}
        badge={t('tailor.bestResults')}
        onSelect={() => onChange('ENHANCED')}
      />
    </div>
  )
}

function ModeCard({
  mode,
  selected,
  disabled,
  title,
  description,
  badge,
  onSelect,
}: {
  mode: ProductUiMode
  selected: boolean
  disabled?: boolean
  title: string
  description: string
  badge?: string
  onSelect: () => void
}) {
  const { t } = useTranslation()
  return (
    <label
      className={`relative block cursor-pointer rounded-xl border p-4 text-left ${
        selected ? 'border-slate-900 bg-slate-50 ring-2 ring-slate-900' : 'border-slate-200 bg-white'
      } ${disabled ? 'cursor-not-allowed opacity-60' : ''}`}
    >
      <input
        type="radio"
        name="tailor-product-mode"
        className="sr-only"
        value={mode}
        checked={selected}
        aria-checked={selected}
        disabled={disabled}
        onChange={onSelect}
        aria-describedby={`mode-help-${mode}`}
      />
      <span className="flex items-center gap-2">
        <span className="text-sm font-semibold text-slate-900">{title}</span>
        {badge ? (
          <span className="rounded-full border border-slate-900 px-2 py-0.5 text-[11px] font-medium text-slate-900">
            {badge}
          </span>
        ) : null}
        {selected ? (
          <span className="ml-auto text-xs font-medium text-slate-700">{t('tailor.modeSelected')}</span>
        ) : null}
      </span>
      <span id={`mode-help-${mode}`} className="mt-2 block text-sm text-slate-600">
        {description}
      </span>
    </label>
  )
}
