import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, cleanup, waitFor, act, fireEvent, screen, within } from '@testing-library/react'
import { openDB } from 'idb'
import type { Session } from './types/session'

// #384 ticket 05 at the page: look after a drive from its row (one menu: rename, note, tags, export, delete), ticked rows
// (Delete selected, Compare). The whole App over an in-memory IndexedDB (fake-indexeddb) and the real storage module;
// only the map is stubbed. Invented data only, dated 2096.

vi.mock('./components/charts/GpsMap', () => ({ GpsMap: () => null }))

function drive(id: string, name: string, day: number, sessionOnly = false): Session {
  const t0 = new Date(2096, 4, day, 9).getTime()
  return {
    id, name, importedAt: t0 + 3_600_000, tags: [],
    trip: sessionOnly ? null : {
      points: [0, 1, 2].map((i) => ({ ts: t0 + i * 1000, lat: 10 + day / 100, lng: 20 + i / 1000, speedKph: 80, rpm: 2500 })) as never,
      summary: { distanceKm: 12.3, distanceSource: 'recorded', durationMs: 1_200_000, fuelUsedL: 0, avgSpeedKph: 50, peakRpm: 5000,
        peakBoostPsi: 14.2, peakBoostSource: 'CAN_0F8', peakLatG: 0, peakLongG: 0, peakSpeedKph: 80, peakCoolantC: 90,
        peakOilTempC: 95, avgRpm: 2500, avgFuelEconomy: 0, modeBreakdown: {}, peakEvents: [] },
    } as never,
    diagnostics: sessionOnly ? { canInventory: [], fpsTimeline: [], decodeTrace: [], probeResults: [], dtcResults: [],
      sessionEvents: [{ ts: 0, type: 'STATE', message: 'Connected' }] } as never : null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: new Date(t0).toISOString(), generatedAt: '' },
  }
}
const DRIVES = [
  drive('e', 'Evening loop', 12), drive('h', 'Hill road', 10), drive('m', 'Morning commute', 8),
  drive('c', 'Circuit afternoon', 6), drive('r', 'Rainy errand', 4), drive('s', 'A scan at the kerb', 2, true),
]

async function boot() {
  window.history.replaceState(null, '', '/')
  vi.resetModules()
  const { useStore } = await import('./store')
  const storage = await import('./lib/storage')
  const { default: App } = await import('./App')
  return { useStore, storage, App }
}
async function seed(sessions = DRIVES) {
  vi.resetModules()
  const storage = await import('./lib/storage')
  await storage.storeImported(sessions)
  storage.closeStorage()
}
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    get length() { return m.size }, clear: () => m.clear(), getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, String(v)) }, removeItem: (k) => { m.delete(k) }, key: (i) => [...m.keys()][i] ?? null,
  }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  // a browser that has seen "What's new" (ticket 09): this file's tests seed earlier drives, which would open it
  localStorage.setItem('sapphire_whats_new_seen', 'slice-2')
  ;(globalThis as { indexedDB: IDBFactory }).indexedDB = new IDBFactory()
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState(null, '', '/') })

async function list() {
  const root = await screen.findByRole('region', { name: 'Drives' }, { timeout: 3000 })
  await waitFor(() => expect(root).toHaveAttribute('aria-busy', 'false'), { timeout: 3000 })
  return root
}
const row = (root: HTMLElement, name: string) => within(root).getAllByRole('listitem').find((li) => within(li).queryByRole('link', { name }))!
const rowNames = (root: HTMLElement) => within(root).queryAllByRole('listitem').map((li) => within(li).getByRole('link').textContent)
const menuButton = (root: HTMLElement, name: string) => within(row(root, name)).getByRole('button', { name: `Actions for ${name}` })
async function start() {
  await seed()
  const booted = await boot()
  render(<booted.App />)
  return { ...booted, root: await list() }
}
/** A reload: fresh modules over the same database. */
async function reload() {
  cleanup()
  const booted = await boot()
  render(<booted.App />)
  return { ...booted, root: await list() }
}
const key = (el: Element, k: string, init: object = {}) => fireEvent.keyDown(el, { key: k, ...init })

