import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from './import'
import { compareCards } from './compare'

// #416 ticket 3: a drive whose export has no coordinates
// never received a distance. Its stored summary says 0 (the sum of 0/0 points); Compare reads the points and shows no
// figure for it and no difference. Peaks, averages and duration keep their real zeros. The fixtures are the project's own.

const FIXTURES = join(process.cwd(), 'src/lib/__fixtures__/android-exports/')
const fixture = (name: string) => new File([readFileSync(FIXTURES + name)], name, { type: 'application/zip' })

describe('#416 ticket 3: Compare and a drive without coordinates', () => {
  it('the fixture without coordinates: points, every one at 0/0, a stored distance of 0', async () => {
    const s = await importZip(fixture('drive-nogps-telemetry.zip'))
    expect(s.trip!.points.length).toBeGreaterThan(1)
    expect(s.trip!.points.every((p) => p.lat === 0 && p.lng === 0)).toBe(true)
    expect(s.trip!.summary.distanceKm).toBe(0)
  })

  it('its Distance card shows no figure for it and no difference, either way round', async () => {
    const gps = (await importZip(fixture('drive-gps-companions.zip'))).trip!
    const nogps = (await importZip(fixture('drive-nogps-telemetry.zip'))).trip!
    const distance = (base: typeof gps, comp: typeof gps) => compareCards(base, comp).find((c) => c.label === 'Distance')
    expect(distance(gps, nogps)).toMatchObject({ baseValue: gps.summary.distanceKm, compValue: null, diff: null, pctChange: null })
    expect(distance(nogps, gps)).toMatchObject({ baseValue: null, compValue: gps.summary.distanceKm, diff: null, pctChange: null })
    expect(distance(nogps, nogps)).toBeUndefined()
  })

  it('its other figures stay: duration and peaks are readings', async () => {
    const gps = (await importZip(fixture('drive-gps-companions.zip'))).trip!
    const nogps = (await importZip(fixture('drive-nogps-telemetry.zip'))).trip!
    const cards = compareCards(gps, nogps)
    expect(cards.find((c) => c.label === 'Duration')?.compValue).toBe(nogps.summary.durationMs)
    expect(cards.find((c) => c.label === 'Peak RPM')?.compValue).toBe(nogps.summary.peakRpm)
  })
})
