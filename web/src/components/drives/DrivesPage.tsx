import { useMemo } from 'react'
import { useStore } from '../../store'
import { hrefOf } from '../../lib/address'
import { fmtDuration, useUnitFormatters } from '../../lib/format'
import { boostLabel } from '../../lib/boost'
import { estimateSuffix } from '../../lib/distance'
import type { DriveRecord } from '../../lib/storage'
import { lazyChunk } from '../ui/lazyChunk'
import { groupByMonth, lapTime, listStart, matches } from './listing'

// Today's import screen, opened from the list's Import button (Import is an action, not a place; ticket 06 replaces it)
const ImportScreen = lazyChunk(() => import('../panels/ImportPanel').then((m) => m.ImportPanel), 'the import screen')

/** Home (#384 ticket 03): every stored drive, newest first, grouped by month. */
export function DrivesPage() {
  const drives = useStore((s) => s.drives)
  const loaded = useStore((s) => s.drivesLoaded)
  const notice = useStore((s) => s.listNotice)
  const importOpen = useStore((s) => s.importOpen)
  const setImportOpen = useStore((s) => s.setImportOpen)
  const search = useStore((s) => s.listSearch)
  const setSearch = useStore((s) => s.setListSearch)
  const warnings = useStore((s) => s.filterWarnings)
  const laps = useStore((s) => s.filterLaps)
  const setFilters = useStore((s) => s.setFilters)
  const shown = useMemo(() => drives.filter((d) => matches(d, { search, warnings, laps })), [drives, search, warnings, laps])
  const groups = useMemo(() => groupByMonth(shown), [shown])
  const filtering = search.trim() !== '' || warnings || laps

  const counts = drives.reduce((n, d) => ({ drives: n.drives + (d.hasDrive ? 1 : 0), sessions: n.sessions + (d.hasDrive ? 0 : 1) }), { drives: 0, sessions: 0 })
  return (
    <section className="a-page" aria-labelledby="drives-title" aria-busy={!loaded}>
      <header>
        <h1 id="drives-title">Drives</h1>
        {loaded && drives.length > 0 && (
          <p className="a-meta">
            {plural(counts.drives, 'drive')}{counts.sessions > 0 ? ` and ${plural(counts.sessions, 'session-only entry', 'session-only entries')}` : ''} · newest first
            {filtering && <span role="status"> · showing {shown.length} of {drives.length}</span>}
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
          <div className="a-toolbar">
            <label className="a-search"><span className="sr">Search drives</span>
              <input type="search" value={search} placeholder="Search name, tag or date" onChange={(e) => setSearch(e.target.value)} />
            </label>
            <div className="a-filters" role="group" aria-label="Quick filters">
              {/* until knock events and critical temperatures are checked too, the filter names the two causes it covers */}
              <button type="button" className="a-btn a-chip" aria-pressed={warnings} onClick={() => setFilters({ warnings: !warnings })}>With warnings: fault codes and gaps</button>
              <button type="button" className="a-btn a-chip" aria-pressed={laps} onClick={() => setFilters({ laps: !laps })}>With laps</button>
            </div>
            <button type="button" className="a-btn primary" onClick={() => setImportOpen(true)}>Import</button>
          </div>
          {shown.length === 0 && (
            <div className="a-empty">
              <p>No drives match this search.</p>
              <button type="button" className="a-btn" onClick={() => { setSearch(''); setFilters({ warnings: false, laps: false }) }}>Clear the search and filters</button>
            </div>
          )}
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
    <li className={`a-row${d.hasDrive ? '' : ' is-session'}${d.hasWarnings ? ' is-warning' : ''}`}>
      <div className="a-date">
        <span className="num">{when.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</span>{' '}
        <span className="a-time num">{when.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
      </div>
      <div>
        <a className="a-name" href={hrefOf({ place: 'drive', id: d.id })}>{d.name}</a>
        <Markers drive={d} />
      </div>
      <dl className="a-facts">
        <div><dt>Duration</dt><dd className="num">{s ? fmtDuration(s.durationMs) : dash}</dd></div>
        <div><dt>Distance</dt><dd className="num">{s ? `${fmt.distance(s.distanceKm)}${estimateSuffix(s)}` : dash}</dd></div>
        <div><dt>Top speed</dt><dd className="num">{s ? fmt.speed(s.peakSpeedKph) : dash}</dd></div>
        <div><dt>{boostName}</dt><dd className="num">{s && s.peakBoostPsi !== null ? fmt.boost(s.peakBoostPsi) : dash}</dd></div>
      </dl>
    </li>
  )
}

/** The row's markers (#384 ticket 04): each a word with a shape, never colour alone. */
function Markers({ drive: d }: { drive: DriveRecord }) {
  const best = typeof d.bestLapMs === 'number' ? ` · best ${lapTime(d.bestLapMs)}` : ''
  if (!d.hasWarnings && !d.hasLaps && d.hasDrive) return null
  return (
    <span className="a-markers">
      {d.hasWarnings && <span className="a-marker warn"><span aria-hidden="true">▲</span> Warnings</span>}
      {d.hasLaps && <span className="a-marker"><span aria-hidden="true">◷</span> Laps{best}</span>}
      {!d.hasDrive && <span className="a-marker"><span aria-hidden="true">◇</span> Session only</span>}
    </span>
  )
}

function plural(n: number, one: string, many = `${one}s`) { return `${n} ${n === 1 ? one : many}` }
