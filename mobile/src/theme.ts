/** Palette carried over from the confirmed /dashboard design, tuned for small screens. */
export const colors = {
  background: '#EEF6FD',
  surface: '#F7FBFF',
  card: '#FFFFFF',
  border: '#C8E2FB',
  hairline: '#DCEAF7',
  ink: '#08142B',
  ink2: '#33405A',
  muted: '#58709B',
  subtle: '#7D8BA5',
  blue: '#0D69EF',
  blueStrong: '#0754BF',
  tile: '#DCEBFD',
  chip: '#DEEDFD',
  rail: '#B7D9FC',
  green: '#08A54E',
  greenFill: '#0CAF59',
  danger: '#B42318',
  dangerSurface: '#FEF3F2',
  dangerBorder: '#FDA29B',
  inputBorder: '#C9D8EA',
  skeleton: '#E1EBF6',
} as const

export const gradients = {
  primary: ['#0877FF', '#2162EA'] as const,
  resumeCard: ['#F3FAFF', '#E2F0FE'] as const,
  quietCard: ['#F8FCFF', '#EDF6FE'] as const,
  brand: ['#108BFF', '#2765EE', '#193D9D'] as const,
}

export const radius = {
  card: 18,
  button: 12,
  tile: 12,
  chip: 8,
  pill: 999,
} as const

export const space = {
  screenX: 18,
  cardPadding: 18,
  sectionGap: 14,
} as const
