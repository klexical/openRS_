/** Design tokens — matches the openRS_ Android app palette exactly. */

/**
 * `accent` and `accentD` resolve to CSS custom properties so that inline-style
 * consumers automatically follow the active RS paint theme. The CSS variables
 * are written to `document.documentElement` from `App.tsx` whenever
 * `useSettings.themeId` changes. Use `cyan` for places that should remain
 * fixed regardless of theme (e.g. categorical legend colours, GPS map buckets).
 */
export const colors = {
  bg: '#05070A',
  surf: '#0A0D12',
  surf2: '#0F141C',
  surf3: '#141B26',
  frost: '#E8F4FF',
  dim: '#3D5A72',
  mid: '#7A9AB8',
  brd: '#162030',
  accent: 'var(--color-accent)',
  accentD: 'var(--color-accent-d)',
  cyan: '#0091EA',
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

/**
 * Lighten (positive) or darken (negative) a `#RRGGBB` colour by the given
 * fraction in `[-1, 1]`. Used to derive `--color-accent-d` from the active
 * RS paint theme without committing every shade to the static palette.
 */
export function shadeHex(hex: string, amount: number): string {
  const h = hex.replace('#', '')
  const num = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16)
  const r = (num >> 16) & 0xff
  const g = (num >> 8) & 0xff
  const b = num & 0xff
  const t = amount < 0 ? 0 : 255
  const p = Math.abs(amount)
  const mix = (channel: number) => Math.round((t - channel) * p) + channel
  const out = (mix(r) << 16) | (mix(g) << 8) | mix(b)
  return '#' + out.toString(16).padStart(6, '0').toUpperCase()
}

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
