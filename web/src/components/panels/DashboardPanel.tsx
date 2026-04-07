import { useMemo } from 'react'
import { useStore, useActiveSession } from '../../store'
import { MetricCard } from '../ui/MetricCard'
import { SectionLabel } from '../ui/SectionLabel'
import { EmptyState } from '../ui/EmptyState'
import { TpmsSummaryCard } from '../ui/TpmsSummaryCard'
import { Sparkline } from '../charts/Sparkline'
import { GaugeChart } from '../charts/GaugeChart'
import { ModeTimeline } from '../charts/ModeTimeline'
import { GpsMap } from '../charts/GpsMap'
import { ExportDropdown } from '../ui/ExportDropdown'
import { fmtNumber, fmtDuration, useUnitFormatters } from '../../lib/format'
import { colors } from '../../styles/tokens'

export function DashboardPanel() {
  const session = useActiveSession()
  const sessions = useStore((s) => s.sessions)
  const setActivePanel = useStore((s) => s.setActivePanel)
  const fmt = useUnitFormatters()

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

  const trip = session.trip?.summary
  const points = session.trip?.points
  const diag = session.diagnostics

  // Sparkline data arrays
  const rpmData = useMemo(() => points?.map((p) => p.rpm) ?? [], [points])
  const speedData = useMemo(() => points?.map((p) => p.speedKph) ?? [], [points])
  const boostData = useMemo(() => points?.map((p) => p.boostPsi) ?? [], [points])

  // TPMS: get last valid reading from trip points
  const tpmsData = useMemo(() => {
    if (!points || points.length === 0) return null
    const last = [...points].reverse().find((p) =>
      p.tirePressLF >= 0 || p.tirePressRF >= 0 || p.tirePressLR >= 0 || p.tirePressRR >= 0
    )
    if (!last) return null
    return {
      pressLF: last.tirePressLF,
      pressRF: last.tirePressRF,
      pressLR: last.tirePressLR,
      pressRR: last.tirePressRR,
      tempLF: last.tireTempLF,
      tempRF: last.tireTempRF,
      tempLR: last.tireTempLR,
      tempRR: last.tireTempRR,
    }
  }, [points])

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
        </div>
        <ExportDropdown session={session} />
      </div>

      {trip && points && (
        <>
          {/* Mini map */}
          <SectionLabel>Route</SectionLabel>
          <GpsMap points={points} peakEvents={trip.peakEvents} height="30vh" />

          {/* Trip KPIs with sparklines */}
          <SectionLabel>Trip Summary</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard label="Distance" value={fmt.distance(trip.distanceKm)} />
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
              <GaugeChart value={trip.peakBoostPsi} max={25} label="Peak Boost" unit={fmt.boostUnit} color={colors.accent} />
              <GaugeChart value={trip.peakSpeedKph} max={270} label="Peak Speed" unit={fmt.speedUnit} color={colors.ok} />
              <GaugeChart value={trip.peakLatG} max={1.5} label="Peak Lat G" unit="G" color={colors.warn} />
            </div>
            {/* Detail row below gauges */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4 pt-4 border-t border-brd">
              <MetricCard label="Peak Long G" value={`${fmtNumber(trip.peakLongG, 2)} G`} color={colors.warn} />
              <MetricCard label="Peak Coolant" value={fmt.temp(trip.peakCoolantC)} color={trip.peakCoolantC > 105 ? colors.orange : colors.ok} />
              <MetricCard label="Peak Oil" value={fmt.temp(trip.peakOilTempC)} color={trip.peakOilTempC > 130 ? colors.orange : colors.ok} />
              <SparkMetricCard label="Boost Trace" value={fmt.boost(trip.peakBoostPsi)} sparkData={boostData} color={colors.accent} />
            </div>
          </div>

          {/* Drive mode timeline */}
          {Object.keys(trip.modeBreakdown).length > 0 && (
            <>
              <SectionLabel>Drive Mode Timeline</SectionLabel>
              <ModeTimeline points={points} />
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
  label: string; value: string; sparkData: number[]; color?: string
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
