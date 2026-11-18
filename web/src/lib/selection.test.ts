import { describe, it, expect } from 'vitest'
import { clampRange, cursorWithin, driveSpanMs, indexWindow, pointsInRange, rangeOfIndices, runRange } from './selection'
import { formatRange, parseRange } from './address'

// #416 ticket 2: the selection's arithmetic, at the module's public functions (#389: "a range from a performance run,
// clamping, whole drive"). A range is milliseconds from the drive's first point; null is the whole drive. Every expected
// value is written by hand. Invented values only.

const pts = (offsets: number[], t0 = 4_000_000_000_000) => offsets.map((o) => ({ ts: t0 + o }))

describe('#416 ticket 2: the selection', () => {
  it("a drive's span runs from its first point to its last; fewer than two points, or none apart, have no span", () => {
    expect(driveSpanMs(pts([0, 1000, 2000, 60_000]))).toBe(60_000)
    expect(driveSpanMs(pts([5000, 9000]))).toBe(4000)
    expect(driveSpanMs(pts([0]))).toBeNull()
    expect(driveSpanMs([])).toBeNull()
    expect(driveSpanMs(pts([0, 0]))).toBeNull()
  })

  it('a range is clamped to the drive', () => {
    expect(clampRange({ from: 10_000, to: 20_000 }, 60_000)).toEqual({ from: 10_000, to: 20_000 })
    expect(clampRange({ from: -5000, to: 20_000 }, 60_000)).toEqual({ from: 0, to: 20_000 })
    expect(clampRange({ from: 50_000, to: 90_000 }, 60_000)).toEqual({ from: 50_000, to: 60_000 })
  })

  it('a range that covers the whole drive is the whole drive, and so is one with nothing left inside the drive', () => {
    expect(clampRange({ from: 0, to: 60_000 }, 60_000)).toBeNull()
    expect(clampRange({ from: -1, to: 70_000 }, 60_000)).toBeNull()
    expect(clampRange({ from: 70_000, to: 80_000 }, 60_000)).toBeNull()       // after the drive's end
    expect(clampRange({ from: 30_000, to: 30_000 }, 60_000)).toBeNull()       // empty
    expect(clampRange({ from: 40_000, to: 30_000 }, 60_000)).toBeNull()       // backwards
  })

  it('"whole drive" is one value, and a drive without a span has no range', () => {
    expect(clampRange(null, 60_000)).toBeNull()
    expect(clampRange({ from: 10_000, to: 20_000 }, null)).toBeNull()
  })

  it('a performance run sets the range to that run, clamped to the drive (times invented: the export gives none yet)', () => {
    const t0 = 4_000_000_000_000
    expect(runRange({ startTs: t0 + 12_000, endTs: t0 + 17_500 }, t0, 60_000)).toEqual({ from: 12_000, to: 17_500 })
    expect(runRange({ startTs: t0 + 55_000, endTs: t0 + 66_000 }, t0, 60_000)).toEqual({ from: 55_000, to: 60_000 })
    expect(runRange({ startTs: t0 - 3000, endTs: t0 + 4000 }, t0, 60_000)).toEqual({ from: 0, to: 4000 })
    expect(runRange({ startTs: t0 + 70_000, endTs: t0 + 80_000 }, t0, 60_000)).toBeNull()
  })

  it('the points in a range: both ends included; the whole drive is every point', () => {
    const p = pts([0, 1000, 2000, 3000, 4000])
    expect(pointsInRange(p, { from: 1000, to: 3000 }).map((x) => x.ts - p[0].ts)).toEqual([1000, 2000, 3000])
    expect(pointsInRange(p, { from: 1500, to: 2500 }).map((x) => x.ts - p[0].ts)).toEqual([2000])
    expect(pointsInRange(p, null)).toBe(p)
  })

  it('the cursor stays only inside the range', () => {
    expect(cursorWithin(15_000, { from: 10_000, to: 20_000 })).toBe(15_000)
    expect(cursorWithin(25_000, { from: 10_000, to: 20_000 })).toBeNull()
    expect(cursorWithin(25_000, null)).toBe(25_000)
    expect(cursorWithin(null, { from: 10_000, to: 20_000 })).toBeNull()
  })

  it('in the address a range is "<from>-<to>" in whole milliseconds; anything else is no range', () => {
    expect(formatRange({ from: 12_000, to: 47_500 })).toBe('12000-47500')
    expect(parseRange('12000-47500')).toEqual({ from: 12_000, to: 47_500 })
    expect(parseRange('0-1')).toEqual({ from: 0, to: 1 })
    for (const bad of ['', 'x', '12000', '12000-', '-5-10', '1.5-3', '12000-47500-1', '1e3-2e3', ' 1-2', '00012-40'])
      expect(parseRange(bad)).toBeNull()
  })

  it('a brush over a chart\'s rows: the range as the rows it holds, and back (both ends included)', () => {
    const xs = [0, 1, 2, 3, 4, 5]
    expect(indexWindow(xs, null)).toEqual({ startIndex: 0, endIndex: 5 })
    expect(indexWindow(xs, { from: 1.5, to: 4 })).toEqual({ startIndex: 2, endIndex: 4 })
    expect(indexWindow(xs, { from: 1.2, to: 1.8 })).toEqual({ startIndex: 0, endIndex: 5 })   // no row inside: the whole chart
    expect(indexWindow([], { from: 1, to: 2 })).toBeNull()
    expect(rangeOfIndices(xs, 2, 4)).toEqual({ from: 2, to: 4 })
    expect(rangeOfIndices(xs, 0, 5)).toBeNull()                                               // every row: the whole drive
    expect(rangeOfIndices(xs, 3, 3)).toEqual({ from: 3, to: 3 })
  })
})

// QA's FINAL on delivery A, R3 (the spec review's C4): the selection never holds an empty or reversed range.
describe('#416 ticket 2: setRange (QA R3)', () => {
  it('an empty or reversed range is the whole drive; a real one is kept', async () => {
    const { useStore } = await import('../store')
    const { setRange } = await import('./selection')
    setRange({ from: 5000, to: 1000 })
    expect(useStore.getState().selection.range).toBeNull()
    setRange({ from: 3000, to: 3000 })
    expect(useStore.getState().selection.range).toBeNull()
    setRange({ from: 1000, to: 3000 })
    expect(useStore.getState().selection.range).toEqual({ from: 1000, to: 3000 })
    setRange({ from: 4000, to: 2000 })
    expect(useStore.getState().selection.range).toBeNull()
  })
})
