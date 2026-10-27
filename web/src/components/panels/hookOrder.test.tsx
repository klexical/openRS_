import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { act, render, cleanup } from '@testing-library/react'
import JSZip from 'jszip'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { importZip } from '../../lib/import'
import { useStore } from '../../store'
import type { Session } from '../../types/session'
import { DashboardPanel } from './DashboardPanel'
import { TripPanel } from './TripPanel'
import { ComparePanel } from './ComparePanel'
import { SessionsPanel } from './SessionsPanel'
import { Sparkline } from '../charts/Sparkline'

// Leaflet needs a real layout engine; the map is not what these tests are about.
vi.mock('../charts/GpsMap', () => ({ GpsMap: () => null }))

// Each component stays mounted while its data goes empty → populated → empty (and the selected drive changes).
// Hooks after an early return made React throw "Rendered more hooks than during the previous render" on the
// first empty → populated step.

const EXPORTS = join(process.cwd(), 'src/lib/__fixtures__/')   // vitest root = web/

async function driveSession(dir: string): Promise<Session> {
  const zip = new JSZip()
  const root = EXPORTS + 'android-export-rc6/' + dir
  for (const f of readdirSync(root)) zip.file(f, readFileSync(root + '/' + f, 'utf8'))
  const file = new File([await zip.generateAsync({ type: 'blob' })], `openrs_export_${dir}.zip`)
  return { ...(await importZip(file)), name: dir }
}

async function diagSession(): Promise<Session> {
  const name = 'diag-snapshot-debug.zip'
  const s = await importZip(new File([readFileSync(EXPORTS + 'android-exports/' + name)], name))
  return { ...s, name: 'diag-only' }
}

let driveA: Session, driveB: Session, diagOnly: Session
let errors: ReturnType<typeof vi.spyOn>

beforeAll(async () => {
  driveA = await driveSession('1-track')
  driveB = await driveSession('3-street-to-race')
  diagOnly = await diagSession()
  expect(driveA.trip!.points.length).toBeGreaterThan(0)
  expect(diagOnly.trip).toBeNull()
  expect(diagOnly.diagnostics).toBeTruthy()
})

beforeEach(() => {
  useStore.setState({ sessions: [], activeSessionId: null, compareSessionIds: [] })
  errors = vi.spyOn(console, 'error')
})

afterEach(() => {
  cleanup()
  errors.mockRestore()
})

const set = (state: Partial<ReturnType<typeof useStore.getState>>) => act(() => { useStore.setState(state) })

function expectNoHookErrors() {
  const hookErrors = errors.mock.calls.filter((args: unknown[]) =>
    args.some((a: unknown) => /hook/i.test(String(a instanceof Error ? a.message : a))))
  expect(hookErrors).toEqual([])
}

describe('hook order across data transitions (same mounted component)', () => {
  it('DashboardPanel — no sessions → drive → other drive → diagnostics-only → none selected → no sessions', () => {
    const { container } = render(<DashboardPanel />)
    expect(container.textContent).toContain('Welcome to Sapphire')

    set({ sessions: [driveA, driveB, diagOnly], activeSessionId: driveA.id })
    expect(container.textContent).toContain('1-track')
    set({ activeSessionId: driveB.id })
    expect(container.textContent).toContain('3-street-to-race')
    set({ activeSessionId: diagOnly.id })
    expect(container.textContent).toContain('diag-only')
    set({ activeSessionId: null })
    expect(container.textContent).toContain('No Session Selected')
    set({ activeSessionId: driveA.id })
    expect(container.textContent).toContain('1-track')
    set({ sessions: [], activeSessionId: null })
    expect(container.textContent).toContain('Welcome to Sapphire')
    expectNoHookErrors()
  })

  it('TripPanel — none selected → drive → other drive → diagnostics-only → drive → none selected', () => {
    const { container } = render(<TripPanel />)
    expect(container.textContent).toContain('No Session Selected')

    set({ sessions: [driveA, driveB, diagOnly], activeSessionId: driveA.id })
    expect(container.textContent).toContain('1-track')
    set({ activeSessionId: driveB.id })
    expect(container.textContent).toContain('3-street-to-race')
    set({ activeSessionId: diagOnly.id })
    expect(container.textContent).toContain('No Trip Data')
    set({ activeSessionId: driveA.id })
    expect(container.textContent).toContain('1-track')
    set({ sessions: [], activeSessionId: null })
    expect(container.textContent).toContain('No Session Selected')
    expectNoHookErrors()
  })

  it('ComparePanel — too few drives → two compared → selection changed → too few drives', () => {
    const { container } = render(<ComparePanel />)
    expect(container.textContent).toContain('Need More Sessions')

    set({ sessions: [driveA, driveB, diagOnly], compareSessionIds: [driveA.id, driveB.id] })
    expect(container.textContent).toContain('Select Sessions to Compare')
    set({ compareSessionIds: [driveB.id, driveA.id] })
    set({ compareSessionIds: [driveB.id] })
    expect(container.textContent).toContain('Select Sessions to Compare')
    set({ sessions: [driveA, diagOnly], compareSessionIds: [] })
    expect(container.textContent).toContain('Need More Sessions')
    expectNoHookErrors()
  })

  it('SessionsPanel — no sessions → three sessions → selection changed → no sessions', () => {
    const { container } = render(<SessionsPanel />)
    expect(container.textContent).toContain('No Sessions')

    set({ sessions: [driveA, driveB, diagOnly], activeSessionId: driveA.id })
    expect(container.textContent).toContain('3-street-to-race')
    set({ activeSessionId: diagOnly.id })
    expect(container.textContent).toContain('diag-only')
    set({ sessions: [], activeSessionId: null })
    expect(container.textContent).toContain('No Sessions')
    expectNoHookErrors()
  })

  it('Sparkline — no data → data → other data → no data', () => {
    const { container, rerender } = render(<Sparkline data={[]} />)
    expect(container.querySelector('svg')).toBeNull()

    rerender(<Sparkline data={[1, 3, 2]} />)
    const svg = container.querySelector('svg')!
    const fillId = svg.querySelector('linearGradient')!.getAttribute('id')!
    expect(svg.querySelector('path')!.getAttribute('fill')).toBe(`url(#${fillId})`)
    rerender(<Sparkline data={[5, 4, 6, 7]} />)
    expect(container.querySelector('svg')).not.toBeNull()
    rerender(<Sparkline data={[NaN]} />)
    expect(container.querySelector('svg')).toBeNull()
    expectNoHookErrors()
  })
})
