import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { tripChartData } from './readings'
import { primaryBoostSource } from './boost'

// Unmodified ZIP exported through the Android share sheet by the final minified rc.7 release APK (source 625c43b,
// SHA-256 1d155b35…97b9, 2026-10-29) on a disposable emulator with a synthetic adapter (fake_ecu_v3): a 4:38 drive
// whose TIP 0x033E went silent for 20 s, so the pressure came from CAN 0x0F8 (genuine 0 readings) for part of it.
// Drive CSV: 183 TIP_033E + 26 CAN_0F8 points; profile JSON peak 8.95 psi TIP_033E, policy rc7-boost-v3, one BOOST
// peak per source. Synthetic data — no car.
const ZIP = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc7/release-tip-can-drive.zip')
const load = () => importZip(new File([readFileSync(ZIP)], 'release-tip-can-drive.zip', { type: 'application/zip' }))

describe('rc.7 release export → Sapphire (#207 boost provenance)', () => {
  it('keeps each point\'s source and CAN\'s genuine zeros', async () => {
    const pts = (await load()).trip!.points
    expect(pts).toHaveLength(209)
    expect(pts.filter((p) => p.boostSource === 'TIP_033E')).toHaveLength(183)
    const can = pts.filter((p) => p.boostSource === 'CAN_0F8')
    expect(can).toHaveLength(26)
    expect(can.every((p) => p.boostPsi === 0)).toBe(true)            // a measured 0, not "no reading"
    expect(pts.some((p) => p.boostPsi == null)).toBe(false)          // this drive had no point without a reading
  })

  it('names the peak by its source and keeps one peak per source', async () => {
    const sum = (await load()).trip!.summary
    expect(sum.boostPolicy).toBe('rc7-boost-v3')
    expect(sum.peakBoostSource).toBe('TIP_033E')
    expect(sum.peakBoostPsi).toBeCloseTo(8.949, 2)
    const peaks = sum.peakEvents.filter((e) => e.type === 'boost').map((e) => [e.source, e.value])
    expect(peaks).toEqual(expect.arrayContaining([['TIP_033E', expect.closeTo(8.949, 2)], ['CAN_0F8', 0]]))
  })

  it('never joins TIP and CAN into one series', async () => {
    const pts = (await load()).trip!.points
    expect(primaryBoostSource(pts)).toBe('TIP_033E')
    const rows = tripChartData(pts)
    expect(rows.every((r) => r.boostTip == null || r.boostCan == null)).toBe(true)
    expect(rows.filter((r) => r.boostCan != null)).toHaveLength(26)
  })
})
