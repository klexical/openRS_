import { useEffect } from 'react'
import { useStore, useCurrentDrive } from '../../store'
import { DRIVE_VIEWS, formatRange, go, hrefOf, parseRange, useAddress, type DriveView } from '../../lib/address'
import { clampRange, commitRangeToAddress, driveSpanMs, sameRange, setRange, type TimeRange } from '../../lib/selection'
import { notCheckedLine } from '../../lib/warningWords'
import { ErrorBoundary } from '../ErrorBoundary'
import { lazyChunk } from '../ui/lazyChunk'

const Views = lazyChunk(() => import('./DriveViews').then((m) => m.DriveViews), 'this drive')

const LABELS: Record<DriveView, string> = { dashboard: 'Dashboard', trip: 'Trip', diagnostics: 'Diagnostics' }

/**
 * One drive at `#/drive/<id>` (#384 ticket 03): its name, the way back to the list, and today's Dashboard, Trip and
 * Diagnostics views reached from the drive. The old views keep their own layout and colours (slice 3 replaces them).
 * The drive is the Drive library's Current drive (#416 ticket 1): the page and its views read it through
 * `useCurrentDrive`, and show it only once it is the drive this address names.
 * The address names the view and the time range (#416 ticket 2): the range goes into the one selection
 * (lib/selection.ts), is clamped to the drive once it is shown, and an address not written as Sapphire writes it is put
 * right in place.
 */
export function DriveFrame({ id }: { id: string }) {
  const record = useStore((s) => s.drives.find((d) => d.id === id))
  const current = useCurrentDrive()
  const mine = current.id === id                     // false for the moment between a new address and the library's read
  const open = mine ? current.session : null
  const failed = mine && current.state === 'read failed'
  const address = useAddress()
  // the address may already name another drive (or place) while this frame still shows [id]: then it is not this
  // frame's to read or to put right (a rewrite would undo the navigation)
  const ours = address.place === 'drive' && address.id === id
  const here = ours ? address : { place: 'drive' as const, id }
  const view = here.view ?? DRIVE_VIEWS[0]
  const addressed = here.range ?? null
  const range = useStore((s) => s.selection.range)
  const span = open ? driveSpanMs(open.trip?.points ?? []) : null
  const name = record?.name ?? open?.name
  const notChecked = record ? notCheckedLine(record) : null   // #384 ticket 04

  // the address as Sapphire writes it: an unknown view or a garbled range becomes the first view, the whole drive, in
  // place and without a message (the integrator's call, 2026-11-18)
  const href = hrefOf(here)
  useEffect(() => { if (ours && window.location.hash !== href) go(here, { replace: true }) })
  // the address's range is the selection's (a new drive, a reload, Back / Forward, a typed address): set when either
  // changes, so a drag in progress (the selection ahead of the address) is not put back
  const addressedText = addressed ? formatRange(addressed) : ''
  useEffect(() => { setRange(parseRange(addressedText)) }, [id, addressedText])
  // clamped to the drive once it is shown; the address follows, in place
  useEffect(() => {
    if (!open || !ours) return
    const clamped = clampRange(addressed, span)
    if (!sameRange(clamped, addressed)) go({ ...here, range: clamped }, { replace: true })
  })

  const wholeDrive = () => { setRange(null); commitRangeToAddress() }
  return (
    <div className="a-page">
      <header>
        <a className="a-crumb" href={hrefOf({ place: 'drives' })}>← Drives</a>
        <h1>{name ?? 'Opening the drive…'}</h1>
        {notChecked && <p className="a-notchecked">{notChecked}</p>}
      </header>
      {failed ? (
        <p role="alert" className="a-notice">Couldn't open this drive. Try again. <a href={hrefOf({ place: 'drives' })}>Back to the list</a></p>
      ) : (<>
      <nav className="a-views" aria-label="Views of this drive">
        {DRIVE_VIEWS.map((v) => (
          // a change of view adds a Back step and keeps the range
          <button key={v} type="button" className="a-btn" aria-pressed={view === v} onClick={() => go({ place: 'drive', id, view: v, range })}>{LABELS[v]}</button>
        ))}
      </nav>
      {open && (
        <div className="a-range">
          <p role="status" aria-label="Range">{rangeText(range, span)}</p>
          {range && <button type="button" className="a-btn" onClick={wholeDrive}>Whole drive</button>}
        </div>
      )}
      <div className="old-view">
        {mine ? (
          <ErrorBoundary key={view} fallbackLabel={`Error loading the ${view} view`}>
            <Views view={view} />
          </ErrorBoundary>
        ) : <div aria-busy="true" />}
      </div>
      </>)}
    </div>
  )
}

/** "Range: Whole drive", or "Range: 2:05 – 7:40 of 20:00" (pending the owner's wording, Q151). */
function rangeText(range: TimeRange | null, span: number | null): string {
  if (!range || span === null) return 'Range: Whole drive'
  return `Range: ${clock(range.from)} – ${clock(range.to)} of ${clock(span)}`
}
/** A time from the drive's start: m:ss, or h:mm:ss from an hour. */
function clock(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}
