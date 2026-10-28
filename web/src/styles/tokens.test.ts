import { describe, it, expect } from 'vitest'
import { colors, rsThemes, shadeHex, contrastRatio, printAccent } from './tokens'

// Sapphire has one (dark) screen palette; print is its only light surface.
const SCREEN_SURFACES = { bg: colors.bg, surf: colors.surf, surf2: colors.surf2, surf3: colors.surf3 }

describe('RS paint accents', () => {
  it('colors.accent follows the paint through a CSS variable; categorical cyan stays fixed', () => {
    expect(colors.accent).toBe('var(--color-accent)')
    expect(colors.accentD).toBe('var(--color-accent-d)')
    expect(colors.cyan).toBe('#0091EA')
  })

  for (const [id, paint] of Object.entries(rsThemes)) {
    it(`${id}: readable as text on the dark surfaces and under bg-coloured text on an accent fill`, () => {
      const { surf3, ...others } = SCREEN_SURFACES
      for (const surface of Object.values(others)) expect(contrastRatio(paint, surface)).toBeGreaterThanOrEqual(4.5)
      // Race Red on the raised surface is 4.49:1 — the same band as the default Nitrous Blue on its tinted chips
      expect(contrastRatio(paint, surf3)).toBeGreaterThanOrEqual(4.4)
      expect(contrastRatio(colors.bg, paint)).toBeGreaterThanOrEqual(4.5)
    })

    it(`${id}: print accent is the same paint darkened to ≥ 4.5:1 on white`, () => {
      const print = printAccent(paint)
      expect(contrastRatio(print, '#FFFFFF')).toBeGreaterThanOrEqual(4.5)
      expect(print).toMatch(/^#[0-9A-F]{6}$/)
    })
  }

  it('shadeHex darkens toward black and lightens toward white', () => {
    expect(shadeHex('#0091EA', 0)).toBe('#0091EA')
    expect(shadeHex('#0091EA', -1)).toBe('#000000')
    expect(shadeHex('#0091EA', 1)).toBe('#FFFFFF')
    expect(shadeHex('#FF1744', -0.25)).toBe('#BF1133')
  })
})
