import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, screen } from '@testing-library/react'

// QA F14: when a look A page throws while it draws, Shell's error message reads at AA in Night, Day and Ultra: the
// palette's muted text (text-dim-text) on look A's background. The whole App, the list page made to throw. jsdom has no
// stylesheet: the class is held here, and that class's colour as the root holds it per palette; the colour itself is
// measured in a browser (a placed measure, QA's errorboundary-class.mjs). Invented data only.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))
vi.mock('./components/drives/DrivesPage', () => ({ DrivesPage: () => { throw new Error('Invented: the list could not draw.') } }))

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('QA F14: the page error message on a look A page', () => {
  it('reads at AA in Night, Day and Ultra: the palette\'s muted text on look A\'s background', async () => {
    const { contrastRatio } = await import('./styles/tokens')
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})   // React and the boundary report the error
    try {
      for (const palette of ['night', 'day', 'ultra']) {
        cleanup()
        localStorage.setItem('sapphire_settings', JSON.stringify({ palette }))
        vi.resetModules()
        const { default: App } = await import('./App')
        render(<App />)
        const message = await screen.findByText('Invented: the list could not draw.')
        expect(screen.getByText('Error loading this page')).toBeInTheDocument()
        expect(message, palette).toHaveClass('text-dim-text')
        const fg = document.documentElement.style.getPropertyValue('--color-dim-text').trim()
        const bg = (document.querySelector('.look-a') as HTMLElement).style.getPropertyValue('--a-bg').trim()
        expect(contrastRatio(fg, bg), `${palette}: ${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5)
      }
    } finally { error.mockRestore() }
  })
})
