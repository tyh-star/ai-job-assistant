export type JobDescriptionBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'list'; items: string[] }

export type JobDescriptionSection = {
  title: string
  blocks: JobDescriptionBlock[]
}

const KNOWN_HEADINGS = new Set([
  'COMPANY OVERVIEW',
  'ABOUT THE COMPANY',
  'ABOUT US',
  'ABOUT THE ROLE',
  'JOB RESPONSIBILITIES',
  'RESPONSIBILITIES',
  'DUTIES AND RESPONSIBILITIES',
  'DUTIES',
  'JOB REQUIREMENTS',
  'REQUIREMENTS',
  'QUALIFICATIONS',
  'JOB QUALIFICATIONS',
  'PREFERRED QUALIFICATIONS',
  'MINIMUM QUALIFICATIONS',
  'BENEFITS',
  'WHAT WE OFFER',
  'COMPENSATION AND BENEFITS',
  'HOW TO APPLY',
  '公司介绍',
  '公司概述',
  '职位职责',
  '工作职责',
  '职位要求',
  '任职资格',
  '任职要求',
  '福利',
  '福利待遇',
])

const BULLET_PATTERN = /^(?:[•·●○]|\*|-|–|—)\s+(.*)$/

function headingKey(line: string): string {
  return line.trim().replace(/[:：]\s*$/, '').replace(/\s+/g, ' ')
}

function isShortAllCapsHeading(line: string): boolean {
  const trimmed = line.trim()
  if (trimmed.length < 3 || trimmed.length > 60) {
    return false
  }
  if (BULLET_PATTERN.test(trimmed) || /[。.]/.test(trimmed)) {
    return false
  }
  const letters = trimmed.replace(/[^A-Za-z]/g, '')
  if (letters.length < 3) {
    return false
  }
  return letters === letters.toUpperCase()
}

export function isJobDescriptionHeading(line: string): boolean {
  const key = headingKey(line)
  if (!key) {
    return false
  }
  if (KNOWN_HEADINGS.has(key) || KNOWN_HEADINGS.has(key.toUpperCase())) {
    return true
  }
  return isShortAllCapsHeading(key)
}

/**
 * Splits a raw JD into titled sections, lists, and paragraphs. Does not translate,
 * summarize, or drop source text.
 */
export function parseJobDescription(text: string, fallbackTitle: string): JobDescriptionSection[] {
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  const sections: JobDescriptionSection[] = []
  let current: JobDescriptionSection | null = null
  let pendingParagraph: string[] = []
  let pendingList: string[] = []

  function ensureSection(): JobDescriptionSection {
    if (!current) {
      current = { title: fallbackTitle, blocks: [] }
      sections.push(current)
    }
    return current
  }

  function flushParagraph() {
    const joined = pendingParagraph.join(' ').trim()
    pendingParagraph = []
    if (joined) {
      ensureSection().blocks.push({ type: 'paragraph', text: joined })
    }
  }

  function flushList() {
    if (pendingList.length > 0) {
      ensureSection().blocks.push({ type: 'list', items: pendingList })
      pendingList = []
    }
  }

  function flush() {
    flushList()
    flushParagraph()
  }

  for (const raw of lines) {
    const trimmed = raw.trim()
    if (!trimmed) {
      flush()
      continue
    }
    if (isJobDescriptionHeading(trimmed)) {
      flush()
      current = { title: headingKey(trimmed), blocks: [] }
      sections.push(current)
      continue
    }
    const bullet = trimmed.match(BULLET_PATTERN)
    if (bullet) {
      flushParagraph()
      pendingList.push(bullet[1].trim())
      continue
    }
    flushList()
    pendingParagraph.push(trimmed)
  }
  flush()

  if (sections.length === 0 && text.trim()) {
    return [{ title: fallbackTitle, blocks: [{ type: 'paragraph', text: text.trim() }] }]
  }
  return sections
}
