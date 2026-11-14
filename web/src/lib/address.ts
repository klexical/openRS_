// Sapphire's addresses (#384 ticket 03): the hash is the memory, so reload and back / forward work without a server.
// Three forms only (spec, draft 11): `#/` the list of drives, `#/drive/<id>` one drive, `#/settings`. A few lines of hash
// handling are enough for three forms; no router library (the unused react-router-dom is removed in this ticket).
import { useSyncExternalStore } from 'react'

export type Address = { place: 'drives' } | { place: 'drive'; id: string } | { place: 'settings' }

/** Read an address from a hash. Anything not one of the three forms, or not decodable, is the list. */
export function parseAddress(hash: string): Address {
  const path = hash.replace(/^#/, '')
  if (path === '/settings') return { place: 'settings' }
  const m = /^\/drive\/([^/]+)$/.exec(path)
  if (m) {
    try {
      const id = decodeURIComponent(m[1])
      if (id) return { place: 'drive', id }
    } catch { /* a malformed escape: not an address, show the list */ }
  }
  return { place: 'drives' }
}

export function hrefOf(a: Address): string {
  if (a.place === 'drive') return `#/drive/${encodeURIComponent(a.id)}`
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
