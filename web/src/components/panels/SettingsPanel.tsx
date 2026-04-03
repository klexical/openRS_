import { useSettings, type ThemeId } from '../../store/settings'
import { useStore } from '../../store'
import { clearAllSessions } from '../../lib/db'
import { rsThemes } from '../../styles/tokens'
import { SectionLabel } from '../ui/SectionLabel'
import type { UnitSystem, BoostUnit, TirePressUnit } from '../../lib/units'
import { useState } from 'react'

const themeEntries: { id: ThemeId; label: string; color: string }[] = [
  { id: 'cyan', label: 'Nitrous Blue', color: rsThemes.cyan },
  { id: 'red', label: 'Race Red', color: rsThemes.red },
  { id: 'orange', label: 'Deep Orange', color: rsThemes.orange },
  { id: 'grey', label: 'Stealth Grey', color: rsThemes.grey },
  { id: 'black', label: 'Shadow Black', color: rsThemes.black },
  { id: 'white', label: 'Frozen White', color: rsThemes.white },
]

export function SettingsPanel() {
  const {
    unitSystem, setUnitSystem,
    boostUnit, setBoostUnit,
    tirePressUnit, setTirePressUnit,
    themeId, setThemeId,
    resetDefaults,
  } = useSettings()

  const sessions = useStore((s) => s.sessions)
  const setSessions = useStore((s) => s.setSessions)

  const [confirmClear, setConfirmClear] = useState(false)

  const handleClearAll = async () => {
    await clearAllSessions()
    setSessions([])
    setConfirmClear(false)
  }

  return (
    <div className="max-w-2xl mx-auto space-y-2">
      {/* ── Units ── */}
      <SectionLabel>Units</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 p-4 space-y-4">
        <SettingRow label="Speed / Distance">
          <SegmentedPicker
            options={[
              { value: 'metric', label: 'Metric (KPH / km)' },
              { value: 'imperial', label: 'Imperial (MPH / mi)' },
            ]}
            value={unitSystem}
            onChange={(v) => setUnitSystem(v as UnitSystem)}
          />
        </SettingRow>

        <SettingRow label="Boost Pressure">
          <SegmentedPicker
            options={[
              { value: 'PSI', label: 'PSI' },
              { value: 'bar', label: 'bar' },
              { value: 'kPa', label: 'kPa' },
            ]}
            value={boostUnit}
            onChange={(v) => setBoostUnit(v as BoostUnit)}
          />
        </SettingRow>

        <SettingRow label="Tire Pressure">
          <SegmentedPicker
            options={[
              { value: 'PSI', label: 'PSI' },
              { value: 'bar', label: 'bar' },
              { value: 'kPa', label: 'kPa' },
            ]}
            value={tirePressUnit}
            onChange={(v) => setTirePressUnit(v as TirePressUnit)}
          />
        </SettingRow>
      </div>

      {/* ── Theme ── */}
      <SectionLabel>Accent Color</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 p-4">
        <div className="grid grid-cols-3 gap-2">
          {themeEntries.map((t) => (
            <button
              key={t.id}
              onClick={() => setThemeId(t.id)}
              className={`
                flex items-center gap-3 px-3 py-2.5 rounded-md border transition-all
                ${themeId === t.id
                  ? 'border-frost/30 bg-surf3'
                  : 'border-brd hover:border-dim bg-surf'
                }
              `}
            >
              <span
                className="w-4 h-4 rounded-full shrink-0 ring-1 ring-white/10"
                style={{ backgroundColor: t.color }}
              />
              <span className="text-xs font-mono text-frost">{t.label}</span>
              {themeId === t.id && (
                <span className="text-[10px] font-mono text-accent ml-auto">Active</span>
              )}
            </button>
          ))}
        </div>
        <p className="text-[10px] font-mono text-dim mt-3">
          Theme accent colors are cosmetic — chart colors remain fixed for readability.
        </p>
      </div>

      {/* ── Data Management ── */}
      <SectionLabel>Data Management</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 p-4 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <span className="text-sm font-mono text-frost">Stored Sessions</span>
            <span className="text-xs font-mono text-dim ml-2">{sessions.length} session{sessions.length !== 1 ? 's' : ''} in IndexedDB</span>
          </div>
        </div>

        {!confirmClear ? (
          <button
            onClick={() => setConfirmClear(true)}
            disabled={sessions.length === 0}
            className="px-3 py-2 rounded-md text-xs font-mono uppercase tracking-wider
                       border transition-colors
                       text-orange border-orange/20 hover:bg-orange/10
                       disabled:opacity-30 disabled:cursor-not-allowed"
          >
            Clear All Sessions
          </button>
        ) : (
          <div className="flex items-center gap-2">
            <span className="text-xs font-mono text-orange">Delete all {sessions.length} sessions?</span>
            <button
              onClick={handleClearAll}
              className="px-3 py-1.5 rounded-md text-xs font-mono uppercase tracking-wider
                         bg-orange/15 text-orange border border-orange/30 hover:bg-orange/25 transition-colors"
            >
              Confirm
            </button>
            <button
              onClick={() => setConfirmClear(false)}
              className="px-3 py-1.5 rounded-md text-xs font-mono uppercase tracking-wider
                         text-dim border border-brd hover:text-frost transition-colors"
            >
              Cancel
            </button>
          </div>
        )}
      </div>

      {/* ── Reset ── */}
      <div className="pt-4">
        <button
          onClick={resetDefaults}
          className="px-3 py-2 rounded-md text-xs font-mono uppercase tracking-wider
                     text-dim border border-brd hover:text-frost hover:border-dim transition-colors"
        >
          Reset Settings to Defaults
        </button>
      </div>
    </div>
  )
}

/** Label + control row. */
function SettingRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-xs font-mono text-frost uppercase tracking-wider shrink-0">{label}</span>
      {children}
    </div>
  )
}

/** Segmented picker matching the Android app's SegmentedPicker aesthetic. */
function SegmentedPicker({ options, value, onChange }: {
  options: { value: string; label: string }[]
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="flex rounded-md border border-brd overflow-hidden">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`
            px-3 py-1.5 text-[11px] font-mono transition-colors
            ${value === opt.value
              ? 'bg-accent/15 text-accent'
              : 'bg-surf3 text-dim hover:text-frost'
            }
            ${opt.value !== options[0].value ? 'border-l border-brd' : ''}
          `}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
