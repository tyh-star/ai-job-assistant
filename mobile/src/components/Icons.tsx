import Svg, { Circle, Path } from 'react-native-svg'
import { colors } from '../theme'

type IconProps = { size?: number; color?: string }

const FILL_OPACITY = 0.22

export function BrandGlyph({ size = 18, color = '#FFFFFF' }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M7.7 7.2V5.9c0-1 .8-1.9 1.9-1.9h4.8c1 0 1.9.8 1.9 1.9v1.3" />
      <Path d="M5.1 8.1h13.8c.9 0 1.6.7 1.6 1.6v8.2c0 1-.8 1.8-1.8 1.8H5.3c-1 0-1.8-.8-1.8-1.8V9.7c0-.9.7-1.6 1.6-1.6Z" />
      <Path d="M3.7 12.3c2.7 1.2 5.5 1.8 8.3 1.8s5.6-.6 8.3-1.8M10.4 12.8h3.2v2.3h-3.2z" />
    </Svg>
  )
}

export function BriefcaseIcon({ size = 20, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M5.6 8.2h12.8a2 2 0 0 1 2 2v7.3a2 2 0 0 1-2 2H5.6a2 2 0 0 1-2-2v-7.3a2 2 0 0 1 2-2Z"
        fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Path d="M8.3 8.1V6.6c0-1 .8-1.7 1.7-1.7h4c.9 0 1.7.8 1.7 1.7v1.5M3.8 12.6h16.4M10.3 12.5v2h3.4v-2" />
    </Svg>
  )
}

export function ClockIcon({ size = 20, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Circle cx="12" cy="12" r="8.5" fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Circle cx="12" cy="12" r="8.5" />
      <Path d="M12 7.7v4.8l3.2 1.8" />
      <Circle cx="12" cy="12" r="1.1" fill={color} stroke="none" />
    </Svg>
  )
}

export function FileIcon({ size = 20, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M6.5 3.8h7.2l4 4v12.4H6.5z" fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Path d="M6.5 3.8h7.2l4 4v12.4H6.5zM13.7 3.8v4h4M9.3 12h5.4M9.3 15.3h4.2" />
    </Svg>
  )
}

export function HomeIcon({ size = 22, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M4 10.6 12 4l8 6.6V19a1.2 1.2 0 0 1-1.2 1.2H5.2A1.2 1.2 0 0 1 4 19z"
        fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Path d="M4 10.6 12 4l8 6.6V19a1.2 1.2 0 0 1-1.2 1.2H5.2A1.2 1.2 0 0 1 4 19zM9.5 20v-5.5h5V20" />
    </Svg>
  )
}

export function UserIcon({ size = 22, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Circle cx="12" cy="8.5" r="3.8" fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Circle cx="12" cy="8.5" r="3.8" />
      <Path d="M4.8 20c.8-3.6 3.6-5.6 7.2-5.6s6.4 2 7.2 5.6" />
    </Svg>
  )
}

export function TailorIcon({ size = 22, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M15.2 4.8a2 2 0 0 1 2.8 0l1.2 1.2a2 2 0 0 1 0 2.8L9.6 18.4l-4.6 1 1-4.6z"
        fill={color} fillOpacity={FILL_OPACITY} stroke="none" />
      <Path d="M15.2 4.8a2 2 0 0 1 2.8 0l1.2 1.2a2 2 0 0 1 0 2.8L9.6 18.4l-4.6 1 1-4.6zM13.4 6.6l4 4M13.2 19.4h6.3" />
    </Svg>
  )
}

export function SearchIcon({ size = 16, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={2.2} strokeLinecap="round">
      <Circle cx="10.6" cy="10.6" r="6.2" />
      <Path d="m15.2 15.2 4.4 4.4" />
    </Svg>
  )
}

export function PinIcon({ size = 16, color = colors.muted }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11Z" />
      <Circle cx="12" cy="10" r="2.3" />
    </Svg>
  )
}

export function CheckIcon({ size = 12, color = '#FFFFFF' }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
      <Path d="m5 12.5 4.5 4.5L19 7.5" />
    </Svg>
  )
}

export function CloseIcon({ size = 14, color = colors.muted }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={2.4} strokeLinecap="round">
      <Path d="M6 6l12 12M18 6 6 18" />
    </Svg>
  )
}

export function ChevronRightIcon({ size = 16, color = colors.subtle }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <Path d="m9 6 6 6-6 6" />
    </Svg>
  )
}

export function ChevronLeftIcon({ size = 20, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
      <Path d="m15 6-6 6 6 6" />
    </Svg>
  )
}

export function ArrowRightIcon({ size = 14, color = colors.blue }: IconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M5 12h14M13 6l6 6-6 6" />
    </Svg>
  )
}

export function EyeIcon({ size = 20, color = colors.muted, off = false }: IconProps & { off?: boolean }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color}
      strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
      <Path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
      <Circle cx="12" cy="12" r="3" />
      {off ? <Path d="M4 4l16 16" /> : null}
    </Svg>
  )
}
