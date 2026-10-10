const EN_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function parse(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null
  }
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

/** "9月8日" / "Sep 8" -- formatted by hand so Hermes' Intl data can't change it. */
export function formatMonthDay(iso: string | null | undefined, language: string): string {
  const date = parse(iso)
  if (!date) {
    return ''
  }
  const month = date.getMonth()
  const day = date.getDate()
  return language.startsWith('zh') ? `${month + 1}月${day}日` : `${EN_MONTHS[month]} ${day}`
}

/** "2026年9月8日" / "Sep 8, 2026" for detail screens. */
export function formatFullDate(iso: string | null | undefined, language: string): string {
  const date = parse(iso)
  if (!date) {
    return ''
  }
  const year = date.getFullYear()
  return language.startsWith('zh')
    ? `${year}年${formatMonthDay(iso, language)}`
    : `${formatMonthDay(iso, language)}, ${year}`
}

/** "09/10" for the timeline chip. */
export function formatShortDate(iso: string | null | undefined): string {
  const date = parse(iso)
  if (!date) {
    return ''
  }
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())}`
}
