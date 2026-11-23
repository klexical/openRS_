import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { useStore } from '../../store'
import type { DiagnosticData, Session } from '../../types/session'
import { DiagnosticsPanel } from './DiagnosticsPanel'
import { storeWithSessions } from '../../test/storeState'


// #382: every event type the app writes gets its own group colour on the Diagnostics panel, whatever its letter
// case; an unknown type gets the neutral style. Invented events only. First written by QA as the reproduction.

// The event types the app writes, listed from the Android source on staging 9ced4a6 (android/app/src/main):
// literal types of DiagnosticLogger.event("…"), SessionEvent(…, "…") (SESSION, SLCAN), the DiagnosticLogger.EVENT_*
// constants passed to event() (LINK_*, FGS_LOCATION) and ObserverNote.EVENT (NOTE, written with eventAt). Not typed
// from memory; a new type on the Android side needs a line here and in lib/eventTypes.ts.
const APP_EVENT_TYPES = ['AUTO_DTC_SCAN', 'BATCH_EXPORT_ERROR', 'BLE_WRITE', 'BOOST_SOURCE', 'DEMO', 'DEVICE_STATUS',
  'DM_CMD', 'DRIVE_EXPORT_ERROR', 'DTC', 'ESC_CMD', 'EXPORT_ERROR', 'FGS_LOCATION', 'FIRMWARE', 'FRAMING', 'LAP',
  'LINK_PREV', 'LINK_PREV_EXIT', 'LINK_START_REFUSED', 'LINK_STOP', 'LINK_STOP_IDLE', 'LINK_TASK_REMOVED', 'NOTE',
  'NRC_RECOVER', 'NRC_SUPPRESS', 'NRC_UNATTRIBUTED', 'PROBE', 'REC', 'REC_GAP', 'SESSION', 'SHARE', 'SLCAN', 'STATE']

/** The neutral style's text colour: the palette's text colour (frost), a CSS variable since #384 ticket 08. */
const NEUTRAL = 'var(--color-frost)'

function show(types: string[]): HTMLElement {
  return showEvents(types.map((type) => ({ type, message: `invented ${type}` })))
}

function showEvents(events: { type: string; message: string }[]): HTMLElement {
  const diagnostics: DiagnosticData = {
    canInventory: [], fpsTimeline: [], decodeTrace: [], probeResults: [], dtcResults: [],
    sessionEvents: events.map((e, i) => ({ ts: 1000 * (i + 1), ...e })),
  }
  const session = { id: 'a', name: 'A', importedAt: 1, tags: [], trip: null, diagnostics,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } } as unknown as Session
  useStore.setState(storeWithSessions([session], 'a'))
  return render(<DiagnosticsPanel />).container
}

/** Each invented event's badge (Session Events table, type column), keyed by its type. */
function badges(container: HTMLElement): Record<string, HTMLElement> {
  const out: Record<string, HTMLElement> = {}
  for (const tr of container.querySelectorAll('tr')) {
    const msg = tr.cells[2]?.textContent ?? ''
    const span = tr.cells[1]?.querySelector('span')
    if (span && msg.startsWith('invented ')) out[msg.slice('invented '.length)] = span
  }
  return out
}
const colours = (b: Record<string, HTMLElement>) =>
  Object.fromEntries(Object.entries(b).map(([t, s]) => [t, getComputedStyle(s).color]))

beforeEach(() => vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('#382 Diagnostics event colours', () => {
  it('no event type the app writes falls to the neutral style', () => {
    const c = colours(badges(show(APP_EVENT_TYPES)))
    expect(Object.keys(c)).toHaveLength(APP_EVENT_TYPES.length)
    expect(Object.entries(c).filter(([, col]) => col === NEUTRAL).map(([t]) => t)).toEqual([])
  })

  it('each badge names its group in words, so the colour is not the only cue', () => {
    const b = badges(show(APP_EVENT_TYPES))
    const groups = Object.fromEntries(Object.entries(b).map(([t, s]) => [t, s.getAttribute('title')]))
    expect(Object.values(groups).every((g) => typeof g === 'string' && g.length > 0)).toBe(true)
    expect(groups.EXPORT_ERROR).toBe(groups.LINK_START_REFUSED)
    expect(groups.SESSION).not.toBe(groups.EXPORT_ERROR)
  })

  it('a lower-case spelling of a known type gets the same colour as the upper-case one', () => {
    const c = colours(badges(show(['FIRMWARE', 'firmware', 'SESSION', 'session', 'DM_CMD', 'dm_cmd', 'Rec_Gap', 'REC_GAP'])))
    expect([c.firmware, c.session, c.dm_cmd, c.Rec_Gap]).toEqual([c.FIRMWARE, c.SESSION, c.DM_CMD, c.REC_GAP])
    expect(c.FIRMWARE).not.toBe(NEUTRAL)
  })

  it('a recording gap shows the warning colour, never the "good" colour; plain recording events neither', () => {
    // REC_GAP marks lost recording time (a pause, a disconnect, a missing fix); the meaning colours keep their meaning
    const c = colours(badges(show(['REC_GAP', 'REC', 'LAP'])))
    const OK = 'rgb(0, 255, 136)', WARN = 'rgb(255, 204, 0)'
    expect(c.REC_GAP).toBe(WARN)
    expect([c.REC, c.LAP]).not.toContain(OK)
    expect([c.REC, c.LAP]).not.toContain(WARN)
  })

  it('an unknown type renders with the neutral style and does not throw', () => {
    const b = badges(show(['SOMETHING_NEW', 'FIRMWARE']))
    expect(getComputedStyle(b.SOMETHING_NEW).color).toBe(NEUTRAL)
    expect(b.SOMETHING_NEW.textContent).toBe('SOMETHING_NEW')
    expect(getComputedStyle(b.FIRMWARE).color).not.toBe(NEUTRAL)
  })
})

describe('#393 a REC_GAP badge names its reason on hover', () => {
  // The four message forms the app writes (RecordingGapMonitor on staging); invented drive number and durations
  const FORMS = {
    disconnected: 'drive 3: DISCONNECTED gap 12.0 s (adapter disconnected — samples skipped)',
    paused: 'drive 3: PAUSED gap 30.0 s (recording paused)',
    noLocation: 'drive 3: NO_LOCATION gap 8.0 s (no location update while connected — telemetry not recorded)',
    notice: 'drive 3: no location update for 6 s while connected — drive points paused until location returns',
  }

  it('only the adapter-disconnected gap is titled "Recording gap"; all four keep the warning colour', () => {
    const container = showEvents(Object.values(FORMS).map((message) => ({ type: 'REC_GAP', message })))
    const byMessage = new Map([...container.querySelectorAll('tr')].flatMap((tr) => {
      const span = tr.cells[1]?.querySelector('span')
      return span ? [[tr.cells[2]?.textContent ?? '', span] as const] : []
    }))
    const title = (m: string) => byMessage.get(m)?.getAttribute('title')
    expect(title(FORMS.disconnected)).toBe('Recording gap')
    expect(title(FORMS.paused)).toBe('Recording paused')
    expect(title(FORMS.noLocation)).toBe('No position update')
    expect(title(FORMS.notice)).toBe('No position update')
    const WARN = 'rgb(255, 204, 0)'
    expect(Object.values(FORMS).map((m) => getComputedStyle(byMessage.get(m)!).color)).toEqual([WARN, WARN, WARN, WARN])
  })
})
