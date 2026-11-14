// Look A, the app's look (owner, 2026-11-14), for the screens slice 2 builds: the list of drives, the places bar and the
// drive's frame. Night palette only until ticket 08 adds Day and Ultra and Match device; the old Trip, Dashboard and
// Diagnostics views keep their own colours until then. Values: the app's NIGHT palette (CLAUDE.md, Theme Colors) and the
// look A mock-up's borders. The accent is the paint (`--color-accent`, App.tsx), never a data colour.
import type { CSSProperties } from 'react'

export interface LookAPalette {
  bg: string; card: string; bar: string; button: string
  brd: string; brd2: string
  dim: string; mid: string; ink: string
  warn: string
  /** Text on the paint accent (the primary button). */
  onAccent: string
}

export const lookANight: LookAPalette = {
  bg: '#05070A', card: '#0A0D12', bar: '#0A0D12', button: '#0F141C',
  brd: '#162030', brd2: '#22324A',
  dim: '#7A9AB8', mid: '#B0D0E8', ink: '#E8F4FF',
  warn: '#FFCC00',
  onAccent: '#05070A',
}

/** The palette as CSS custom properties for `.look-a` (styles in index.css). */
export function lookAVars(p: LookAPalette): CSSProperties {
  return {
    '--a-bg': p.bg, '--a-card': p.card, '--a-bar': p.bar, '--a-button': p.button,
    '--a-brd': p.brd, '--a-brd2': p.brd2, '--a-dim': p.dim, '--a-mid': p.mid, '--a-ink': p.ink,
    '--a-warn': p.warn, '--a-on-accent': p.onAccent,
  } as CSSProperties
}
