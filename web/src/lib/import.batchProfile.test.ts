import { describe, it, expect, beforeAll } from 'vitest'
import JSZip from 'jszip'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip, importBatchZip } from './import'
import type { Session } from '../types/session'

// Per-drive profile association in batch imports. Base data: the batch shared from TRIP by the app
// (batch-gps-nogps-empty.zip — drive 1 GPS, profile peakRpm 6700 / CSV 4600; drive 2 empty; drive 3 no GPS,
// profile 6800 / CSV 5000). Its manifest is the rc.6 one that labels per-drive profiles "unknown".
const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/android-exports/')   // vitest root = web/

const D1 = 'drive_1_20251009_025320'
const D3 = 'drive_3_20251011_025320'
let entries: Record<string, string>

beforeAll(async () => {
  const zip = await JSZip.loadAsync(readFileSync(FIXTURES + 'batch-gps-nogps-empty.zip'))
  entries = {}
  for (const name of Object.keys(zip.files)) entries[name] = await zip.files[name].async('string')
})

async function zipOf(files: [string, string][], name = 'openrs_batch.zip'): Promise<File> {
  const zip = new JSZip()
  for (const [f, text] of files) zip.file(f, text)                       // insertion order = ZIP order
  return new File([await zip.generateAsync({ type: 'blob' })], name, { type: 'application/zip' })
}

const without = (...names: string[]) => Object.entries(entries).filter(([n]) => !names.includes(n))
const byFirstTs = (sessions: Session[]) => Object.fromEntries(sessions.map((s) => [s.trip!.points[0].ts, s]))
const D1_TS = 1760000000000
const D3_TS = 1760172800000

function manifestWith(types: (name: string, type: string) => string): string {
  const m = JSON.parse(entries['manifest.json'])
  m.files = m.files.map((f: { name: string; type: string }) => ({ ...f, type: types(f.name, f.type) }))
  return JSON.stringify(m)
}

const corrected = (name: string, type: string) =>
  name.endsWith('_profile.json') ? 'drive_profile' : name.endsWith('_summary.txt') ? 'drive_summary' : type

/** The same drive exported on its own: drive_<ts>.csv + drive_profile_<ts>.json (+ summary). */
async function singleImport(prefix: string, profile = entries[`${prefix}_profile.json`]): Promise<Session> {
  const ts = prefix.replace(/^drive_\d+_/, '')
  const files: [string, string][] = [[`drive_${ts}.csv`, entries[`${prefix}.csv`]], [`drive_summary_${ts}.txt`, entries[`${prefix}_summary.txt`]]]
  if (profile) files.push([`drive_profile_${ts}.json`, profile])
  return importZip(await zipOf(files, `openrs_export_${ts}.zip`))
}

