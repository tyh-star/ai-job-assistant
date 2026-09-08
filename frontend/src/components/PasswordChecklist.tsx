import { useTranslation } from 'react-i18next'
import { evaluatePassword, type PasswordRuleId } from '../auth/passwordStrength'

const RULES: PasswordRuleId[] = ['length', 'upper', 'lower', 'digit', 'special', 'noSpaceOrControl']

export function PasswordChecklist({ password }: { password: string }) {
  const { t } = useTranslation()
  const state = evaluatePassword(password)
  return (
    <ul className="mt-2 space-y-1 text-xs" aria-label={t('auth.passwordRulesLabel')}>
      {RULES.map((rule) => {
        const ok = state[rule]
        return (
          <li
            key={rule}
            className={ok ? 'text-emerald-700' : 'text-slate-500'}
            data-met={ok ? 'true' : 'false'}
          >
            <span aria-hidden="true">{ok ? '✓' : '○'} </span>
            {t(`auth.passwordRule.${rule}`)}
          </li>
        )
      })}
    </ul>
  )
}
