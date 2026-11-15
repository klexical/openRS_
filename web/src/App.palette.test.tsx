import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'

// #384 ticket 08, "Match device, Night, Day and Ultra": the whole App over an in-memory IndexedDB, with the device's
// light / dark setting (window.matchMedia, prefers-color-scheme) replaced by an invented one per test, at the same
// whole-app seam (integrator, 2026-11-15). Invented data only.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))
// jsdom has no layout, so Recharts' ResponsiveContainer measures 0 and draws nothing: give its chart a fixed size, so
// the real charts draw their lines and bars (the colours the test reads)
vi.mock('recharts', async (importOriginal) => {
  const orig = await importOriginal<typeof import('recharts')>()
  const { cloneElement, isValidElement } = await import('react')
  return {
    ...orig,
    ResponsiveContainer: ({ children }: { children: unknown }) =>
      isValidElement(children) ? cloneElement(children as React.ReactElement<{ width: number; height: number }>, { width: 600, height: 300 }) : null,
  }
})

type Listener = (e: { matches: boolean }) => void
/** The device's setting, invented: 'dark', 'light', or 'none' (states no preference). `old` = Safari's older form. */
function device(start: 'dark' | 'light' | 'none', { old = false } = {}) {
  let scheme = start
  const lists = new Set<{ q: string; ls: Set<Listener> }>()
  const matches = (q: string) => (q.includes('dark') ? scheme === 'dark' : q.includes('light') ? scheme === 'light' : false)
  vi.stubGlobal('matchMedia', (q: string) => {
    const entry = { q, ls: new Set<Listener>() }
    lists.add(entry)
    const mql: Record<string, unknown> = {
      media: q, get matches() { return matches(q) }, onchange: null,
      addListener: (l: Listener) => entry.ls.add(l), removeListener: (l: Listener) => entry.ls.delete(l),
    }
    if (!old) {
      mql.addEventListener = (_: string, l: Listener) => entry.ls.add(l)
      mql.removeEventListener = (_: string, l: Listener) => entry.ls.delete(l)
    }
    return mql
  })
  return {
    set(next: 'dark' | 'light' | 'none') {
      scheme = next
      for (const e of lists) for (const l of [...e.ls]) l({ matches: matches(e.q) })
    },
  }
}
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}
async function boot(hash = '') {
  cleanup()
  window.history.replaceState(null, '', `/${hash}`)
  vi.resetModules()
  const { useStore } = await import('./store')
  const { useSettings } = await import('./store/settings')
  const { contrastRatio } = await import('./styles/tokens')
  const { default: App } = await import('./App')
  render(<App />)
  return { useStore, useSettings, contrastRatio }
}
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  // a browser that has seen "What's new" (ticket 09): this file's tests seed earlier drives, which would open it
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => {
  cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/')
  document.documentElement.removeAttribute('style')
})

/** The palette the new screens are drawn in now, read from the background they are given (CLAUDE.md, Theme Colors). */
const BACKGROUNDS: Record<string, string> = { '#05070A': 'night', '#EFF3F8': 'day', '#000000': 'ultra' }
const shown = () => {
  const el = document.querySelector('.look-a') as HTMLElement | null
  return el ? BACKGROUNDS[el.style.getPropertyValue('--a-bg').toUpperCase()] ?? 'unknown' : null
}
const lookVar = (name: string) => (document.querySelector('.look-a') as HTMLElement).style.getPropertyValue(name)
const rootVar = (name: string) => document.documentElement.style.getPropertyValue(name)
const palettePicker = () => screen.findByRole('radiogroup', { name: 'Palette' }, { timeout: 3000 })

describe('#384 ticket 08: the first visit follows the device; Match device picks between Night and Day', () => {
  it('a device set to dark shows Night', async () => {
    device('dark')
    await boot()
    await waitFor(() => expect(shown()).toBe('night'))
  })

  it('a device set to light shows Day', async () => {
    device('light')
    await boot()
    await waitFor(() => expect(shown()).toBe('day'))
  })

  it('a device that states no preference shows Day (a browser reports it as light)', async () => {
    device('none')
    await boot()
    await waitFor(() => expect(shown()).toBe('day'))
  })

  it('Match device follows a change of the device setting while Sapphire is open', async () => {
    const d = device('dark')
    await boot()
    await waitFor(() => expect(shown()).toBe('night'))
    act(() => d.set('light'))
    expect(shown()).toBe('day')
    act(() => d.set('dark'))
    expect(shown()).toBe('night')
  })

  it('it follows on a browser that offers only the older listener form (older Safari)', async () => {
    const d = device('light', { old: true })
    await boot()
    await waitFor(() => expect(shown()).toBe('day'))
    act(() => d.set('dark'))
    expect(shown()).toBe('night')
  })
})

