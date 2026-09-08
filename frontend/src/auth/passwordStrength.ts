export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 72

export type PasswordRuleId =
  | 'length'
  | 'upper'
  | 'lower'
  | 'digit'
  | 'special'
  | 'noSpaceOrControl'

export type PasswordRuleState = Record<PasswordRuleId, boolean> & { strong: boolean }

function isIsoControl(code: number): boolean {
  return (code >= 0 && code <= 0x1f) || (code >= 0x7f && code <= 0x9f)
}

function isUpper(ch: string): boolean {
  return ch.toUpperCase() === ch && ch.toLowerCase() !== ch
}

function isLower(ch: string): boolean {
  return ch.toLowerCase() === ch && ch.toUpperCase() !== ch
}

function isDigit(ch: string): boolean {
  return ch >= '0' && ch <= '9'
}

/**
 * Mirrors {@code aijob.security.PasswordStrength}: raw input, never trimmed. Backend
 * remains the security gate.
 */
export function evaluatePassword(password: string): PasswordRuleState {
  const lengthOk = password.length >= PASSWORD_MIN_LENGTH && password.length <= PASSWORD_MAX_LENGTH
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
    if (isUpper(ch)) {
      upper = true
    } else if (isLower(ch)) {
      lower = true
    } else if (isDigit(ch)) {
      digit = true
    } else {
      special = true
    }
  }
  const strong = lengthOk && upper && lower && digit && special && noSpaceOrControl
  return {
    length: lengthOk,
    upper,
    lower,
    digit,
    special,
    noSpaceOrControl,
    strong,
  }
}
