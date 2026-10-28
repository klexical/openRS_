// Exposes Sapphire's production modules unchanged; check.mjs drives them in a real browser.
import { putSession, putSessions, getAllSessions } from '../../src/lib/db'
import { importBatchZip } from '../../src/lib/import'

Object.assign(window, { sapphire: { putSession, putSessions, getAllSessions, importBatchZip }, ready: true })
