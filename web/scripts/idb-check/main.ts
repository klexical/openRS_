// Exposes Sapphire's production modules unchanged; check.mjs drives them in a real browser.
import * as storage from '../../src/lib/storage'
import { importBatchZip } from '../../src/lib/import'

/** A version-1 database as Sapphire stored it before #384: one `sessions` store holding whole sessions. */
function writeV1(sessions: unknown[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('sapphire', 1)
    req.onupgradeneeded = () => { req.result.createObjectStore('sessions', { keyPath: 'id' }) }
    req.onerror = () => reject(req.error)
    req.onsuccess = () => {
      const db = req.result
      const tx = db.transaction('sessions', 'readwrite')
      for (const s of sessions) tx.objectStore('sessions').put(s)
      tx.oncomplete = () => { db.close(); resolve() }
      tx.onerror = () => reject(tx.error)
    }
  })
}

/** Open the database at a version (none = current) without Sapphire: its version, stores and every record. */
function readRaw(version?: number): Promise<{ version: number; stores: string[]; records: Record<string, unknown[]> }> {
  return new Promise((resolve, reject) => {
    const req = version ? indexedDB.open('sapphire', version) : indexedDB.open('sapphire')
    req.onupgradeneeded = () => {}
    req.onerror = () => reject(req.error)
    req.onsuccess = () => {
      const db = req.result
      const stores = [...db.objectStoreNames]
      const out: Record<string, unknown[]> = {}
      if (stores.length === 0) { db.close(); resolve({ version: db.version, stores, records: out }); return }
      const tx = db.transaction(stores, 'readonly')
      for (const s of stores) { const r = tx.objectStore(s).getAll(); r.onsuccess = () => { out[s] = r.result } }
      tx.oncomplete = () => { db.close(); resolve({ version: db.version, stores, records: out }) }
    }
  })
}

/** Hold a connection open and never close it on request: an old tab running Sapphire from before #384. */
let held: IDBDatabase | null = null
function holdOpen(version: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('sapphire', version)
    req.onupgradeneeded = () => { if (req.result.objectStoreNames.length === 0) req.result.createObjectStore('other') }
    req.onerror = () => reject(req.error)
    req.onsuccess = () => { held = req.result; resolve(held.version) }
  })
}
const release = () => { held?.close(); held = null }

Object.assign(window, { sapphire: { ...storage, importBatchZip, writeV1, readRaw, holdOpen, release }, ready: true })