describe('batch import: per-drive profile association', () => {
  it('rc.6 manifest ("unknown" profiles): each drive gets its own profile, identical to its single-drive import', async () => {
    const batch = byFirstTs(await importBatchZip(await zipOf(Object.entries(entries))))
    expect(Object.keys(batch)).toHaveLength(2)                             // the empty drive 2 has no CSV
    expect(batch[D1_TS].trip!.summary.peakRpm).toBe(6700)
    expect(batch[D3_TS].trip!.summary.peakRpm).toBe(6800)
    expect(batch[D1_TS].trip!.profile!.drive.distanceKm).toBe(31.4)
    expect(batch[D3_TS].trip!.profile!.drive.distanceKm).toBe(40)

    for (const [prefix, ts] of [[D1, D1_TS], [D3, D3_TS]] as const) {
      const single = await singleImport(prefix)
      expect(batch[ts].trip).toEqual(single.trip)
      expect(batch[ts].tags).toEqual(single.tags)
    }
  })

  it('a corrected manifest (drive_profile / drive_summary) gives the same associations', async () => {
    const old = byFirstTs(await importBatchZip(await zipOf(Object.entries(entries))))
    const fixed = byFirstTs(await importBatchZip(await zipOf([...without('manifest.json'), ['manifest.json', manifestWith(corrected)]])))
    expect(fixed[D1_TS].trip).toEqual(old[D1_TS].trip)
    expect(fixed[D3_TS].trip).toEqual(old[D3_TS].trip)
  })

  it('the app\'s corrected batch manifest (fixed exporter) imports the same trips as the rc.6 one', async () => {
    const zip = await JSZip.loadAsync(readFileSync(FIXTURES + 'batch-typed-manifest.zip'))
    const types = JSON.parse(await zip.files['manifest.json'].async('string')).files
      .filter((f: { name: string }) => /_(profile\.json|summary\.txt)$/.test(f.name)).map((f: { type: string }) => f.type)
    expect(types.sort()).toEqual(['drive_profile', 'drive_profile', 'drive_summary', 'drive_summary', 'drive_summary'])

    const typed = byFirstTs(await importBatchZip(new File([readFileSync(FIXTURES + 'batch-typed-manifest.zip')], 'b.zip')))
    const old = byFirstTs(await importBatchZip(await zipOf(Object.entries(entries))))
    expect(Object.keys(typed)).toHaveLength(2)
    expect(typed[D1_TS].trip).toEqual(old[D1_TS].trip)
    expect(typed[D3_TS].trip).toEqual(old[D3_TS].trip)
  })

  it('deliberately different profiles never cross over, whatever the ZIP / manifest order', async () => {
    const p1 = JSON.parse(entries[`${D1}_profile.json`])
    const p3 = JSON.parse(entries[`${D3}_profile.json`])
    p1.drive = { ...p1.drive, peakRpm: 6700, peakBoostPsi: 21.5, tags: 'TRACK' }
    p3.drive = { ...p3.drive, peakRpm: 5100, peakBoostPsi: 9.5, tags: 'COMMUTE' }
    const base: [string, string][] = Object.entries({
      ...entries, [`${D1}_profile.json`]: JSON.stringify(p1), [`${D3}_profile.json`]: JSON.stringify(p3),
    })
    const singles: Record<number, Session> = { [D1_TS]: await singleImport(D1, JSON.stringify(p1)), [D3_TS]: await singleImport(D3, JSON.stringify(p3)) }

    const reversedManifest = () => {
      const m = JSON.parse(entries['manifest.json'])
      m.files.reverse()
      return JSON.stringify(m)
    }
    const orders: [string, string][][] = [
      base,
      [...base].reverse(),
      [...base.filter(([n]) => n.endsWith('_profile.json')), ...base.filter(([n]) => !n.endsWith('_profile.json'))],
      [...base.slice(5), ...base.slice(0, 5)],
      [...base.filter(([n]) => n !== 'manifest.json'), ['manifest.json', reversedManifest()] as [string, string]].reverse(),
    ]
    for (const files of orders) {
      const got = byFirstTs(await importBatchZip(await zipOf(files)))
      expect(got[D1_TS].trip!.summary.peakRpm).toBe(6700)
      expect(got[D1_TS].tags).toEqual(['TRACK'])
      expect(got[D3_TS].trip!.summary.peakRpm).toBe(5100)
      expect(got[D3_TS].trip!.summary.peakBoostPsi).toBe(9.5)
      expect(got[D3_TS].tags).toEqual(['COMMUTE'])
      for (const ts of [D1_TS, D3_TS]) expect(got[ts].trip).toEqual(singles[ts].trip)
    }
  })

  it('a missing profile keeps the CSV-only summary; a merge never changes the recorded samples', async () => {
    const withProfiles = byFirstTs(await importBatchZip(await zipOf(Object.entries(entries))))
    const noD1 = byFirstTs(await importBatchZip(await zipOf(without(`${D1}_profile.json`))))
    const csvOnly = byFirstTs(await importBatchZip(await zipOf(without(`${D1}_profile.json`, `${D3}_profile.json`))))

    expect(noD1[D1_TS].trip!.summary.peakRpm).toBe(4600)                 // CSV-derived
    expect(noD1[D1_TS].trip!.profile).toBeUndefined()
    expect(noD1[D3_TS].trip!.summary.peakRpm).toBe(6800)                 // drive 3 keeps its own
    for (const ts of [D1_TS, D3_TS]) {
      expect(withProfiles[ts].trip!.points).toEqual(csvOnly[ts].trip!.points)
      expect(csvOnly[ts].trip!.profile).toBeUndefined()
    }
  })

  it('a profile whose drive identity matches no CSV is not applied to any drive', async () => {
    const files = without(`${D1}_profile.json`, `${D3}_profile.json`)
    files.push(['drive_9_20251009_025320_profile.json', entries[`${D1}_profile.json`]])
    files.push(['drive_1_20251009_025321_profile.json', entries[`${D1}_profile.json`]])   // same id, other start
    const got = byFirstTs(await importBatchZip(await zipOf(files)))
    expect(got[D1_TS].trip!.profile).toBeUndefined()
    expect(got[D3_TS].trip!.profile).toBeUndefined()
  })

  it('drive CSVs without an identity in the name get no profile in a batch (never by ZIP order)', async () => {
    const got = await importBatchZip(await zipOf([
      ['drive_a.csv', entries[`${D1}.csv`]],
      ['drive_b.csv', entries[`${D3}.csv`]],
      ['drive_profile_20251009_025320.json', entries[`${D1}_profile.json`]],
    ]))
    expect(got).toHaveLength(2)
    for (const s of got) expect(s.trip!.profile).toBeUndefined()
  })

  it('single-drive ZIPs: a profile naming another drive, or two candidate profiles, is not merged', async () => {
    const csv = entries[`${D1}.csv`]
    const profile = entries[`${D1}_profile.json`]
    const other = await importZip(await zipOf([['drive_20251009_025320.csv', csv], ['drive_profile_20251011_025320.json', profile]]))
    expect(other.trip!.profile).toBeUndefined()
    expect(other.trip!.summary.peakRpm).toBe(4600)

    const two = await importZip(await zipOf([['drive_x.csv', csv], ['drive_profile_20251009_025320.json', profile], ['drive_profile_20251011_025320.json', profile]]))
    expect(two.trip!.profile).toBeUndefined()

    // One CSV without an identity + exactly one profile: still paired (older exports)
    const one = await importZip(await zipOf([['drive_x.csv', csv], ['drive_profile_20251009_025320.json', profile]]))
    expect(one.trip!.summary.peakRpm).toBe(6700)
  })
})
