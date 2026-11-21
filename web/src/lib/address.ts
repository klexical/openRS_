// Sapphire's addresses (#384 ticket 03): the hash is the memory, so reload and back / forward work without a server.
// Three places (spec, draft 11): `#/` the list of drives, `#/drive/<id>` one drive, `#/settings`. A drive's address also
// carries its view and its time range (#416 ticket 2): `#/drive/<id>/<view>?range=<from>-<to>`, milliseconds from the
// drive's first point; the first view and the whole drive are left out, so `#/drive/<id>` is the first view on the whole
// drive. A few lines of hash handling; no router library.
import { useSyncExternalStore } from 'react'
import type { TimeRange } from './selection'

/** A range as the address writes it: "<from>-<to>", whole milliseconds. */
export function formatRange(range: TimeRange): string {
  return `${Math.round(range.from)}-${Math.round(range.to)}`
}
/** A range from the address; null for anything not written as [formatRange] writes it. */
export function parseRange(text: string): TimeRange | null {
  const m = /^(0|[1-9]\d*)-(0|[1-9]\d*)$/.exec(text)
  return m ? { from: Number(m[1]), to: Number(m[2]) } : null
}

/** The views of one drive, in the order the page offers them; the first is the one a bare drive address opens. */
export const DRIVE_VIEWS = ['dashboard', 'trip', 'diagnostics'] as const
export type DriveView = typeof DRIVE_VIEWS[number]

/** A drive's address; a view or range left out is the first view, the whole drive. */
export type DriveAddress = { place: 'drive'; id: string; view?: DriveView; range?: TimeRange | null }
export type Address = { place: 'drives' } | DriveAddress | { place: 'settings' }

/**
 * Read an address from a hash. Anything not one of the three places, or not decodable, is the list. Within a drive, an
 * unknown view is the first view and a range not written as Sapphire writes it is the whole drive: the page then puts
 * the address right in place (the integrator's call, 2026-11-18).
 */
export function parseAddress(hash: string): Address {
  const path = hash.replace(/^#/, '')
  if (path === '/settings') return { place: 'settings' }
  const m = /^\/drive\/([^/?]+)(?:\/([^/?]*))?(?:\?(.*))?$/.exec(path)
  if (m) {
    try {
      const id = decodeURIComponent(m[1])
      if (id) {
        const view = (DRIVE_VIEWS as readonly string[]).includes(m[2] ?? '') ? m[2] as DriveView : DRIVE_VIEWS[0]
        const rangeText = /^range=(.*)$/.exec(m[3] ?? '')?.[1]
        return { place: 'drive', id, view, range: rangeText === undefined ? null : parseRange(rangeText) }
      }
    } catch { /* a malformed escape: not an address, show the list */ }
  }
  return { place: 'drives' }
}

export function hrefOf(a: Address): string {
  if (a.place === 'drive') {
    const view = a.view && a.view !== DRIVE_VIEWS[0] ? `/${a.view}` : ''
    const range = a.range ? `?range=${formatRange(a.range)}` : ''
    return `#/drive/${encodeURIComponent(a.id)}${view}${range}`
  }
  return a.place === 'settings' ? '#/settings' : '#/'
}

const listeners = new Set<() => void>()
let current = typeof window === 'undefined' ? '' : window.location.hash
let address: Address = parseAddress(current)
function read() {
  const hash = window.location.hash
  if (hash !== current) { current = hash; address = parseAddress(hash) }
  return address
}
function notify() { read(); for (const l of [...listeners]) l() }
function subscribe(l: () => void) {
  if (listeners.size === 0) { window.addEventListener('hashchange', notify); window.addEventListener('popstate', notify) }
  listeners.add(l)
  return () => {
    listeners.delete(l)
    if (listeners.size === 0) { window.removeEventListener('hashchange', notify); window.removeEventListener('popstate', notify) }
  }
}

/** The current address; the component re-renders when it changes (a link, back / forward, a typed address). */
export function useAddress(): Address {
  return useSyncExternalStore(subscribe, read, read)
}

/** The current address, outside React. */
export function currentAddress(): Address { return read() }

/**
 * Go to an address. `replace` takes the place of the current history entry (no extra Back step): used when an address
 * cannot be shown, so Back does not lead to it again.
 */
export function go(a: Address, { replace = false } = {}) {
  const href = hrefOf(a)
  if (replace) {
    window.history.replaceState(window.history.state, '', href)
    notify()
  } else {
    window.location.hash = href   // fires hashchange
  }
}