describe('#384 ticket 05: one menu per row', () => {
  it('a visible menu button on every row opens the menu: rename, note, tags, export, delete', async () => {
    const { root } = await start()
    const button = menuButton(root, 'Evening loop')
    expect(button).toHaveAttribute('aria-haspopup', 'menu')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    const menu = screen.getByRole('menu', { name: 'Actions for Evening loop' })
    expect(within(menu).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(
      ['Rename…', 'Note…', 'Tags…', 'Export trip CSV', 'Export full JSON', 'Delete…'])
    // a session-only entry has no trip to export as CSV
    fireEvent.click(menuButton(root, 'A scan at the kerb'))
    expect(within(screen.getByRole('menu', { name: 'Actions for A scan at the kerb' })).queryByRole('menuitem', { name: 'Export trip CSV' })).toBeNull()
    expect(screen.getAllByRole('menu')).toHaveLength(1)                  // one menu open at a time
  }, 15_000)                                                             // under 1 s alone, past 5 s under load (#422)

  it('works by keyboard alone: arrows move, Escape closes and returns to the button, Enter chooses', async () => {
    const { root } = await start()
    const button = menuButton(root, 'Hill road')
    button.focus()
    key(button, 'ArrowDown')
    const menu = screen.getByRole('menu', { name: 'Actions for Hill road' })
    const items = within(menu).getAllByRole('menuitem')
    expect(document.activeElement).toBe(items[0])
    key(items[0], 'ArrowDown'); expect(document.activeElement).toBe(items[1])
    key(items[1], 'End'); expect(document.activeElement).toBe(items[items.length - 1])
    key(items[items.length - 1], 'ArrowDown'); expect(document.activeElement).toBe(items[0])       // wraps
    key(items[0], 'ArrowUp'); expect(document.activeElement).toBe(items[items.length - 1])
    key(document.activeElement!, 'Escape')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(button)
    // Enter on the button opens the menu at its first item; Enter on "Rename…" opens the editor, focused
    key(button, 'Enter')
    expect(document.activeElement).toBe(within(screen.getByRole('menu')).getAllByRole('menuitem')[0])
    fireEvent.click(document.activeElement!)
    const input = within(row(root, 'Hill road')).getByRole('textbox', { name: 'Name' })
    expect(document.activeElement).toBe(input)
    key(input, 'Escape')
    expect(within(row(root, 'Hill road')).queryByRole('textbox')).toBeNull()
    expect(document.activeElement).toBe(menuButton(root, 'Hill road'))
  })

  it('right-click on a row opens its menu with a mouse; a touch (long press) is left to the browser', async () => {
    const { root } = await start()
    const li = row(root, 'Morning commute')
    fireEvent.pointerDown(li, { pointerType: 'touch' })
    expect(fireEvent.contextMenu(li)).toBe(true)                 // not prevented: the browser's own behaviour
    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.pointerDown(li, { pointerType: 'mouse', button: 2 })
    expect(fireEvent.contextMenu(li)).toBe(false)                // prevented: ours instead of the browser's
    expect(screen.getByRole('menu', { name: 'Actions for Morning commute' })).toBeInTheDocument()
    expect(document.activeElement).toBe(within(screen.getByRole('menu')).getAllByRole('menuitem')[0])
  })
})

describe('#384 ticket 05: rename, note and tags are kept across a reload', () => {
  const choose = (root: HTMLElement, name: string, item: string) => {
    fireEvent.click(menuButton(root, name))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: item }))
  }

  it('rename', async () => {
    const { root } = await start()
    choose(root, 'Hill road', 'Rename…')
    const input = within(row(root, 'Hill road')).getByRole('textbox', { name: 'Name' })
    fireEvent.change(input, { target: { value: 'Hill road, second run' } })
    await act(async () => { key(input, 'Enter') })
    await waitFor(() => expect(rowNames(root)).toContain('Hill road, second run'))
    const again = await reload()
    expect(rowNames(again.root)).toContain('Hill road, second run')
    expect(rowNames(again.root)).not.toContain('Hill road')
  })

  it('note: shown on the row, kept, and written to the small record only', async () => {
    const { root } = await start()
    choose(root, 'Evening loop', 'Note…')
    const area = within(row(root, 'Evening loop')).getByRole('textbox', { name: 'Note' })
    expect(document.activeElement).toBe(area)
    fireEvent.change(area, { target: { value: 'Oil temperature to watch' } })
    await act(async () => { fireEvent.click(within(row(root, 'Evening loop')).getByRole('button', { name: 'Save' })) })
    await waitFor(() => expect(row(root, 'Evening loop').textContent).toContain('Oil temperature to watch'))
    const again = await reload()
    expect(row(again.root, 'Evening loop').textContent).toContain('Oil temperature to watch')
    const db = await openDB('sapphire')
    expect((await db.get('drives', 'e')).note).toBe('Oil temperature to watch')
    expect(Object.keys(await db.get('points', 'e'))).toEqual(['id', 'trip'])     // the points record is untouched
    db.close()
  })

  it('tags: toggled from the menu, shown on the row, kept', async () => {
    const { root } = await start()
    choose(root, 'Circuit afternoon', 'Tags…')
    const group = within(row(root, 'Circuit afternoon')).getByRole('group', { name: 'Tags' })
    const trackDay = within(group).getByRole('button', { name: 'Track Day' })
    expect(trackDay).toHaveAttribute('aria-pressed', 'false')
    await act(async () => { fireEvent.click(trackDay) })
    await waitFor(() => expect(trackDay).toHaveAttribute('aria-pressed', 'true'))
    fireEvent.click(within(row(root, 'Circuit afternoon')).getByRole('button', { name: 'Done' }))
    expect(row(root, 'Circuit afternoon').textContent).toContain('Track Day')
    const again = await reload()
    expect(row(again.root, 'Circuit afternoon').textContent).toContain('Track Day')
  })
})

