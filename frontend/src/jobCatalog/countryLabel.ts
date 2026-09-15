/**
 * "Canada (CA)" / "加拿大（CA）" -- never a bare code.
 */
export function countryLabel(code: string, language: string): string {
  try {
    const displayNames = new Intl.DisplayNames([language], { type: 'region' })
    const name = displayNames.of(code)
    if (!name || name === code) {
      return code
    }
    return language.startsWith('zh') ? `${name}（${code}）` : `${name} (${code})`
  } catch {
    return code
  }
}
