import { useMemo } from 'react'
import { useStore } from '../../store'
import { hrefOf } from '../../lib/address'
import { fmtDuration, useUnitFormatters } from '../../lib/format'
import { boostLabel } from '../../lib/boost'
import { estimateSuffix } from '../../lib/distance'
import type { DriveRecord } from '../../lib/storage'
import { lazyChunk } from '../ui/lazyChunk'
import { groupByMonth, listStart } from './listing'

// Today's import screen, opened from the list's Import button (Import is an action, not a place; ticket 06 replaces it)
const ImportScreen = lazyChunk(() => import('../panels/ImportPanel').then((m) => m.ImportPanel), 'the import screen')

/** Home (#384 ticket 03): every stored drive, newest first, grouped by month. */
export function DrivesPage() {
  const drives = useStore((s) => s.drives)
  const loaded = useStore((s) => s.drivesLoaded)
  const notice = useStore((s) => s.listNotice)
  const importOpen = useStore((s) => s.importOpen)
  const setImportOpen = useStore((s) => s.setImportOpen)
  const groups = useMemo(() => groupByMonth(drives), [drives])

  const counts = drives.reduce((n, d) => ({ drives: n.drives + (d.hasDrive ? 1 : 0), sessions: n.sessions + (d.hasDrive ? 0 : 1) }), { drives: 0, sessions: 0 })
  return (
    <section className="a-page" aria-labelledby="drives-title" aria-busy={!loaded}>
      <header>
        <h1 id="drives-title">Drives</h1>
        {loaded && drives.length > 0 && (
          <p className="a-meta">
            {plural(counts.drives, 'drive')}{counts.sessions > 0 ? ` and ${plural(counts.sessions, 'session-only entry', 'session-only entries')}` : ''} · newest first
          </p>
        )}
      </header>
      {notice && <p role="status" className="a-notice">{notice}</p>}
      {importOpen ? (
        <div>
          <div className="a-toolbar"><button type="button" className="a-btn" onClick={() => setImportOpen(false)}>Back to the list</button></div>
          <ImportScreen />
        </div>
      ) : loaded && drives.length === 0 ? (
        <div className="a-empty">
          <p>No drives stored in this browser yet.</p>
          <p>The ZIP comes from the openRS_ app on your phone: export a drive, or share a diagnostic snapshot, and import that file here.</p>
          <button type="button" className="a-btn primary" onClick={() => setImportOpen(true)}>Import</button>
        </div>
      ) : loaded ? (
        <>
          <div className="a-toolbar"><span /><button type="button" className="a-btn primary" onClick={() => setImportOpen(true)}>Import</button></div>
          {groups.map((g) => (
            <section key={g.key} className="a-month" aria-labelledby={`month-${g.key}`}>
              <h2 id={`month-${g.key}`}>{g.label}</h2>
              <ul className="a-rows">{g.drives.map((d) => <DriveRow key={d.id} drive={d} />)}</ul>
            </section>
          ))}
        </>
      ) : null}
    </section>
  )
}

function DriveRow({ drive: d }: { drive: DriveRecord }) {
  const fmt = useUnitFormatters()
  const when = new Date(listStart(d))
  const s = d.summary
  const boostName = boostLabel(s?.peakBoostSource) === 'TIP' ? 'Peak TIP' : 'Peak boost'
  const dash = '—'
  return (
    <li className={`a-row${d.hasDrive ? '' : ' is-session'}`}>
      <div className="a-date">
        <span className="num">{when.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</span>{' '}
        <span className="a-time num">{when.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
      </div>
      <div><a className="a-name" href={hrefOf({ place: 'drive', id: d.id })}>{d.name}</a></div>
      <dl className="a-facts">
        <div><dt>Duration</dt><dd className="num">{s ? fmtDuration(s.durationMs) : dash}</dd></div>
        <div><dt>Distance</dt><dd className="num">{s ? `${fmt.distance(s.distanceKm)}${estimateSuffix(s)}` : dash}</dd></div>
        <div><dt>Top speed</dt><dd className="num">{s ? fmt.speed(s.peakSpeedKph) : dash}</dd></div>
        <div><dt>{boostName}</dt><dd className="num">{s && s.peakBoostPsi !== null ? fmt.boost(s.peakBoostPsi) : dash}</dd></div>
      </dl>
    </li>
  )
}

function plural(n: number, one: string, many = `${one}s`) { return `${n} ${n === 1 ? one : many}` }