describe('#384 ticket 08: Settings offers Match device, Night, Day and Ultra, named "Palette"', () => {
  it('the four choices, in that order, under "Palette"; Match device is chosen on a first visit', async () => {
    device('dark')
    await boot('#/settings')
    const picker = await palettePicker()
    const options = within(picker).getAllByRole('radio')
    expect(options.map((o) => o.textContent)).toEqual(['Match device', 'Night', 'Day', 'Ultra'])
    expect(within(picker).getByRole('radio', { name: 'Match device' })).toHaveAttribute('aria-checked', 'true')
  })

  it('Ultra is a choice in Settings only: chosen, it stays whatever the device does, and after a reload', async () => {
    const d = device('dark')
    await boot('#/settings')
    fireEvent.click(within(await palettePicker()).getByRole('radio', { name: 'Ultra' }))
    expect(shown()).toBe('ultra')
    act(() => d.set('light'))
    expect(shown()).toBe('ultra')
    await boot()
    await waitFor(() => expect(shown()).toBe('ultra'))
  })

  it('a fixed choice stops following the device; Match device follows it again', async () => {
    const d = device('light')
    await boot('#/settings')
    const picker = await palettePicker()
    fireEvent.click(within(picker).getByRole('radio', { name: 'Night' }))
    expect(shown()).toBe('night')
    act(() => d.set('dark')); act(() => d.set('light'))
    expect(shown()).toBe('night')
    fireEvent.click(within(picker).getByRole('radio', { name: 'Match device' }))
    expect(shown()).toBe('day')
  })

  it('the option is never named "Auto", and the words "Theme", "Mode" and "Look" do not name it', async () => {
    device('dark')
    await boot('#/settings')
    await palettePicker()
    const text = screen.getByRole('region', { name: 'Palette and paint colour' }).textContent ?? ''
    expect(text).not.toMatch(/\b(Auto|Theme|Mode|Look)\b/i)
  })
})

describe('#384 ticket 08: the paint and the warning colour meet AA on Day; the old views take the palette', () => {
  const DAY_SURFACES = ['--a-bg', '--a-card', '--a-button']

  it('Day: Nitrous Blue\'s accent is the app\'s #0064B0; every paint\'s accent, and text on it, meet 4.5:1', async () => {
    device('light')
    const { useSettings, contrastRatio } = await boot()
    await waitFor(() => expect(shown()).toBe('day'))
    expect(rootVar('--color-accent').toUpperCase()).toBe('#0064B0')
    for (const paint of ['cyan', 'red', 'orange', 'grey', 'black', 'white'] as const) {
      act(() => useSettings.getState().setThemeId(paint))
      const accent = rootVar('--color-accent')
      for (const s of DAY_SURFACES) expect(contrastRatio(accent, lookVar(s)), `${paint} on ${s}`).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(lookVar('--a-on-accent'), accent), `text on ${paint}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('Day: warning text is a darker shade that meets 4.5:1; warning fills and markers keep #E09500', async () => {
    device('light')
    const { contrastRatio } = await boot()
    await waitFor(() => expect(shown()).toBe('day'))
    expect(lookVar('--a-warn').toUpperCase()).toBe('#E09500')
    const text = lookVar('--a-warn-text')
    for (const s of DAY_SURFACES) expect(contrastRatio(text, lookVar(s)), `warning text on ${s}`).toBeGreaterThanOrEqual(4.5)
  })

  it('the old Trip, Dashboard and Diagnostics views take the palette: their surface and text colours follow it', async () => {
    const d = device('dark')
    await boot()
    await waitFor(() => expect(shown()).toBe('night'))
    const night = ['--color-bg', '--color-surf2', '--color-frost', '--color-dim'].map(rootVar)
    act(() => d.set('light'))
    const day = ['--color-bg', '--color-surf2', '--color-frost', '--color-dim'].map(rootVar)
    expect(night.every(Boolean) && day.every(Boolean)).toBe(true)
    day.forEach((v, i) => expect(v).not.toBe(night[i]))
    expect(day[0].toUpperCase()).toBe(lookVar('--a-bg').toUpperCase())             // the same Day as the new screens
  })
})

describe('#384 ticket 08: the paint colours buttons, tabs and highlights only; data colours and the route stay fixed', () => {
  /** Every element in the drive's views that takes the paint, outside a control (button, link, tab) or the Trip brush. */
  const paintedData = () => [...document.querySelectorAll('.old-view *')].filter((el) => {
    if (el.closest('button, a, [role="tab"], .recharts-brush')) return false
    const style = (el as HTMLElement).style
    const values = [style?.color, style?.stroke, style?.fill, style?.background, style?.backgroundColor,
      el.getAttribute('stroke'), el.getAttribute('fill'), el.getAttribute('color')]
    return values.some((v) => v?.includes('--color-accent'))
  }).map((el) => `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 30)}"`)

  it('a drive\'s Dashboard and Trip draw no figure, line or bar in the paint (Race Red chosen)', async () => {
    device('dark')
    vi.resetModules()
    const storage = await import('./lib/storage')
    const T0 = 4_000_000_000_000
    const points = Array.from({ length: 40 }, (_, i) => ({ ts: T0 + i * 1000, lat: 10 + i / 1e4, lng: 20, speedKph: 40 + i, rpm: 2500 + i * 50 }))
    await storage.storeImported([{ id: 'd', name: 'Invented d', importedAt: T0, tags: [],
      trip: { points: points as never, summary: { distanceKm: 1, durationMs: 39_000, fuelUsedL: 1, avgSpeedKph: 60, peakRpm: 4450,
        peakBoostPsi: 12, peakLatG: 0.5, peakLongG: 0.3, peakSpeedKph: 79, peakCoolantC: 90, peakOilTempC: 95, avgRpm: 3500,
        avgFuelEconomy: 9, modeBreakdown: {}, peakEvents: [] } },
      diagnostics: null, meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(T0).toISOString(), generatedAt: '' } }])
    storage.closeStorage()
    localStorage.setItem('sapphire_settings', JSON.stringify({ themeId: 'red' }))
    await boot('#/drive/d')
    expect(await screen.findByText('Avg RPM', {}, { timeout: 3000 })).toBeInTheDocument()       // the Dashboard is drawn
    expect(paintedData()).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: 'Trip' }))
    expect(await screen.findByText('RPM Distribution', {}, { timeout: 3000 })).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('.old-view .recharts-line-curve, .old-view .recharts-bar-rectangle').length).toBeGreaterThan(0))
    expect(paintedData()).toEqual([])
  })
})

