/** Design tokens — matches the openRS_ Android app palette exactly. */

export const colors = {
  bg: '#05070A',
  surf: '#0A0D12',
  surf2: '#0F141C',
  surf3: '#141B26',
  frost: '#E8F4FF',
  dim: '#3D5A72',
  mid: '#7A9AB8',
  brd: '#162030',
  accent: '#0091EA',
  accentD: '#006DB3',
  orange: '#FF4D00',
  ok: '#00FF88',
  warn: '#FFCC00',
  red: '#FF3333',
} as const

/** RS paint colour themes (themeId → accent colour). */
export const rsThemes = {
  cyan: '#0091EA',    // Nitrous Blue
  red: '#FF1744',     // Race Red
  orange: '#FF6D00',  // Deep Orange
  grey: '#78909C',    // Stealth Grey
  black: '#90A4AE',   // Shadow Black
  white: '#ECEFF1',   // Frozen White
} as const

/** Chart series colours — distinct, accessible against dark bg. */
export const chartColors = [
  '#0091EA', // accent cyan
  '#00FF88', // ok green
  '#FF4D00', // orange
  '#FFCC00', // warn yellow
  '#E040FB', // purple
  '#FF1744', // red
  '#00BCD4', // teal
  '#7C4DFF', // deep purple
] as const

/** Drive mode colours. */
export const modeColors = {
  normal: '#0091EA',
  sport: '#FF6D00',
  track: '#FF1744',
  drift: '#E040FB',
} as const
