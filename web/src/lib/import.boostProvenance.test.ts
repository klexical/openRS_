import { describe, it, expect } from 'vitest'
import JSZip from 'jszip'
import { importZip } from './import'
import { tripChartData } from './readings'
import { pointColor } from './mapColors'
import { computeDeltas, normalizePoints } from './compare'
import { colors } from '../styles/tokens'
import type { TripSummary } from '../types/session'

// #207 export → import contract (2026-10-29): Android's drive CSV writes boost_psi empty for a point without a
// reading (boost_source NONE), a genuine 0.000 for a CAN reading of 0, and names each value's source. Sapphire must
// keep "no reading" out of every value (never 0), keep genuine zeros, keep CAN and TIP apart, and leave older rows
// without a source as recorded. Only modules that existed before the fix are imported, so this file also runs
// against the previous build (failing-before evidence).

async function zipOf(files: Record<string, string>): Promise<File> {
  const zip = new JSZip()
  for (const [n, c] of Object.entries(files)) zip.file(n, c)
  return new File([await zip.generateAsync({ type: 'blob' })], 'export.zip', { type: 'application/zip' })
}

const V13_HEADER = 'timestamp_ms,lat,lng,speed_kph,rpm,boost_psi,boost_source,baro_kpa'
const v13Rows = [
  '1000000,50.0000,19.0000,0.0,780,0.000,CAN_0F8,96.50',     // idle: CAN reads a genuine 0
  '1001000,50.0001,19.0001,30.0,3100,8.900,TIP_033E,96.50',  // under load: TIP − baro
  '1002000,50.0002,19.0002,35.0,2900,,NONE,96.50',           // no fresh source: no reading
  '1003000,50.0003,19.0003,40.0,2500,3.100,TIP_033E,96.50',
  '1004000,50.0004,19.0004,0.0,790,0.000,CAN_0F8,96.50',
]
const v13Csv = ['# openrs_csv_v2', V13_HEADER, ...v13Rows].join('\n')

