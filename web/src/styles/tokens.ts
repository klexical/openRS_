/** Design tokens — matches the openRS_ Android app palette exactly. */

/**
 * `accent` and `accentD` resolve to CSS custom properties so inline-style and SVG consumers follow the
 * active RS paint theme; `App.tsx` writes them to `document.documentElement` whenever
 * `useSettings.themeId` changes. Use `cyan` where the colour must stay fixed whatever the paint
 * (categorical legends, GPS map buckets, threshold bands) — a Race Red accent would otherwise read as a
 * warning next to `orange` / `red`.
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
 * Lighten (positive) or darken (negative) a `#RRGGBB` colour by the given fraction in `[-1, 1]`.
 * Used to derive `--color-accent-d` from the active RS paint.
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

/** WCAG 2 contrast ratio between two `#RRGGBB` colours. */
export function contrastRatio(a: string, b: string): number {
  const lum = (hex: string) => {
    const n = parseInt(hex.replace('#', ''), 16)
    const [r, g, bl] = [16, 8, 0].map((s) => {
      const c = ((n >> s) & 0xff) / 255
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
    })
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/**
 * The paint darkened just enough for text on white (≥ 4.5:1) — print is Sapphire's only light surface, and
 * Frozen White / Shadow Black would vanish on paper. Written to `--color-accent-print` (see index.css).
 */
export function printAccent(hex: string): string {
  for (let amount = 0; amount < 1; amount += 0.05) {
    const shaded = shadeHex(hex, -amount)
    if (contrastRatio(shaded, '#FFFFFF') >= 4.5) return shaded
  }
  return '#000000'
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
