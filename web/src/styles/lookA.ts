// Look A, the app's look (owner, 2026-11-14), in its three palettes (#384 ticket 08): Night, Day and Ultra. The new
// screens (the list, Settings, the bars, the drive's frame) take every value here; the old Trip, Dashboard and
// Diagnostics views keep their layout and take only the palette's surfaces and text ([oldViewTokens]). Values: the
// app's palettes (CLAUDE.md, Theme Colors) and the look A mock-up's borders. The accent is the paint ([paintAccent]),
// never a data colour: chart, map and route colours stay fixed (tokens.ts).
import type { CSSProperties } from 'react'
import { contrastRatio, nightTokens, rsThemes, shadeHex } from './tokens'
import type { ThemeId } from '../store/settings'

export type PaletteName = 'night' | 'day' | 'ultra'

export interface LookAPalette {
  bg: string; card: string; bar: string; button: string
  brd: string; brd2: string
  dim: string; mid: string; ink: string
  /** The warning colour for fills, borders and markers. */
  warn: string
  /** The warning colour for text: [warn] itself where it reads at 4.5:1, a darker shade of it where not (Day). */
  warnText: string
  /** Text on the paint accent (the primary button). */
  onAccent: string
}

/** The darkest of [backgrounds] decides: [color] shaded darker, step by step, until it reads at 4.5:1 on all of them. */
export function aaShade(color: string, backgrounds: string[]): string {
  for (let step = 0; step <= 100; step++) {
    const shaded = shadeHex(color, -step / 100)
    if (backgrounds.every((b) => contrastRatio(shaded, b) >= 4.5)) return shaded
  }
  return '#000000'
}

export const lookANight: LookAPalette = {
  bg: '#05070A', card: '#0A0D12', bar: '#0A0D12', button: '#0F141C',
  brd: '#162030', brd2: '#22324A',
  dim: '#7A9AB8', mid: '#B0D0E8', ink: '#E8F4FF',
  warn: '#FFCC00', warnText: '#FFCC00',
  onAccent: '#05070A',
}
const DAY_SURFACES = ['#EFF3F8', '#FFFFFF', '#E4EAF2']
export const lookADay: LookAPalette = {
  bg: '#EFF3F8', card: '#FFFFFF', bar: '#FFFFFF', button: '#E4EAF2',
  brd: '#B8C4D4', brd2: '#9AA9BD',
  dim: '#556374', mid: '#293748', ink: '#080D16',
  // #E09500 for fills and markers; its text shade picked by the AA rule (owner, 2026-11-15, Q4)
  warn: '#E09500', warnText: aaShade('#E09500', DAY_SURFACES),
  onAccent: '#FFFFFF',
}
export const lookAUltra: LookAPalette = {
  bg: '#000000', card: '#050505', bar: '#050505', button: '#0C0C0C',
  brd: '#1A2230', brd2: '#22324A',
  dim: lookANight.dim, mid: lookANight.mid, ink: lookANight.ink,
  warn: lookANight.warn, warnText: lookANight.warnText,
  onAccent: '#000000',
}
export const LOOK_A: Record<PaletteName, LookAPalette> = { night: lookANight, day: lookADay, ultra: lookAUltra }

/**
 * The paint's accent in [palette]. Night and Ultra take the paint as it is (each reads at 4.5:1 or better on their
 * surfaces). Day takes the app's #0064B0 for Nitrous Blue (owner, 2026-11-15, Q5), and for the other paints their own
 * shade darkened until it reads at 4.5:1 on Day's surfaces.
 */
export function paintAccent(themeId: ThemeId, palette: PaletteName): string {
  const paint = rsThemes[themeId] ?? rsThemes.cyan
  if (palette !== 'day') return paint
  return themeId === 'cyan' ? '#0064B0' : aaShade(paint, DAY_SURFACES)
}

/** The palette as CSS custom properties for `.look-a` (styles in index.css). */
export function lookAVars(p: LookAPalette): CSSProperties {
  return {
    '--a-bg': p.bg, '--a-card': p.card, '--a-bar': p.bar, '--a-button': p.button,
    '--a-brd': p.brd, '--a-brd2': p.brd2, '--a-dim': p.dim, '--a-mid': p.mid, '--a-ink': p.ink,
    '--a-warn': p.warn, '--a-warn-text': p.warnText, '--a-on-accent': p.onAccent,
  } as CSSProperties
}

/**
 * The old views' surfaces and text in [palette]: the CSS custom properties Tailwind's classes (`bg-surf2`,
 * `text-frost`, …) and `colors.bg` … `colors.brd` resolve to, and the warning and muted text of the lines outside look
 * A's frame (`text-warn-text`, `colors.warnText`, `text-dim-text`).
 * Data and meaning colours (cyan, ok, warn, orange, red, the chart and map colours) are not here: they stay fixed.
 */
export function oldViewTokens(palette: PaletteName): Record<string, string> {
  const p = LOOK_A[palette]
  const surfaces = palette === 'day' ? { surf2: '#E4EAF2', surf3: '#D4DCE8' } : palette === 'ultra' ? { surf2: '#080808', surf3: '#0C0C0C' }
    : { surf2: nightTokens.surf2, surf3: nightTokens.surf3 }
  return {
    '--color-bg': p.bg, '--color-surf': p.card, '--color-surf2': surfaces.surf2, '--color-surf3': surfaces.surf3,
    '--color-brd': p.brd, '--color-frost': p.ink,
    // the old views' own dim and mid (darker on Night than look A's), Day's from the app's palette
    '--color-dim': palette === 'day' ? p.dim : nightTokens.dim, '--color-mid': palette === 'day' ? p.mid : nightTokens.mid,
    // the palette's warning TEXT, for the lines outside look A's frame: the storage bar above every page and the backup
    // controls' fallback (QA F12). `--color-warn` itself stays fixed (fills, markers, the old views' data).
    '--color-warn-text': p.warnText,
    // the palette's muted text for the same lines (the bar's backup progress, QA F13): look A's dim, which reads at AA
    // on the bar's surface in every palette; `--color-dim` stays the old views' own
    '--color-dim-text': p.dim,
  }
}