describe('#384 ticket 05: export from the row gives the same files as today', () => {
  it('trip CSV and full JSON: the same names and bytes as the drive page export', async () => {
    const files: { name: string; text: string }[] = []
    URL.createObjectURL = vi.fn(() => 'blob:invented') as never
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      const blob = (URL.createObjectURL as unknown as { mock: { calls: [Blob][] } }).mock.calls.at(-1)![0]
      void blob.text().then((text) => files.push({ name: this.download, text }))
    })
    try {
      const { root, storage } = await start()
      for (const item of ['Export trip CSV', 'Export full JSON']) {
        fireEvent.click(menuButton(root, 'Evening loop'))
        await act(async () => { fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: item })) })
      }
      await waitFor(() => expect(files).toHaveLength(2))
      // today's export: lib/export on the drive as the drive page holds it
      const { exportSessionCsv, exportSessionJson } = await import('./lib/export')
      const session = (await storage.loadDrive('e'))!
      exportSessionCsv(session); exportSessionJson(session)
      await waitFor(() => expect(files).toHaveLength(4))
      expect(files[0]).toEqual(files[2])
      expect(files[1]).toEqual(files[3])
      expect(files.map((f) => f.name)).toEqual(['Evening_loop_trip.csv', 'Evening_loop.json', 'Evening_loop_trip.csv', 'Evening_loop.json'])
    } finally { click.mockRestore() }
  })
})

