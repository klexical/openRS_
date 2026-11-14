import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from 'react'
import { useStore } from '../../store'
import { deleteDrives, loadDrive, renameDrive, setDriveNote, setDriveTags } from '../../lib/storage'
import { exportSessionCsv, exportSessionJson } from '../../lib/export'
import { ConfirmDialog, RowMenu, TagEditor, TextEditor, type RowAction } from './RowActions'
import { startImport } from '../importing/startImport'
import { hrefOf } from '../../lib/address'
import { fmtDuration, useUnitFormatters } from '../../lib/format'
import { boostLabel } from '../../lib/boost'
import { estimateSuffix } from '../../lib/distance'
import type { DriveRecord } from '../../lib/storage'
import { lazyChunk } from '../ui/lazyChunk'
import { groupByMonth, lapTime, listStart, matches } from './listing'

// Today's Compare, for the ticked rows (Compare is an action, not a place; ticket 05)
const CompareScreen = lazyChunk(() => import('../panels/ComparePanel').then((m) => m.ComparePanel), 'Compare')

type Editing = { id: string; kind: 'rename' | 'note' | 'tags' } | null
/** Where focus goes when the "Delete selected" dialog is cancelled (not a drive id). */
const DELETE_SELECTED = '\u0000delete-selected'

/** Home (#384 ticket 03): every stored drive, newest first, grouped by month. */
export function DrivesPage() {
  const drives = useStore((s) => s.drives)
  const loaded = useStore((s) => s.drivesLoaded)
  const notice = useStore((s) => s.listNotice)
  const newIds = useStore((s) => s.newIds)
  // Import is an action (ticket 06): the button opens the browser's file chooser, several files at a time
  const fileInput = useRef<HTMLInputElement>(null)
  const chooseFiles = () => fileInput.current?.click()
  const search = useStore((s) => s.listSearch)
  const setSearch = useStore((s) => s.setListSearch)
  const warnings = useStore((s) => s.filterWarnings)
  const laps = useStore((s) => s.filterLaps)
  const setFilters = useStore((s) => s.setFilters)
  const shown = useMemo(() => drives.filter((d) => matches(d, { search, warnings, laps })), [drives, search, warnings, laps])
  const groups = useMemo(() => groupByMonth(shown), [shown])
  const filtering = search.trim() !== '' || warnings || laps

  // ── Looking after drives from their rows (ticket 05) ──
  const compareOpen = useStore((s) => s.compareOpen)
  const setCompareOpen = useStore((s) => s.setCompareOpen)
  const setCompare = useStore((s) => s.setCompare)
  const updateDrive = useStore((s) => s.updateDrive)
  const removeDrives = useStore((s) => s.removeDrives)
  const [picked, setPicked] = useState<string[]>([])                 // ticked rows, in the order they were ticked
  const ticked = useMemo(() => picked.filter((id) => drives.some((d) => d.id === id)), [picked, drives])
  const [menuFor, setMenuFor] = useState<{ id: string; focus: 'first' | 'last' } | null>(null)
  const [editing, setEditing] = useState<Editing>(null)
  const [confirm, setConfirm] = useState<string[] | null>(null)     // the drives a delete would remove
  const [message, setMessage] = useState<string | null>(null)
  const buttons = useRef<Record<string, HTMLButtonElement | null>>({})
  const deleteSelected = useRef<HTMLButtonElement>(null)
  // focus goes back to a row's menu button after its menu, editor or dialog closes: noted here, done after the render
  const focusBack = useRef<string | null>(null)
  useEffect(() => {
    if (!focusBack.current) return
    // the "Delete selected" button after its dialog is cancelled (QA, delivery B review); else a row's menu button
    ;(focusBack.current === DELETE_SELECTED ? deleteSelected.current : buttons.current[focusBack.current])?.focus()
    focusBack.current = null
  })
  const setFocusMenuOf = (id: string) => { focusBack.current = id }
  const nameOf = (id: string) => drives.find((d) => d.id === id)?.name ?? ''
  const menuId = menuFor?.id ?? null
  const closeMenu = useCallback((back: boolean) => {
    if (back && menuId) focusBack.current = menuId
    setMenuFor(null)
  }, [menuId])
  const endEdit = (id: string) => { setEditing(null); setFocusMenuOf(id) }
  const save = async (id: string, write: Promise<import('../../lib/storage').DriveRecord | null>) => {
    try { const rec = await write; if (rec) updateDrive(rec); setMessage(null) }
    catch (err) { console.warn('[Sapphire] Could not save:', err); setMessage(`Couldn't save the change to ${nameOf(id)}. Try again.`) }
  }
  const choose = async (id: string, a: RowAction) => {
    setMessage(null)
    if (a === 'rename' || a === 'note' || a === 'tags') { setEditing({ id, kind: a }); return }
    if (a === 'delete') { setConfirm([id]); return }
    // export: today's files (lib/export), from the drive with its points
    try {
      const session = await loadDrive(id)
      if (!session) return
      if (a === 'csv') exportSessionCsv(session); else exportSessionJson(session)
    } catch (err) { console.warn('[Sapphire] Could not export:', err); setMessage(`Couldn't export ${nameOf(id)}. Try again.`) }
    buttons.current[id]?.focus()
  }
  const doDelete = async (ids: string[]) => {
    setConfirm(null)
    try {
      await deleteDrives(ids)                     // one transaction: all of them, or none (ticket 01)
      removeDrives(ids)
      setPicked((p) => p.filter((x) => !ids.includes(x)))
      setMessage(null)
    } catch (err) {
      console.warn('[Sapphire] Failed to delete drives from IndexedDB:', err)
      setMessage(ids.length === 1 ? `Couldn't delete ${nameOf(ids[0])}: nothing was deleted. Try again.`
        : "Couldn't delete the selected drives: nothing was deleted. Try again.")
    }
  }
  const tick = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]))
  const rowProps = (d: DriveRecord) => ({
    drive: d, ticked: ticked.includes(d.id), onTick: () => tick(d.id),
    menuOpen: menuFor?.id === d.id ? menuFor.focus : null,
    onMenuOpen: (focus: 'first' | 'last') => { setEditing(null); setMenuFor({ id: d.id, focus }) },
    onMenuClose: closeMenu, onChoose: (a: RowAction) => { void choose(d.id, a) },
    buttonRef: (el: HTMLButtonElement | null) => { buttons.current[d.id] = el },
    editing: editing?.id === d.id ? editing.kind : null,
    onRename: (text: string) => { const t = text.trim(); endEdit(d.id); if (t && t !== d.name) void save(d.id, renameDrive(d.id, t)) },
    onNote: (text: string) => { endEdit(d.id); if (text.trim() !== (d.note ?? '')) void save(d.id, setDriveNote(d.id, text)) },
    onToggleTag: (tag: string) => {
      const tags = d.tags ?? []
      void save(d.id, setDriveTags(d.id, tags.includes(tag) ? tags.filter((t) => t !== tag) : [...tags, tag]))
    },
    onEndEdit: () => endEdit(d.id),
  })

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
      <input ref={fileInput} id="file-input" type="file" multiple accept=".zip,application/zip" hidden aria-label="Import files"
        onChange={(e) => { const files = [...(e.target.files ?? [])]; e.target.value = ''; void startImport(files) }} />
      {notice && <p role="status" className="a-notice">{notice}</p>}
      {message && <p role="alert" className="a-notice">{message}</p>}
      {confirm && (
        <ConfirmDialog title={confirm.length === 1 ? `Delete ${nameOf(confirm[0])}?` : `Delete ${confirm.length} drives?`}
          confirm="Delete" onCancel={() => { setFocusMenuOf(confirm.length === 1 ? confirm[0] : DELETE_SELECTED); setConfirm(null) }}
          onConfirm={() => void doDelete(confirm)}>
          {confirm.length > 1 && <ul className="a-confirm-list">{confirm.map((id) => <li key={id}>{nameOf(id)}</li>)}</ul>}
          <p>{confirm.length === 1 ? 'This removes the drive, its points and its log' : 'This removes these drives, their points and their logs'} from
            this browser. It cannot be undone.</p>
        </ConfirmDialog>
      )}
      {compareOpen ? (
        <div>
          <div className="a-toolbar"><button type="button" className="a-btn" onClick={() => setCompareOpen(false)}>Back to the list</button></div>
          <div className="old-view"><CompareScreen /></div>
        </div>
      ) : loaded && drives.length === 0 ? (
        <div className="a-empty">
          <p>No drives stored in this browser yet.</p>
          <p>The ZIP comes from the openRS_ app on your phone: export a drive, or share a diagnostic snapshot, and import that file here.</p>
          <button type="button" className="a-btn primary" onClick={chooseFiles}>Import</button>
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
            <button type="button" className="a-btn primary" onClick={chooseFiles}>Import</button>
          </div>
          {ticked.length > 0 && (
            <div className="a-toolbar a-selection" role="group" aria-label="Ticked drives">
              <span>{plural(ticked.length, 'drive')} ticked</span>
              {ticked.length >= 2 && (
                <>
                  <button ref={deleteSelected} type="button" className="a-btn danger" onClick={() => setConfirm(ticked)}>Delete selected ({ticked.length})</button>
                  <button type="button" className="a-btn" disabled={ticked.length > 4} aria-describedby={ticked.length > 4 ? 'compare-limit' : undefined}
                    onClick={() => { setCompare(ticked); setCompareOpen(true) }}>Compare ({ticked.length})</button>
                  {ticked.length > 4 && <span id="compare-limit" className="a-meta">Compare takes two to four drives.</span>}
                </>
              )}
              <button type="button" className="a-btn" onClick={() => setPicked([])}>Clear the ticks</button>
            </div>
          )}
          {shown.length === 0 && (
            <div className="a-empty">
              <p>No drives match this search.</p>
              <button type="button" className="a-btn" onClick={() => { setSearch(''); setFilters({ warnings: false, laps: false }) }}>Clear the search and filters</button>
            </div>
          )}
          {groups.map((g) => (
            <section key={g.key} className="a-month" aria-labelledby={`month-${g.key}`}>
              <h2 id={`month-${g.key}`}>{g.label}</h2>
              <ul className="a-rows" role="list">{g.drives.map((d) => <DriveRow key={d.id} {...rowProps(d)} isNew={newIds.includes(d.id)} />)}</ul>
            </section>
          ))}
        </>
      ) : null}
    </section>
  )
}

