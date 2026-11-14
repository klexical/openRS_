import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import JSZip from 'jszip'
import { useStore } from '../../store'
import { importZip } from '../../lib/import'
import type { Session } from '../../types/session'
import { ComparePanel } from './ComparePanel'
import { storeWithSessions } from '../../test/storeState'

// #344: below the phone breakpoint (md, 768 px) Compare stacks its route maps one per row for every number of drives (a
// 2-up grid left them 169 px wide at 375 px with three or four); md and up keep two per row. jsdom does no layout, so this
// checks the classes in effect below md; scripts/layout-check measures the pixels.

vi.mock('../charts/GpsMap', () => ({ GpsMap: () => <div data-testid="map" /> }))

const classes = (el: Element) => (el.getAttribute('class') ?? '').split(/\s+/).filter(Boolean)
const belowMd = (el: Element) => classes(el).filter((c) => !/^(sm|md|lg|xl|2xl):/.test(c))

let drive: Session
beforeAll(async () => {
  const zip = new JSZip()
  const root = join(process.cwd(), 'src/lib/__fixtures__/android-export-rc6/1-track')
  for (const f of readdirSync(root)) zip.file(f, readFileSync(join(root, f), 'utf8'))
  drive = await importZip(new File([await zip.generateAsync({ type: 'blob' })], 'openrs_export_1-track.zip'))
  expect(drive.trip!.points.some((p) => p.lat !== 0)).toBe(true)
})
afterEach(cleanup)

describe('Compare route maps at phone width (#344)', () => {
  for (const count of [2, 3, 4]) {
    it(`stacks ${count} maps one per row below md, two per row from md`, () => {
      const sessions = Array.from({ length: count }, (_, i) => ({ ...drive, id: `d${i}`, name: `Drive ${i + 1}` }))
      useStore.setState({ ...storeWithSessions(sessions, null, sessions.map((s) => s.id)), activePanel: 'compare' })
      const { getAllByTestId } = render(<ComparePanel />)
      const maps = getAllByTestId('map')
      expect(maps).toHaveLength(count)
      const grid = maps[0].parentElement!.parentElement!
      expect(belowMd(grid)).toContain('grid-cols-1')
      expect(belowMd(grid)).not.toContain('grid-cols-2')
      expect(classes(grid)).toContain('md:grid-cols-2')
    })
  }
})
