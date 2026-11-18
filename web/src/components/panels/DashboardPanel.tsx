import { useMemo } from 'react'
import { boostLabel, pressureDecimals, pressureSeries } from '../../lib/boost'
import { convertBoost, convertSpeed } from '../../lib/units'
import { useStore, useCurrentDrive } from '../../store'
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
import { go } from '../../lib/address'
import { pointsInRange } from '../../lib/selection'
import { summaryInRange, type RangeSummary } from '../../lib/rangeSummary'
import type { TripSummary } from '../../types/session'

/** Under a narrowed range, in place of the figures that cannot be cut to it (#416 ticket 2; pending the owner's Q152). */
const WHOLE_DRIVE_ONLY = 'Fuel, aggression, thermal progression and tyre pressures are for the whole drive.'

export function DashboardPanel() {
  // ── Hooks (must run on every render path — keep above any early return) ──
  const { state, session } = useCurrentDrive()
  // nothing to draw while the drive is read, or after its read failed (the drive's page says so; #384)
  const opening = state === 'being read' || state === 'read failed'
  const sessions = useStore((s) => s.drives)
  const fmt = useUnitFormatters()

  const trip = session?.trip?.summary
  const points = session?.trip?.points
  const diag = session?.diagnostics
  // #416 ticket 2: the summary numbers follow the page's range; the whole drive (null) keeps the stored summary
  const range = useStore((s) => s.selection.range)
  const ranged = useMemo(() => summaryInRange(points ?? [], range, trip?.peakBoostSource ?? null), [points, range, trip?.peakBoostSource])
  const shownPoints = useMemo(() => (points ? pointsInRange(points, range) : undefined), [points, range])

  // Sparkline data arrays
  const rpmData = useMemo(() => shownPoints?.map((p) => p.rpm) ?? [], [shownPoints])
  const speedData = useMemo(() => shownPoints?.map((p) => p.speedKph) ?? [], [shownPoints])
  // #207: the peak's own measurement, one position per sample — no reading / another source is a gap (never joined,
  // time never compressed), values in the selected unit
  const boostData = useMemo(
    () => (shownPoints ? pressureSeries(shownPoints, trip?.peakBoostSource ?? null, fmt.boostUnit) : []),
    [shownPoints, trip?.peakBoostSource, fmt.boostUnit])
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
        description="Import a session ZIP from the openRS_ app to get started."
        action={
          <button
            onClick={() => go({ place: 'drives' })}
            className="px-4 py-2 rounded-md bg-accent/10 text-accent border border-accent/20
                       text-sm font-mono uppercase tracking-wider hover:bg-accent/20 transition-colors"
          >
            Drives
          </button>
        }
      />
    )
  }

  if (opening) return <div aria-busy="true" />   // #384: the selected drive is still being read

  if (!session) {
    return (
      <EmptyState
        icon="◎"
        title="No Session Selected"
        description="Open a drive from the list of drives to view its dashboard."
        action={
          <button
            onClick={() => go({ place: 'drives' })}
            className="px-4 py-2 rounded-md bg-accent/10 text-accent border border-accent/20
                       text-sm font-mono uppercase tracking-wider hover:bg-accent/20 transition-colors"
          >
            Drives
          </button>
        }
      />
    )
  }

  const schemaNote = exportSchemaNote(session.meta.exportSchemaVersion)
  const peak = peaksOf(trip, ranged)

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

          {/* Trip KPIs with sparklines: the range's own figures while one is set (#416 ticket 2) */}
          <SectionLabel>Trip Summary</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard
              label="Distance"
              value={fmt.distance(ranged ? ranged.distanceKm : trip.distanceKm)}
              sub={ranged || isDistanceEstimate(trip) ? DISTANCE_ESTIMATE_NOTE : undefined}
            />
            <MetricCard label="Duration" value={fmtDuration(ranged ? ranged.durationMs : trip.durationMs)} />
            <SparkMetricCard label="Avg Speed" value={orDash(ranged ? ranged.avgSpeedKph : trip.avgSpeedKph, fmt.speed)} sparkData={speedData} color={colors.ok} />
            <SparkMetricCard label="Avg RPM" value={orDash(ranged ? ranged.avgRpm : trip.avgRpm, (v) => fmtNumber(v, 0))} sparkData={rpmData} color={colors.cyan} />
          </div>

          {!ranged && trip.fuelUsedL > 0 && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3">
              <MetricCard label="Fuel Used" value={`${fmtNumber(trip.fuelUsedL)} L`} color={colors.warn} />
              <MetricCard label="Economy" value={fmt.fuelEconomy(trip.avgFuelEconomy)} color={colors.warn} />
            </div>
          )}

          {/* Peak gauges */}
          <SectionLabel>Session Peaks</SectionLabel>
          <div className="rounded-lg border border-brd bg-surf2 p-4">
            <div className="flex flex-wrap justify-center gap-6">
              {peak.rpm != null
                ? <GaugeChart value={peak.rpm} max={7000} label="Peak RPM" unit="RPM" color={colors.orange} />
                : <MetricCard label="Peak RPM" value="—" color={colors.dim} />}
              {peak.boostPsi != null
                ? <GaugeChart value={Number(convertBoost(peak.boostPsi, fmt.boostUnit).toFixed(pressureDecimals(fmt.boostUnit)))}
                    max={convertBoost(25, fmt.boostUnit)} label={`Peak ${boostName}`} unit={fmt.boostUnit} color={colors.cyan} />
                : <MetricCard label="Peak Boost" value="— (no reading)" color={colors.dim} />}
              {peak.speedKph != null
                ? <GaugeChart value={convertSpeed(peak.speedKph, fmt.unitSystem)} max={convertSpeed(270, fmt.unitSystem)}
                    label="Peak Speed" unit={fmt.speedUnit} color={colors.ok} />
                : <MetricCard label="Peak Speed" value="—" color={colors.dim} />}
              {peak.latG != null
                ? <GaugeChart value={peak.latG} max={1.5} label="Peak Lat G" unit="G" color={colors.warn} />
                : <MetricCard label="Peak Lat G" value="—" color={colors.dim} />}
              {/* Aggression Score (T2A): a whole-drive figure */}
              {!ranged && trip.aggressionScore != null && trip.aggressionScore > 0 && (
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
              <MetricCard label="Peak Long G" value={orDash(peak.longG, (v) => `${fmtNumber(v, 2)} G`)} color={colors.warn} />
              <MetricCard label="Peak Coolant" value={fmt.temp(peak.coolantC)} color={peak.coolantC > 105 ? colors.orange : colors.ok} />
              <MetricCard label="Peak Oil" value={fmt.temp(peak.oilTempC)} color={peak.oilTempC > 130 ? colors.orange : colors.ok} />
              <SparkMetricCard label={`${boostName} Trace`} value={peak.boostPsi != null ? fmt.boost(peak.boostPsi) : '—'}
                sparkData={boostData} color={colors.cyan} />
            </div>
            {ranged && <p className="mt-3 text-xs font-mono text-mid">{WHOLE_DRIVE_ONLY}</p>}
          </div>

          {/* Thermal Progression (T2B): a whole-drive record */}
          {!ranged && session.trip?.thermalProgression && (
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
              <SectionLabel>Observer’s notes ({session.trip.bookmarks.length})</SectionLabel>
              <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
                <div className="max-h-[200px] overflow-y-auto">
                  <table className="w-full text-xs font-mono">
                    <thead className="sticky top-0 bg-surf3">
                      <tr className="text-left text-dim uppercase tracking-wider">
                        <th className="px-3 py-2">Drive time</th>
                        <th className="px-3 py-2">Note (typed in the car)</th>
                        <th className="px-3 py-2">Speed</th>
                        <th className="px-3 py-2">RPM</th>
                        <th className="px-3 py-2">Boost</th>
                      </tr>
                    </thead>
                    <tbody>
                      {session.trip.bookmarks.map((bm, i) => (
                        <tr key={i} className="border-t border-brd hover:bg-surf3/50">
                          <td className="px-3 py-1.5 text-dim">{bm.relMs != null ? fmtDuration(bm.relMs) : '—'}</td>
                          <td className="px-3 py-1.5" style={{ color: '#E040FB' }}>{bm.label || `#${i + 1} (no text)`}</td>
                          <td className="px-3 py-1.5 text-frost">{fmt.speed(bm.speedKph)}</td>
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

          {/* TPMS: each corner's latest reading of the whole drive */}
          {!ranged && tpmsData && (
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

/** A figure, or "—" when there is none (no reading in the range, #416 ticket 2). */
function orDash(v: number | null | undefined, format: (v: number) => string): string {
  return v == null || !Number.isFinite(v) ? '—' : format(v)
}

/** The peaks shown: the range's own while one is set, else the stored summary's (#416 ticket 2). Temperatures keep −99 for none. */
function peaksOf(trip: TripSummary | undefined, ranged: RangeSummary | null) {
  const s = ranged ?? trip
  return {
    rpm: s?.peakRpm ?? null, boostPsi: s?.peakBoostPsi ?? null, speedKph: s?.peakSpeedKph ?? null, latG: s?.peakLatG ?? null,
    longG: s?.peakLongG ?? null, coolantC: s?.peakCoolantC ?? -99, oilTempC: s?.peakOilTempC ?? -99,
  }
}

/** MetricCard with an inline sparkline below the value. */
function SparkMetricCard({ label, value, sparkData, color = colors.cyan }: {   // data: fixed, never the paint (#384 ticket 08)
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
