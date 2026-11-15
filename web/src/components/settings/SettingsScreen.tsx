// Settings at `#/settings` (#384 ticket 07; the owner's pick, mock-up B): panels with a section index (a row of chips
// on a phone, a rail on a desktop), your drives first as a summary. Look A. The Palette sits beside the paint colour
// (ticket 08); About holds "What's new" (ticket 09).
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useStore } from '../../store'
import { useSettings, type PaletteChoice, type ThemeId } from '../../store/settings'
import { rsThemes } from '../../styles/tokens'
import { aboutSize, keepStatus, type KeepStatus } from '../../lib/keepData'
import type { UnitSystem, BoostUnit, TirePressUnit } from '../../lib/units'
import { BackupButton, RestoreInput } from '../ui/LazyBackupControls'
import { ClearAllDialog } from './ClearAllDialog'

export const MAP_SENTENCE = 'The map background comes from a public map server (OpenStreetMap). It sees the area shown, which for a drive is where it went, and your internet address. No readings from a drive are sent.'

const PAINTS: { id: ThemeId; label: string }[] = [
  { id: 'cyan', label: 'Nitrous Blue' }, { id: 'red', label: 'Race Red' }, { id: 'orange', label: 'Deep Orange' },
  { id: 'grey', label: 'Stealth Grey' }, { id: 'black', label: 'Shadow Black' }, { id: 'white', label: 'Frozen White' },
]
const SECTIONS = [['drives', 'Your drives'], ['units', 'Units'], ['paint', 'Palette and paint colour'], ['map', 'The map'], ['about', 'About']] as const

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`
/** Below this (0.05 MB) the space is "almost none": rounding it up to a figure would say more than the browser does. */
const ALMOST_NONE = 50_000

export function SettingsScreen() {
  return (
    <section className="a-page a-settings" aria-labelledby="settings-title">
      <h1 id="settings-title">Settings</h1>
      <div className="a-settings-grid">
        <nav className="a-settings-index" aria-label="Settings sections">
          {SECTIONS.map(([id, label]) => (
            <button key={id} type="button" className="a-btn a-chip-link"
              onClick={() => document.getElementById(`settings-${id}`)?.scrollIntoView({ block: 'start' })}>{label}</button>
          ))}
        </nav>
        <div className="a-panels">
          <DrivesPanel />
          <Panel id="units" title="Units"><Units /></Panel>
          <Panel id="paint" title="Palette and paint colour"><Palette /><Paint /></Panel>
          <Panel id="map" title="The map"><p>{MAP_SENTENCE}</p></Panel>
          <Panel id="about" title="About"><About /></Panel>
          <ResetLine />
        </div>
      </div>
    </section>
  )
}

function Panel({ id, title, children, className = '' }: { id: string; title: string; children: ReactNode; className?: string }) {
  return (
    <section id={`settings-${id}`} className={`a-panel ${className}`} aria-labelledby={`settings-${id}-title`}>
      <h2 id={`settings-${id}-title`}>{title}</h2>
      {children}
    </section>
  )
}

/** Your drives: what is stored, whether the browser keeps it, about how much space; backup, restore; Clear all under them. */
function DrivesPanel() {
  const drives = useStore((s) => s.drives)
  const loaded = useStore((s) => s.drivesLoaded)
  const [status, setStatus] = useState<KeepStatus | null>(null)
  const [clearing, setClearing] = useState(false)
  const [cleared, setCleared] = useState(false)
  const clearButton = useRef<HTMLButtonElement>(null)
  const clearedLine = useRef<HTMLParagraphElement>(null)
  const read = () => { void keepStatus().then(setStatus) }
  useEffect(read, [])                                            // each time Settings opens: read, never ask
  useEffect(() => { if (cleared) clearedLine.current?.focus() }, [cleared])

  const sessions = drives.filter((d) => !d.hasDrive).length
  const stored = drives.length - sessions
  const what = sessions > 0 ? `${plural(stored, 'drive')} and ${plural(sessions, 'session-only entry', 'session-only entries')}` : plural(stored, 'drive')
  // focus goes back to Clear all; after a clear (nothing left, the button off) to the line that says so
  const close = () => { setClearing(false); clearButton.current?.focus() }

  return (
    <Panel id="drives" title="Your drives" className="a-panel-lead">
      <dl className="a-summary">
        <div><dt>Stored</dt><dd>{loaded ? what : '…'}</dd></div>
        <div><dt>Space</dt><dd>{status?.usage == null ? '—' : status.usage < ALMOST_NONE ? 'almost none' : `about ${aboutSize(status.usage)}`}</dd></div>
        <div><dt>Kept</dt><dd>{status === null ? '…' : status.kept === null ? 'Not said' : status.kept ? 'Kept' : 'Not kept'}</dd></div>
      </dl>
      {status && <KeepWords status={status} />}
      <div className="a-buttons">
        <BackupButton label="Back up all drives" buttonClass="a-btn primary" lookA />
        <RestoreInput buttonClass="a-btn" lookA />
      </div>
      <div className="a-under">
        <button ref={clearButton} type="button" className="a-btn danger" disabled={loaded && drives.length === 0}
          onClick={() => { setCleared(false); setClearing(true) }}>Clear all</button>
        {cleared && <p ref={clearedLine} tabIndex={-1} role="status" className="a-meta">Every drive and session was removed from this browser.</p>}
      </div>
      {clearing && <ClearAllDialog what={loaded ? what : null} onClose={close} onCleared={() => { setCleared(true); setClearing(false); read() }} />}
    </Panel>
  )
}

/** The browser's answers, in words (ticket 07): "no" is said as no; the space is the browser's estimate, "about". */
function KeepWords({ status }: { status: KeepStatus }) {
  const { kept, usage, quota } = status
  if (kept === null && usage === null) return <p>This browser does not say whether it keeps Sapphire's drives, or how much space they use.</p>
  return <>
    <p>{kept === null ? 'This browser does not say whether it keeps Sapphire\'s drives.'
      : kept ? 'Kept: this browser keeps Sapphire\'s drives and will not clear them to free space.'
        : 'Not kept: this browser may clear Sapphire\'s drives when space runs low. A backup keeps a copy.'}</p>
    <p className="a-meta">{usage === null ? 'This browser does not say how much space they use.'
      : `${usage < ALMOST_NONE ? 'Almost no space used' : `About ${aboutSize(usage)} used`}${quota ? `, of about ${aboutSize(quota)} this browser allows` : ''}.`}</p>
  </>
}

function Choice<T extends string>({ label, options, value, onChange }: {
  label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void
}) {
  return (
    <div className="a-field" role="group" aria-label={label}>
      <span className="a-label">{label}</span>
      <span className="a-buttons">
        {options.map((o) => (
          <button key={o.value} type="button" className="a-btn" aria-pressed={value === o.value} onClick={() => onChange(o.value)}>{o.label}</button>
        ))}
      </span>
    </div>
  )
}
const PRESSURE: { value: BoostUnit & TirePressUnit; label: string }[] = [{ value: 'PSI', label: 'PSI' }, { value: 'bar', label: 'bar' }, { value: 'kPa', label: 'kPa' }]

function Units() {
  const { unitSystem, setUnitSystem, boostUnit, setBoostUnit, tirePressUnit, setTirePressUnit } = useSettings()
  return <>
    <Choice<UnitSystem> label="Speed and distance" value={unitSystem} onChange={setUnitSystem}
      options={[{ value: 'metric', label: 'Metric (km/h, km)' }, { value: 'imperial', label: 'Imperial (mph, mi)' }]} />
    <Choice label="Boost" value={boostUnit} onChange={setBoostUnit} options={PRESSURE} />
    <Choice label="Tyre pressure" value={tirePressUnit} onChange={setTirePressUnit} options={PRESSURE} />
  </>
}

const PALETTES: { value: PaletteChoice; label: string }[] = [
  { value: 'match', label: 'Match device' }, { value: 'night', label: 'Night' }, { value: 'day', label: 'Day' }, { value: 'ultra', label: 'Ultra' },
]
/**
 * The palette (#384 ticket 08; owner, 2026-11-15): the set of colours Sapphire is drawn in. Match device picks Night or
 * Day from the device's setting; Ultra is chosen here only. Never named "Auto", "Theme", "Mode" or "Look".
 */
function Palette() {
  const { palette, setPalette } = useSettings()
  return (
    <div className="a-field">
      <span className="a-label" id="palette-label">Palette</span>
      <div className="a-buttons" role="radiogroup" aria-labelledby="palette-label">
        {PALETTES.map((p) => (
          <button key={p.value} type="button" role="radio" aria-checked={palette === p.value} className="a-btn a-choice"
            onClick={() => setPalette(p.value)}>{p.label}</button>
        ))}
      </div>
      <p className="a-meta">Match device picks Night or Day from this device's setting. Ultra is pure black, for OLED screens.</p>
    </div>
  )
}

/** The paint colour: the RS paint the accent comes from, on buttons, tabs and highlights only (owner, 2026-11-15). */
function Paint() {
  const { themeId, setThemeId } = useSettings()
  return <div className="a-field">
    <span className="a-label">Paint colour</span>
    <div className="a-buttons" role="radiogroup" aria-label="Paint colour">
      {PAINTS.map((p) => (
        <button key={p.id} type="button" role="radio" aria-checked={themeId === p.id} className="a-btn a-paint" onClick={() => setThemeId(p.id)}>
          <span className="a-dot" style={{ background: rsThemes[p.id] }} aria-hidden="true" />{p.label}</button>
      ))}
    </div>
    <p className="a-meta">On buttons, tabs and highlights only; never on data or the route.</p>
  </div>
}

/** About (#384 ticket 09): "What's new" is always reachable here. */
function About() {
  const open = () => useStore.getState().setWhatsNewOpen(true)
  return <>
    <p>Sapphire is openRS_'s web dashboard: it reads the drives the app exports, in this browser only.</p>
    <p><button type="button" className="a-btn" onClick={open}>What's new</button></p>
  </>
}

function ResetLine() {
  const resetDefaults = useSettings((s) => s.resetDefaults)
  return <p className="a-reset"><button type="button" className="a-btn" onClick={resetDefaults}>Reset units, palette and paint colour</button></p>
}
