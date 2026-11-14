import { describe, it, expect } from 'vitest'
import { contrastRatio, rsThemes } from './tokens'
import { lookANight } from './lookA'

// #384 ticket 03: the new screens (the list of drives, the places bar, the drive frame) meet WCAG AA text contrast in
// look A's Night palette, with every paint accent. Each pair is one the screens draw: text colour on its background.
describe('#384 ticket 03: look A Night text contrast (WCAG AA, 4.5:1)', () => {
  const p = lookANight
  const pairs: [string, string, string][] = [
    ['ink on bg', p.ink, p.bg], ['ink on card', p.ink, p.card],
    ['mid on bg', p.mid, p.bg], ['mid on card', p.mid, p.card],
    ['dim on bg', p.dim, p.bg], ['dim on card', p.dim, p.card], ['dim on bar', p.dim, p.bar],
    ['ink on bar', p.ink, p.bar], ['ink on button', p.ink, p.button],
    ['warn on bar', p.warn, p.bar], ['warn on bg', p.warn, p.bg],
    // ticket 04: the "Warnings" marker on a row (the search box and the not-checked line use ink, mid and dim on card, above)
    ['warn on card (the Warnings marker)', p.warn, p.card],
  ]
  it.each(pairs)('%s', (_, fg, bg) => { expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(4.5) })

  it.each(Object.entries(rsThemes))('the primary button: on-accent text on the %s paint', (_, paint) => {
    expect(contrastRatio(p.onAccent, paint)).toBeGreaterThanOrEqual(4.5)
  })
  it.each(Object.entries(rsThemes))('the focus ring and current-place mark (3:1, non-text): %s paint on bg and bar', (_, paint) => {
    expect(contrastRatio(paint, p.bg)).toBeGreaterThanOrEqual(3)
    expect(contrastRatio(paint, p.bar)).toBeGreaterThanOrEqual(3)
  })
})
