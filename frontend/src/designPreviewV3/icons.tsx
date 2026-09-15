/** Line icons for the V3 prototype. 20x20 grid, 1.6 stroke, currentColor. */

import type { ReactNode } from 'react'

type Props = { size?: number }

function Svg({ size = 16, children }: Props & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export const IconGrid = (p: Props) => (
  <Svg {...p}>
    <rect x="2.75" y="2.75" width="6" height="6" rx="1.2" />
    <rect x="11.25" y="2.75" width="6" height="6" rx="1.2" />
    <rect x="2.75" y="11.25" width="6" height="6" rx="1.2" />
    <rect x="11.25" y="11.25" width="6" height="6" rx="1.2" />
  </Svg>
)

export const IconSearch = (p: Props) => (
  <Svg {...p}>
    <circle cx="9" cy="9" r="5.25" />
    <path d="M13 13.2 16.5 16.7" />
  </Svg>
)

export const IconBriefcase = (p: Props) => (
  <Svg {...p}>
    <rect x="2.75" y="6" width="14.5" height="10.75" rx="1.5" />
    <path d="M7 6V4.6A1.35 1.35 0 0 1 8.35 3.25h3.3A1.35 1.35 0 0 1 13 4.6V6" />
    <path d="M2.75 10.25h14.5" />
  </Svg>
)

export const IconDoc = (p: Props) => (
  <Svg {...p}>
    <path d="M5.5 2.75h6l3.25 3.25v11a1 1 0 0 1-1 1h-8.25a1 1 0 0 1-1-1v-13.25a1 1 0 0 1 1-1Z" />
    <path d="M11.25 2.75V6.25h3.5" />
    <path d="M7.5 10.5h5M7.5 13.25h3.25" />
  </Svg>
)

export const IconSparkle = (p: Props) => (
  <Svg {...p}>
    <path d="M7.25 2.75 8.4 6.1l3.35 1.15-3.35 1.15L7.25 11.75 6.1 8.4 2.75 7.25 6.1 6.1Z" />
    <path d="M14 11.5l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7Z" />
  </Svg>
)

export const IconPlus = (p: Props) => (
  <Svg {...p}>
    <path d="M10 4.5v11M4.5 10h11" />
  </Svg>
)

export const IconArrow = (p: Props) => (
  <Svg {...p}>
    <path d="M4.5 10h11M11 5.5 15.5 10 11 14.5" />
  </Svg>
)

export const IconCheck = (p: Props) => (
  <Svg {...p}>
    <path d="M4.5 10.5 8 14l7.5-8" />
  </Svg>
)

export const IconMenu = (p: Props) => (
  <Svg {...p}>
    <path d="M3.25 5.5h13.5M3.25 10h13.5M3.25 14.5h13.5" />
  </Svg>
)

export const IconClose = (p: Props) => (
  <Svg {...p}>
    <path d="M5 5l10 10M15 5 5 15" />
  </Svg>
)

export const IconChevron = (p: Props) => (
  <Svg {...p}>
    <path d="M6.5 8 10 11.5 13.5 8" />
  </Svg>
)

export const IconUser = (p: Props) => (
  <Svg {...p}>
    <circle cx="10" cy="6.75" r="3" />
    <path d="M3.75 16.75c0-3.45 2.8-5.75 6.25-5.75s6.25 2.3 6.25 5.75" />
  </Svg>
)

export const IconLogout = (p: Props) => (
  <Svg {...p}>
    <path d="M12 6.25V4.5a1.25 1.25 0 0 0-1.25-1.25h-6A1.25 1.25 0 0 0 3.5 4.5v11a1.25 1.25 0 0 0 1.25 1.25h6A1.25 1.25 0 0 0 12 15.5v-1.75" />
    <path d="M8 10h8.5M14 7.5 16.5 10 14 12.5" />
  </Svg>
)

export const IconAlert = (p: Props) => (
  <Svg {...p}>
    <circle cx="10" cy="10" r="7.25" />
    <path d="M10 6.25v4.5M10 13.5h.01" />
  </Svg>
)

export const IconBrand = (p: Props) => (
  <Svg {...p}>
    <rect x="2.75" y="6.5" width="14.5" height="10.25" rx="1.6" />
    <path d="M7 6.5V4.9A1.4 1.4 0 0 1 8.4 3.5h3.2A1.4 1.4 0 0 1 13 4.9v1.6" />
    <path d="M2.75 10.25h14.5" />
  </Svg>
)
