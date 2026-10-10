/**
 * Synthetic preview-only fixture. Must never be imported by production pages.
 * Names, employers, and universities are fictional on purpose.
 */
export const SYNTHETIC_FIXTURE_NOTICE =
  'Synthetic fixture · preview only · not loaded in production'

export const PERSON = {
  name: 'Alex Morgan',
  givenName: 'Alex',
  email: 'alex.morgan@example.com',
  headline: 'Operations coordinator',
  university: 'Example University',
} as const

export const EMPLOYER = {
  name: 'Example Company',
  location: 'Toronto, ON',
} as const

export const STATS = {
  todayApplied: 2,
  last7Applied: 9,
  saved: 6,
  applied: 14,
} as const

export const RECENT_APPLICATIONS = [
  {
    id: 'a1',
    title: 'Operations Coordinator',
    company: EMPLOYER.name,
    location: 'Toronto, ON',
    appliedOn: '2026-09-08',
  },
  {
    id: 'a2',
    title: 'Program Assistant',
    company: 'Example University',
    location: 'Boston, MA',
    appliedOn: '2026-09-07',
  },
  {
    id: 'a3',
    title: 'Customer Support Lead',
    company: EMPLOYER.name,
    location: 'Remote',
    appliedOn: '2026-09-06',
  },
] as const

export const RECENT_SEARCHES = [
  { id: 's1', keywords: 'operations coordinator toronto', lastSearchedAt: '2026-09-08' },
  { id: 's2', keywords: 'program assistant hybrid', lastSearchedAt: '2026-09-07' },
  { id: 's3', keywords: 'customer support remote', lastSearchedAt: '2026-09-06' },
] as const

export const RECENT_RESUMES = [
  { id: 'r1', fileName: 'Alex_Morgan_Resume.pdf', updated: '2026-09-08' },
  { id: 'r2', fileName: 'Alex_Morgan_University.pdf', updated: '2026-09-02' },
  { id: 'r3', fileName: 'Alex_Morgan_Short.pdf', updated: '2026-08-21' },
] as const

export const JOB_RESULTS = [
  {
    id: 'j1',
    title: 'Operations Coordinator',
    company: EMPLOYER.name,
    location: 'Toronto, ON',
    workMode: 'Hybrid',
    employment: 'Full-time',
    posted: '2d ago',
    saved: true,
    applied: false,
  },
  {
    id: 'j2',
    title: 'Administrative Specialist',
    company: EMPLOYER.name,
    location: 'Ottawa, ON',
    workMode: 'On-site',
    employment: 'Full-time',
    posted: '4d ago',
    saved: false,
    applied: false,
  },
  {
    id: 'j3',
    title: 'Research Assistant',
    company: 'Example University',
    location: 'Boston, MA',
    workMode: 'On-site',
    employment: 'Part-time',
    posted: '1w ago',
    saved: false,
    applied: true,
  },
  {
    id: 'j4',
    title: 'Customer Success Associate',
    company: EMPLOYER.name,
    location: 'Remote',
    workMode: 'Remote',
    employment: 'Full-time',
    posted: '1w ago',
    saved: true,
    applied: false,
  },
] as const

export const JOB_DETAIL = {
  title: 'Operations Coordinator',
  company: EMPLOYER.name,
  location: 'Toronto, ON',
  workMode: 'Hybrid',
  employment: 'Full-time',
  posted: 'Posted 2 days ago',
  responsibilities: [
    'Coordinate weekly operations reviews and keep action items current.',
    'Maintain vendor calendars, meeting notes, and follow-up lists.',
    'Prepare concise status updates for the operations lead.',
  ],
  requirements: [
    'Experience coordinating schedules, vendors, or office programs.',
    'Clear written communication in English.',
    'Comfortable with spreadsheets and shared documents.',
    'Degree or equivalent experience from Example University or a similar program is welcome, not required.',
  ],
} as const
