/** Mirrors aijob.security.PasswordStrength. The backend remains the real gate. */
export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 72

export type PasswordRuleId = 'length' | 'upper' | 'lower' | 'digit' | 'special' | 'noSpaceOrControl'

export const PASSWORD_RULES: PasswordRuleId[] = [
  'length',
  'upper',
  'lower',
  'digit',
  'special',
  'noSpaceOrControl',
]

export type PasswordRuleState = Record<PasswordRuleId, boolean> & { strong: boolean }

function isIsoControl(code: number): boolean {
  return (code >= 0 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f)
}

export function evaluatePassword(password: string): PasswordRuleState {
  const length = password.length >= PASSWORD_MIN_LENGTH && password.length <= PASSWORD_MAX_LENGTH
  let upper = false
  let lower = false
  let digit = false
  let special = false
  let noSpaceOrControl = true
  for (const ch of password) {
    const code = ch.codePointAt(0) ?? 0
    if (isIsoControl(code) || /\s/u.test(ch)) {
      noSpaceOrControl = false
      continue
    }
    if (ch.toUpperCase() === ch && ch.toLowerCase() !== ch) {
      upper = true
    } else if (ch.toLowerCase() === ch && ch.toUpperCase() !== ch) {
      lower = true
    } else if (ch >= '0' && ch <= '9') {
      digit = true
    } else {
      special = true
    }
  }
  return {
    length,
    upper,
    lower,
    digit,
    special,
    noSpaceOrControl,
    strong: length && upper && lower && digit && special && noSpaceOrControl,
  }
}