interface RowProps {
  drive: DriveRecord; ticked: boolean; onTick: () => void
  /** Stored by the last import (ticket 06): marked "New". */
  isNew?: boolean
  menuOpen: 'first' | 'last' | null; onMenuOpen: (focus: 'first' | 'last') => void; onMenuClose: (back: boolean) => void
  onChoose: (a: RowAction) => void; buttonRef: (el: HTMLButtonElement | null) => void
  editing: 'rename' | 'note' | 'tags' | null
  onRename: (text: string) => void; onNote: (text: string) => void; onToggleTag: (tag: string) => void; onEndEdit: () => void
}

function DriveRow(p: RowProps) {
  const d = p.drive
  const fmt = useUnitFormatters()
  // right-click opens the row's menu with a mouse (or the keyboard's menu key); a touch is left to the browser, so on a
  // phone the visible menu button is the one way in (owner, 2026-11-14: no hand-built long press). The event's own
  // pointer type decides where the browser states one (Chromium: a PointerEvent); elsewhere, a touch still down
  // (a long press) does. A touch that has ended no longer counts, so the menu key works after it (QA N1, ticket 06).
  const touchDown = useRef(false)
  const onPointerDown = (e: ReactPointerEvent) => { touchDown.current = e.pointerType === 'touch' || e.pointerType === 'pen' }
  const onPointerEnd = () => { touchDown.current = false }
  const onContextMenu = (e: ReactMouseEvent) => {
    const stated = (e.nativeEvent as { pointerType?: string }).pointerType
    if (stated === 'touch' || stated === 'pen' || (!stated && touchDown.current)) return
    if ((e.target as Element).closest('input, textarea, .a-editor, .a-menu')) return
    e.preventDefault()
    p.onMenuOpen('first')
  }
  const when = new Date(listStart(d))
  const s = d.summary
  const boostName = boostLabel(s?.peakBoostSource) === 'TIP' ? 'Peak TIP' : 'Peak boost'
  const dash = '—'
  return (
    <li className={`a-row${d.hasDrive ? '' : ' is-session'}${d.hasWarnings ? ' is-warning' : ''}${p.ticked ? ' is-ticked' : ''}`}
      onPointerDown={onPointerDown} onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd} onContextMenu={onContextMenu}>
      <div className="a-rowhead">
        <input type="checkbox" className="a-tick" aria-label={`Select ${d.name}`} checked={p.ticked} onChange={p.onTick} />
        <div className="a-date">
          <span className="num">{when.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}</span>{' '}
          <span className="a-time num">{when.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
        </div>
      </div>
      <div>
        <a className="a-name" href={hrefOf({ place: 'drive', id: d.id })}>{d.name}</a>
        <Markers drive={d} isNew={p.isNew} />
        {(d.tags ?? []).length > 0 && <span className="a-tags">{d.tags.map((t) => <span key={t} className="a-tag">{t}</span>)}</span>}
        {d.note && <p className="a-note">{d.note}</p>}
      </div>
      <dl className="a-facts">
        <div><dt>Duration</dt><dd className="num">{s ? fmtDuration(s.durationMs) : dash}</dd></div>
        <div><dt>Distance</dt><dd className="num">{s ? `${fmt.distance(s.distanceKm)}${estimateSuffix(s)}` : dash}</dd></div>
        <div><dt>Top speed</dt><dd className="num">{s ? fmt.speed(s.peakSpeedKph) : dash}</dd></div>
        <div><dt>{boostName}</dt><dd className="num">{s && s.peakBoostPsi !== null ? fmt.boost(s.peakBoostPsi) : dash}</dd></div>
      </dl>
      <RowMenu menuId={`menu-${d.id}`} name={d.name} hasDrive={d.hasDrive} open={p.menuOpen} onOpen={p.onMenuOpen}
        onClose={p.onMenuClose} onChoose={p.onChoose} buttonRef={p.buttonRef} />
      {p.editing === 'rename' && <TextEditor label="Name" initial={d.name} onSave={p.onRename} onCancel={p.onEndEdit} />}
      {p.editing === 'note' && <TextEditor label="Note" multiline initial={d.note ?? ''} onSave={p.onNote} onCancel={p.onEndEdit} />}
      {p.editing === 'tags' && <TagEditor tags={d.tags ?? []} onToggle={p.onToggleTag} onDone={p.onEndEdit} />}
    </li>
  )
}

/** The row's markers (#384 ticket 04): each a word with a shape, never colour alone. */
function Markers({ drive: d, isNew }: { drive: DriveRecord; isNew?: boolean }) {
  const best = typeof d.bestLapMs === 'number' ? ` · best ${lapTime(d.bestLapMs)}` : ''
  if (!d.hasWarnings && !d.hasLaps && d.hasDrive && !isNew) return null
  return (
    <span className="a-markers">
      {isNew && <span className="a-marker new"><span aria-hidden="true">●</span> New</span>}
      {d.hasWarnings && <span className="a-marker warn"><span aria-hidden="true">▲</span> Warnings</span>}
      {d.hasLaps && <span className="a-marker"><span aria-hidden="true">◷</span> Laps{best}</span>}
      {!d.hasDrive && <span className="a-marker"><span aria-hidden="true">◇</span> Session only</span>}
    </span>
  )
}

function plural(n: number, one: string, many = `${one}s`) { return `${n} ${n === 1 ? one : many}` }
