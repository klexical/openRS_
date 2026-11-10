import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { KNOWN_EXPORT_SCHEMA_VERSION, exportSchemaNote } from './exportSchema'

// #253: the rc.8 diagnostic export adds `didInventory` and `linkIntegrity.isoTpRefused` (additive: the schema version stays
// the same). Sapphire reads the inventory; a never-answered DID carries no time (absent, not 0), and an export without the
// key has no inventory (not recorded), never an empty one. Fixture written by the Android production exporter
// (ExportSchemaVersionTest); synthetic counts and times.
const DIR = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc8-inventory/')   // vitest root = web/

type Files = Record<string, string>

function files(): Files {
  return Object.fromEntries(readdirSync(DIR).filter((f) => f !== 'README.md').map((f) => [f, readFileSync(DIR + f, 'utf8')]))
}

async function zip(f: Files): Promise<File> {
  const z = new JSZip()
  for (const [name, text] of Object.entries(f)) z.file(name, text)
  return new File([await z.generateAsync({ type: 'blob' })], 'openrs_export_rc8_inventory.zip', { type: 'application/zip' })
}

const DETAIL = 'diagnostic_detail_20260101_000000.json'

describe('rc.8 diagnostic export with the DID inventory (#253) → Sapphire', () => {
  it('the fixture carries the added fields as the app writes them', () => {
    const detail = JSON.parse(files()[DETAIL])
    expect(detail.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
    expect(detail.linkIntegrity.isoTpRefused).toBe(1)
    const rows: Array<Record<string, unknown>> = detail.didInventory.rows
    expect(rows.map((r) => `${r.module} ${r.did}`).sort()).toEqual(['BCM 4028', 'PCM 033E', 'PCM 03ED', 'PCM 0466'])
    const never = rows.find((r) => r.did === '0466')!
    expect(never.positive).toBe(0)
    expect('lastPositiveRelMs' in never).toBe(false)                    // unavailable, not a zero time
    expect(typeof rows.find((r) => r.did === '033E')!.lastPositiveRelMs).toBe('number')
    expect(detail.didInventory.overflow).toBe(0)
  })

  it('imports as a current export: version recorded, no note, diagnostics parsed', async () => {
    const s = await importZip(await zip(files()))
    expect(s.meta.exportSchemaVersion).toBe(KNOWN_EXPORT_SCHEMA_VERSION)
    expect(exportSchemaNote(s.meta.exportSchemaVersion)).toBeNull()
    expect(s.diagnostics).toBeTruthy()
  })

  it('reads the inventory as the app wrote it: counts, and a time only for a DID that answered', async () => {
    const inv = (await importZip(await zip(files()))).diagnostics!.didInventory!
    expect(inv.rows).toEqual([
      { module: 'BCM', responseId: '72E', did: '4028', sent: 1, positive: 1, nrcAttributed: 0, lastPositiveRelMs: 0 },
      { module: 'PCM', responseId: '7E8', did: '033E', sent: 3, positive: 3, nrcAttributed: 0, lastPositiveRelMs: 0 },
      { module: 'PCM', responseId: '7E8', did: '03ED', sent: 2, positive: 0, nrcAttributed: 2 },
      { module: 'PCM', responseId: '7E8', did: '0466', sent: 2, positive: 0, nrcAttributed: 0 },
    ])
    expect(inv.overflow).toBe(0)
    expect(inv.unreadable).toBe(0)
  })

  it('an export without the inventory imports with none (not recorded), and nothing else changes', async () => {
    const f = files()
    const detail = JSON.parse(f[DETAIL])
    delete detail.didInventory
    delete detail.linkIntegrity.isoTpRefused
    const without = await importZip(await zip({ ...f, [DETAIL]: JSON.stringify(detail, null, 2) }))
    const withFields = await importZip(await zip(f))
    expect(without.diagnostics!.didInventory).toBeUndefined()
    const strip = (s: typeof withFields) => ({ ...s, id: '', importedAt: 0, diagnostics: { ...s.diagnostics!, didInventory: undefined } })
    expect(strip(withFields)).toEqual(strip(without))                   // isoTpRefused is still not read
  })

  it('holds no real run time: the only time values are the fixed ones', () => {
    const text = Object.entries(files()).map(([name, body]) => `${name}\n${body}`).join('\n')
    expect([...new Set(text.match(/\d{13}/g) ?? [])]).toEqual(['1767225600000'])
    expect([...new Set(text.match(/\d{8}_\d{6}/g) ?? [])]).toEqual(['20260101_000000'])
  })
})