describe('boost provenance through import (#207)', () => {
  it('an empty boost_psi (NONE) is no reading — not 0; a genuine 0 stays; each value keeps its source', async () => {
    const s = await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv }))
    const pts = s.trip!.points
    expect(pts.map((p) => p.boostPsi)).toEqual([0, 8.9, null, 3.1, 0])
    expect(pts.map((p) => p.boostSource ?? null)).toEqual(['CAN_0F8', 'TIP_033E', null, 'TIP_033E', 'CAN_0F8'])
  })

  it('summary: the peak names its source; one boost peak event per source, never one max across them', async () => {
    const s = await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv }))
    const sum = s.trip!.summary
    expect(sum.peakBoostPsi).toBe(8.9)
    expect(sum.peakBoostSource).toBe('TIP_033E')
    const boostPeaks = sum.peakEvents.filter((e) => e.type === 'boost').map((e) => [e.source, e.value])
    expect(boostPeaks).toEqual(expect.arrayContaining([['TIP_033E', 8.9], ['CAN_0F8', 0]]))
    expect(boostPeaks).toHaveLength(2)
  })

  it('a drive with no reading at all has no peak (null), not 0', async () => {
    const csv = ['# openrs_csv_v2', V13_HEADER, '1000000,50,19,0,800,,NONE,96.5', '1001000,50,19,0,800,,NONE,96.5'].join('\n')
    const profile = JSON.stringify({ version: 2, drive: { startTime: 1000000, endTime: 1001000, peakRpm: 800, boostPolicy: 'rc7-boost-v3' }, peaks: [] })
    const s = await importZip(await zipOf({ 'drive_20261029_120000.csv': csv, 'drive_profile_20261029_120000.json': profile }))
    expect(s.trip!.points.every((p) => p.boostPsi === null)).toBe(true)
    expect(s.trip!.summary.peakBoostPsi).toBeNull()
  })

  it('older exports (no boost_source column) keep values as recorded, with no source; an empty cell is still no reading', async () => {
    const csv = ['timestamp_ms,lat,lng,speed_kph,rpm,boost_psi', '1000000,50,19,0,800,4.200', '1001000,50,19,0,800,', '1002000,50,19,0,800,7.500'].join('\n')
    const s = await importZip(await zipOf({ 'drive_20261029_120000.csv': csv }))
    expect(s.trip!.points.map((p) => p.boostPsi)).toEqual([4.2, null, 7.5])
    expect(s.trip!.points.map((p) => p.boostSource ?? null)).toEqual([null, null, null])
    expect(s.trip!.summary.peakBoostPsi).toBe(7.5)
    expect(s.trip!.summary.peakBoostSource ?? null).toBeNull()
  })

  it('a legacy profile peak is kept as recorded without a source; a rule profile peak keeps its source', async () => {
    const legacy = JSON.stringify({ version: 2, drive: { startTime: 1, endTime: 2, peakBoostPsi: 7.5 }, peaks: [] })
    const a = await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv, 'drive_profile_20261029_120000.json': legacy }))
    expect(a.trip!.summary.peakBoostPsi).toBe(7.5)
    const rule = JSON.stringify({ version: 2, drive: { startTime: 1, endTime: 2, peakBoostPsi: 8.9, peakBoostSource: 'TIP_033E', boostPolicy: 'rc7-boost-v3' },
      peaks: [{ type: 'BOOST', value: 8.9, lat: 50, lng: 19, timestampMs: 1001000, source: 'TIP_033E' },
              { type: 'BOOST', value: 0, lat: 50, lng: 19, timestampMs: 1000000, source: 'CAN_0F8' }] })
    const b = await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv, 'drive_profile_20261029_120000.json': rule }))
    expect(b.trip!.summary.peakBoostSource).toBe('TIP_033E')
    expect(b.trip!.summary.peakEvents.filter((e) => e.type === 'boost').map((e) => e.source)).toEqual(['TIP_033E', 'CAN_0F8'])
  })

  it('chart rows: one series per source — no reading is a gap in all of them, sources never share a line', async () => {
    const s = await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv }))
    const rows = tripChartData(s.trip!.points)
    expect(rows.map((r) => r.boostTip ?? null)).toEqual([null, 8.9, null, 3.1, null])
    expect(rows.map((r) => r.boostCan ?? null)).toEqual([0, null, null, null, 0])
    expect(rows.every((r) => r.boostPsi == null)).toBe(true)
  })

  it('map BOOST colours only the drive peak source; another source or no reading is dimmed', async () => {
    const pts = (await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv }))).trip!.points
    expect(pointColor(pts[3], 'boost', 'TIP_033E')).toBe(colors.ok)       // TIP 3.1 psi
    expect(pointColor(pts[0], 'boost', 'TIP_033E')).toBe(colors.dim)      // CAN genuine 0 — another measurement
    expect(pointColor(pts[2], 'boost', 'TIP_033E')).toBe(colors.dim)      // no reading
  })

  it('compare: a boost delta only between peaks of one source; overlay values only from the primary source', async () => {
    const base = { peakBoostPsi: 8.9, peakBoostSource: 'TIP_033E' } as unknown as TripSummary
    const tip = { peakBoostPsi: 6.0, peakBoostSource: 'TIP_033E' } as unknown as TripSummary
    const can = { peakBoostPsi: 0, peakBoostSource: 'CAN_0F8' } as unknown as TripSummary
    const none = { peakBoostPsi: null } as unknown as TripSummary
    const boostRows = (a: TripSummary, b: TripSummary) => computeDeltas(a, b).filter((d) => /boost|tip/i.test(d.label))
    expect(boostRows(base, tip)).toHaveLength(1)
    expect(boostRows(base, can)).toHaveLength(0)
    expect(boostRows(base, none)).toHaveLength(0)
    const pts = (await importZip(await zipOf({ 'drive_20261029_120000.csv': v13Csv }))).trip!.points
    expect(normalizePoints(pts).map((p) => p.boostPsi)).toEqual([null, 8.9, null, 3.1, null])
  })
})
