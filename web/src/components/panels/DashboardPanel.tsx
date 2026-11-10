import { useMemo } from 'react'
import { boostLabel, pressureDecimals, pressureSeries } from '../../lib/boost'
import { convertBoost } from '../../lib/units'
import { useStore, useActiveSession } from '../../store'
import { MetricCard } from '../ui/MetricCard'
import { SectionLabel } from '../ui/SectionLabel'
import { EmptyState } from '../ui/EmptyState'
import { TpmsSummaryCard } from '../ui/TpmsSummaryCard'
import { Sparkline } from '../charts/Sparkline'
import { GaugeChart } from '../charts/GaugeChart'
import { ModeTimeline } from '../charts/ModeTimeline'
import { GpsMap } from '../charts/GpsMap'
import { ThermalProgressionChart } from '../charts/ThermalProgressionChart'
import { ExportDropdown } from '../ui/ExportDropdown'
import { fmtNumber, fmtDuration, useUnitFormatters } from '../../lib/format'
import { latestTpms } from '../../lib/readings'
import { isDistanceEstimate, DISTANCE_ESTIMATE_NOTE } from '../../lib/distance'
import { exportSchemaNote } from '../../lib/exportSchema'
import { colors } from '../../styles/tokens'

export function DashboardPanel() {
  // ── Hooks (must run on every render path — keep above any early return) ──
  const session = useActiveSession()
  const sessions = useStore((s) => s.sessions)
  const setActivePanel = useStore((s) => s.setActivePanel)
  const fmt = useUnitFormatters()

  const trip = session?.trip?.summary
  const points = session?.trip?.points
  const diag = session?.diagnostics

  // Sparkline data arrays
  const rpmData = useMemo(() => points?.map((p) => p.rpm) ?? [], [points])
  const speedData = useMemo(() => points?.map((p) => p.speedKph) ?? [], [points])
  // #207: the peak's own measurement, one position per sample — no reading / another source is a gap (never joined,
  // time never compressed), values in the selected unit
  const boostData = useMemo(
    () => (points ? pressureSeries(points, trip?.peakBoostSource ?? null, fmt.boostUnit) : []),
    [points, trip?.peakBoostSource, fmt.boostUnit])
  const boostName = boostLabel(trip?.peakBoostSource) === 'TIP' ? 'TIP' : 'Boost'

  // Race Ready detection (T2I)
  const hasRaceReady = useMemo(() => points?.some((p) => p.raceReady) ?? false, [points])

  // TPMS: each corner's most recent valid reading, found independently — corners are polled out of phase, so
  // any single sample usually has at least one corner still at its sentinel (main e9cd565)
  const tpmsData = useMemo(() => latestTpms(points ?? []), [points])

  // ── Early returns (after all hooks) ──
  if (sessions.length === 0) {
    return (
      <EmptyState
        icon="◈"
        title="Welcome to Sapphire"
        description="Import a session ZIP from the openRS_ app to get started. Drop a file or use the Import panel."
        action={
          <button
            onClick={() => setActivePanel('import')}
            className="px-4 py-2 rounded-md bg-accent/10 text-accent border border-accent/20
                       text-sm font-mono uppercase tracking-wider hover:bg-accent/20 transition-colors"
          >
            Import Session
          </button>
        }
      />
    )
  }

  if (!session) {
    return (
      <EmptyState
        icon="◎"
        title="No Session Selected"
        description="Select a session from the Sessions panel to view its dashboard."
        action={
          <button
            onClick={() => setActivePanel('sessions')}
            className="px-4 py-2 rounded-md bg-accent/10 text-accent border border-accent/20
                       text-sm font-mono uppercase tracking-wider hover:bg-accent/20 transition-colors"
          >
            View Sessions
          </button>
        }
      />
    )
  }

  const schemaNote = exportSchemaNote(session.meta.exportSchemaVersion)

  return (
    <div className="max-w-6xl mx-auto">
      {/* Session header */}
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-lg font-display tracking-wide text-frost">{session.name}</h2>
          <div className="flex gap-4 mt-1 text-xs font-mono text-dim">
            <span>v{session.meta.appVersion}</span>
            {session.meta.firmwareVersion !== 'unknown' && (
              <span>FW {session.meta.firmwareVersion}</span>
            )}
            <span>{session.meta.sessionStart}</span>
          </div>
          {schemaNote && (
            <p className="mt-1 text-xs font-mono" style={{ color: colors.warn }}>{schemaNote}</p>
          )}
          {/* Tags (T2H) */}
          {session.tags.length > 0 && (
            <div className="flex gap-1.5 mt-2 flex-wrap">
              {session.tags.map((tag) => (
                <span
                  key={tag}
                  className="px-2 py-0.5 rounded text-[10px] font-mono uppercase tracking-wider
                             bg-accent/10 text-accent border border-accent/20"
                >
                  {tag}
                </span>
              ))}
            </div>
          )}
          {/* Race Ready indicator (T2I) */}
          {hasRaceReady && (
            <span className="inline-block mt-2 px-2 py-0.5 rounded text-[10px] font-mono uppercase tracking-wider font-semibold bg-ok/10 text-ok border border-ok/20">
              Race Ready Detected
            </span>
          )}
        </div>
        <ExportDropdown session={session} />
      </div>

      {trip && points && (
        <>
          {/* Mini map */}
          <SectionLabel>Route</SectionLabel>
          <GpsMap points={points} peakEvents={trip.peakEvents} bookmarks={session.trip?.bookmarks} height="30vh" />

          {/* Trip KPIs with sparklines */}
          <SectionLabel>Trip Summary</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard
              label="Distance"
              value={fmt.distance(trip.distanceKm)}
              sub={isDistanceEstimate(trip) ? DISTANCE_ESTIMATE_NOTE : undefined}
            />
            <MetricCard label="Duration" value={fmtDuration(trip.durationMs)} />
            <SparkMetricCard label="Avg Speed" value={fmt.speed(trip.avgSpeedKph)} sparkData={speedData} color={colors.ok} />
            <SparkMetricCard label="Avg RPM" value={fmtNumber(trip.avgRpm, 0)} sparkData={rpmData} color={colors.accent} />
          </div>

          {trip.fuelUsedL > 0 && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3">
              <MetricCard label="Fuel Used" value={`${fmtNumber(trip.fuelUsedL)} L`} color={colors.warn} />
              <MetricCard label="Economy" value={fmt.fuelEconomy(trip.avgFuelEconomy)} color={colors.warn} />
            </div>
          )}

          {/* Peak gauges */}
          <SectionLabel>Session Peaks</SectionLabel>
          <div className="rounded-lg border border-brd bg-surf2 p-4">
            <div className="flex flex-wrap justify-center gap-6">
              <GaugeChart value={trip.peakRpm} max={7000} label="Peak RPM" unit="RPM" color={colors.orange} />
              {trip.peakBoostPsi != null
                ? <GaugeChart value={Number(convertBoost(trip.peakBoostPsi, fmt.boostUnit).toFixed(pressureDecimals(fmt.boostUnit)))}
                    max={convertBoost(25, fmt.boostUnit)} label={`Peak ${boostName}`} unit={fmt.boostUnit} color={colors.accent} />
                : <MetricCard label="Peak Boost" value="— (no reading)" color={colors.dim} />}
              <GaugeChart value={trip.peakSpeedKph} max={270} label="Peak Speed" unit={fmt.speedUnit} color={colors.ok} />
              <GaugeChart value={trip.peakLatG} max={1.5} label="Peak Lat G" unit="G" color={colors.warn} />
              {/* Aggression Score (T2A) */}
              {trip.aggressionScore != null && trip.aggressionScore > 0 && (
                <GaugeChart
                  value={trip.aggressionScore}
                  max={100}
                  label="Aggression"
                  unit="/100"
                  color={trip.aggressionScore > 70 ? colors.orange : trip.aggressionScore > 40 ? colors.warn : colors.ok}
                />
              )}
            </div>
            {/* Detail row below gauges */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4 pt-4 border-t border-brd">
              <MetricCard label="Peak Long G" value={`${fmtNumber(trip.peakLongG, 2)} G`} color={colors.warn} />
              <MetricCard label="Peak Coolant" value={fmt.temp(trip.peakCoolantC)} color={trip.peakCoolantC > 105 ? colors.orange : colors.ok} />
              <MetricCard label="Peak Oil" value={fmt.temp(trip.peakOilTempC)} color={trip.peakOilTempC > 130 ? colors.orange : colors.ok} />
              <SparkMetricCard label={`${boostName} Trace`} value={trip.peakBoostPsi != null ? fmt.boost(trip.peakBoostPsi) : '—'}
                sparkData={boostData} color={colors.accent} />
            </div>
          </div>

          {/* Thermal Progression (T2B) */}
          {session.trip?.thermalProgression && (
            <>
              <SectionLabel>Thermal Progression</SectionLabel>
              <ThermalProgressionChart data={session.trip.thermalProgression} />
            </>
          )}

          {/* Drive mode timeline */}
          {Object.keys(trip.modeBreakdown).length > 0 && (
            <>
              <SectionLabel>Drive Mode Timeline</SectionLabel>
              <ModeTimeline points={points} canonicalTimeline={session.trip?.canonicalModeTimeline} />
            </>
          )}

          {/* Bookmarks summary */}
          {session.trip?.bookmarks && session.trip.bookmarks.length > 0 && (
            <>
              <SectionLabel>Bookmarks ({session.trip.bookmarks.length})</SectionLabel>
              <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
                <div className="max-h-[200px] overflow-y-auto">
                  <table className="w-full text-xs font-mono">
                    <thead className="sticky top-0 bg-surf3">
                      <tr className="text-left text-dim uppercase tracking-wider">
                        <th className="px-3 py-2">Label</th>
                        <th className="px-3 py-2">Speed</th>
                        <th className="px-3 py-2">RPM</th>
                        <th className="px-3 py-2">Boost</th>
                      </tr>
                    </thead>
                    <tbody>
                      {session.trip.bookmarks.map((bm, i) => (
                        <tr key={i} className="border-t border-brd hover:bg-surf3/50">
                          <td className="px-3 py-1.5" style={{ color: '#E040FB' }}>{bm.label || `#${i + 1}`}</td>
                          <td className="px-3 py-1.5 text-frost">{bm.speedKph.toFixed(0)} kph</td>
                          <td className="px-3 py-1.5 text-frost">{bm.rpm}</td>
                          <td className="px-3 py-1.5 text-frost">{bm.boostPsi != null ? fmt.boost(bm.boostPsi) : '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}

          {/* TPMS */}
          {tpmsData && (
            <>
              <SectionLabel>TPMS</SectionLabel>
              <TpmsSummaryCard data={tpmsData} />
            </>
          )}
        </>
      )}

      {/* Diagnostic summary */}
      {diag && (
        <>
          <SectionLabel>Diagnostic Summary</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard label="CAN Frames" value={String(diag.canInventory.length)} />
            <MetricCard label="Events" value={String(diag.sessionEvents.length)} />
            <MetricCard label="Decode Entries" value={String(diag.decodeTrace.length)} />
            <MetricCard label="DID Probes" value={String(diag.probeResults.length)} />
            {diag.dtcResults.length > 0 && (
              <MetricCard label="Fault Codes" value={String(diag.dtcResults.length)} color={colors.orange} />
            )}
          </div>
        </>
      )}
    </div>
  )
}

/** MetricCard with an inline sparkline below the value. */
function SparkMetricCard({ label, value, sparkData, color = colors.accent }: {
  label: string; value: string; sparkData: (number | null)[]; color?: string
}) {
  return (
    <div className="rounded-lg border border-brd bg-surf2 p-4 flex flex-col gap-1 min-w-[140px]">
      <span className="text-xs font-mono uppercase tracking-widest text-dim">{label}</span>
      <span className="text-2xl font-display font-bold tracking-wide" style={{ color }}>
        {value}
      </span>
      <Sparkline data={sparkData} color={color} width={120} height={28} />
    </div>
  )
}