describe('#384 ticket 05: delete asks first and is final', () => {
  it('Delete… asks; Cancel keeps the drive; Delete removes it, and it stays gone after a reload', async () => {
    const { root } = await start()
    fireEvent.click(menuButton(root, 'Rainy errand'))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Delete…' }))
    const ask = screen.getByRole('alertdialog', { name: 'Delete Rainy errand?' })
    expect(ask.textContent).toMatch(/cannot be undone/)
    expect(document.activeElement).toBe(within(ask).getByRole('button', { name: 'Cancel' }))   // the safe choice first
    fireEvent.click(within(ask).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(rowNames(root)).toContain('Rainy errand')
    fireEvent.click(menuButton(root, 'Rainy errand'))
    fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: 'Delete…' }))
    await act(async () => { fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' })) })
    await waitFor(() => expect(rowNames(root)).not.toContain('Rainy errand'))
    const again = await reload()
    expect(rowNames(again.root)).not.toContain('Rainy errand')
    expect(rowNames(again.root)).toHaveLength(DRIVES.length - 1)
  })
})

describe('#384 ticket 05: ticked rows', () => {
  const tick = (root: HTMLElement, name: string) => fireEvent.click(within(row(root, name)).getByRole('checkbox', { name: `Select ${name}` }))

  it('"Delete selected" from two ticked rows, after asking; one ticked row does not offer it', async () => {
    const { root } = await start()
    tick(root, 'Hill road')
    expect(within(root).queryByRole('button', { name: /Delete selected/ })).toBeNull()
    tick(root, 'Morning commute')
    fireEvent.click(within(root).getByRole('button', { name: 'Delete selected (2)' }))
    const ask = screen.getByRole('alertdialog', { name: 'Delete 2 drives?' })
    expect(ask.textContent).toMatch(/Hill road/)
    expect(ask.textContent).toMatch(/Morning commute/)
    await act(async () => { fireEvent.click(within(ask).getByRole('button', { name: 'Delete' })) })
    await waitFor(() => expect(rowNames(root)).toHaveLength(DRIVES.length - 2))
    const again = await reload()
    expect(rowNames(again.root)).not.toContain('Hill road')
    expect(rowNames(again.root)).not.toContain('Morning commute')
  })

  it('"Delete selected" is all or none: when it fails, nothing is deleted and the message says so', async () => {
    const { root } = await start()
    tick(root, 'Hill road'); tick(root, 'Morning commute')
    const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
    const del = proto.delete
    let calls = 0
    proto.delete = function (this: unknown, ...a: unknown[]) { if (++calls === 4) throw new DOMException('invented failure', 'UnknownError'); return del.apply(this, a) }
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      fireEvent.click(within(root).getByRole('button', { name: 'Delete selected (2)' }))
      await act(async () => { fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' })) })
      await waitFor(() => expect(within(root).getByRole('alert').textContent).toBe("Couldn't delete the selected drives: nothing was deleted. Try again."))
    } finally { proto.delete = del; warn.mockRestore() }
    expect(rowNames(root)).toHaveLength(DRIVES.length)
    expect(within(row(root, 'Hill road')).getByRole('checkbox')).toBeChecked()     // still ticked, for a retry
    const again = await reload()
    expect(rowNames(again.root)).toHaveLength(DRIVES.length)
    const db = await openDB('sapphire')
    for (const store of ['drives', 'points', 'diagnostics']) {
      expect(await db.get(store, 'h')).toBeDefined()
      expect(await db.get(store, 'm')).toBeDefined()
    }
    db.close()
  })

  it('Compare opens from two to four ticked rows, in the order they were ticked', async () => {
    const { root, useStore } = await start()
    tick(root, 'Hill road')
    expect(within(root).queryByRole('button', { name: /^Compare/ })).toBeNull()
    tick(root, 'Evening loop')
    fireEvent.click(within(root).getByRole('button', { name: 'Compare (2)' }))
    await waitFor(() => expect(useStore.getState().compareSessionIds).toEqual(['h', 'e']))
    expect(await screen.findByText('Evening loop vs Hill road', {}, { timeout: 5000 })).toBeInTheDocument()   // today's Compare, against the first ticked
    fireEvent.click(screen.getByRole('button', { name: 'Back to the list' }))
    const back = await list()
    for (const n of ['Morning commute', 'Circuit afternoon', 'Rainy errand']) tick(back, n)
    expect(within(back).getByRole('button', { name: /^Compare/ })).toBeDisabled()
    expect(back.textContent).toMatch(/Compare takes two to four drives/)
  })
})

describe('#384 ticket 05, QA notes N1 and N2 (in ticket 06)', () => {
  /** A contextmenu event as a browser sends it; [pointerType] when the browser states one (Chromium: a PointerEvent). */
  const contextMenu = (el: Element, pointerType?: string) => {
    const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    if (pointerType !== undefined) Object.defineProperty(ev, 'pointerType', { value: pointerType })
    let result = true
    act(() => { result = el.dispatchEvent(ev) })
    return result
  }

  it('N1: after a touch has ended, the keyboard menu key opens the menu; a contextmenu that says it is a touch does not', async () => {
    const { root } = await start()
    const li = row(root, 'Morning commute')
    fireEvent.pointerDown(li, { pointerType: 'touch' })
    fireEvent.pointerUp(li, { pointerType: 'touch' })
    expect(contextMenu(li)).toBe(false)                          // the menu key: no pointer down, no type stated
    expect(screen.getByRole('menu', { name: 'Actions for Morning commute' })).toBeInTheDocument()
    key(document.activeElement!, 'Escape')
    expect(contextMenu(li, 'touch')).toBe(true)                  // Chromium's long press states a touch: left to the browser
    expect(screen.queryByRole('menu')).toBeNull()
    expect(contextMenu(li, 'mouse')).toBe(false)
  })

  describe('N2: a save that fails says so and changes nothing', () => {
    const failDrivesPut = () => {
      const proto = (globalThis as unknown as { IDBObjectStore: { prototype: Record<string, (...a: unknown[]) => unknown> } }).IDBObjectStore.prototype
      const put = proto.put
      proto.put = function (this: { name: string }, ...a: unknown[]) {
        if (this.name === 'drives') throw new DOMException('invented failure', 'QuotaExceededError')
        return put.apply(this, a)
      }
      return () => { proto.put = put }
    }
    const choose = (root: HTMLElement, name: string, item: string) => {
      fireEvent.click(menuButton(root, name))
      fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: item }))
    }
    for (const what of ['rename', 'note', 'tags'] as const) {
      it(what, async () => {
        const { root } = await start()
        const db = await openDB('sapphire'); const before = await db.get('drives', 'h'); db.close()
        const restore = failDrivesPut()
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
        try {
          if (what === 'rename') {
            choose(root, 'Hill road', 'Rename…')
            const input = within(row(root, 'Hill road')).getByRole('textbox', { name: 'Name' })
            fireEvent.change(input, { target: { value: 'Never saved' } })
            await act(async () => { key(input, 'Enter') })
          } else if (what === 'note') {
            choose(root, 'Hill road', 'Note…')
            fireEvent.change(within(row(root, 'Hill road')).getByRole('textbox', { name: 'Note' }), { target: { value: 'Never saved' } })
            await act(async () => { fireEvent.click(within(row(root, 'Hill road')).getByRole('button', { name: 'Save' })) })
          } else {
            choose(root, 'Hill road', 'Tags…')
            await act(async () => { fireEvent.click(within(row(root, 'Hill road')).getByRole('button', { name: 'Rain' })) })
          }
          await waitFor(() => expect(within(root).getByRole('alert').textContent).toBe("Couldn't save the change to Hill road. Try again."))
        } finally { restore(); warn.mockRestore() }
        expect(rowNames(root)).toContain('Hill road')
        expect(row(root, 'Hill road').querySelector('.a-tags')).toBeNull()                 // no tag shown on the row
        expect(row(root, 'Hill road').querySelector('.a-name')?.textContent).toBe('Hill road')
        expect(row(root, 'Hill road').querySelector('.a-note')).toBeNull()
        const db2 = await openDB('sapphire'); const after = await db2.get('drives', 'h'); db2.close()
        expect(after).toEqual(before)
      })
    }
  })
})
