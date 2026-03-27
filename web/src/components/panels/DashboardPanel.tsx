import { useStore, useActiveSession } from '../../store'
import { MetricCard } from '../ui/MetricCard'
import { SectionLabel } from '../ui/SectionLabel'
import { EmptyState } from '../ui/EmptyState'
import { fmtNumber, fmtDuration, fmtTemp } from '../../lib/format'
import { colors } from '../../styles/tokens'

export function DashboardPanel() {
  const session = useActiveSession()
  const sessions = useStore((s) => s.sessions)
  const setActivePanel = useStore((s) => s.setActivePanel)

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
  const diag = session.diagnostics

  return (
    <div className="max-w-6xl mx-auto">
      {/* Session header */}
      <div className="mb-6">
        <h2 className="text-lg font-display tracking-wide text-frost">{session.name}</h2>
        <div className="flex gap-4 mt-1 text-xs font-mono text-dim">
          <span>v{session.meta.appVersion}</span>
          {session.meta.firmwareVersion !== 'unknown' && (
            <span>FW {session.meta.firmwareVersion}</span>
          )}
          <span>{session.meta.sessionStart}</span>
        </div>
      </div>

      {/* Trip KPIs */}
      {trip && (
        <>
          <SectionLabel>Trip Summary</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard label="Distance" value={`${fmtNumber(trip.distanceKm)} km`} />
            <MetricCard label="Duration" value={fmtDuration(trip.durationMs)} />
            <MetricCard label="Avg Speed" value={`${fmtNumber(trip.avgSpeedKph, 0)} kph`} />
            <MetricCard label="Avg RPM" value={fmtNumber(trip.avgRpm, 0)} />
          </div>

          <SectionLabel>Session Peaks</SectionLabel>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <MetricCard label="Peak RPM" value={fmtNumber(trip.peakRpm, 0)} color={colors.orange} />
            <MetricCard label="Peak Boost" value={`${fmtNumber(trip.peakBoostPsi)} PSI`} color={colors.accent} />
            <MetricCard label="Peak Speed" value={`${fmtNumber(trip.peakSpeedKph, 0)} kph`} color={colors.ok} />
            <MetricCard label="Peak Lat G" value={`${fmtNumber(trip.peakLatG, 2)} G`} color={colors.warn} />
            <MetricCard label="Peak Long G" value={`${fmtNumber(trip.peakLongG, 2)} G`} color={colors.warn} />
            <MetricCard label="Peak Coolant" value={fmtTemp(trip.peakCoolantC)} color={trip.peakCoolantC > 105 ? colors.orange : colors.ok} />
            <MetricCard label="Peak Oil" value={fmtTemp(trip.peakOilTempC)} color={trip.peakOilTempC > 130 ? colors.orange : colors.ok} />
          </div>

          {/* Drive mode breakdown */}
          {Object.keys(trip.modeBreakdown).length > 0 && (
            <>
              <SectionLabel>Drive Mode Breakdown</SectionLabel>
              <ModeBreakdownBar breakdown={trip.modeBreakdown} total={trip.durationMs / 1000} />
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
          </div>
        </>
      )}
    </div>
  )
}

function ModeBreakdownBar({ breakdown, total }: { breakdown: Record<string, number>; total: number }) {
  const modeColors: Record<string, string> = {
    NORMAL: colors.accent,
    SPORT: colors.orange,
    TRACK: colors.red,
    DRIFT: '#E040FB',
  }

  if (total <= 0) return null

  return (
    <div className="space-y-2">
      <div className="flex h-6 rounded-md overflow-hidden border border-brd">
        {Object.entries(breakdown).map(([mode, seconds]) => {
          const pct = (seconds / total) * 100
          if (pct < 0.5) return null
          return (
            <div
              key={mode}
              className="h-full transition-all"
              style={{
                width: `${pct}%`,
                backgroundColor: modeColors[mode] || colors.dim,
                opacity: 0.7,
              }}
              title={`${mode}: ${Math.round(seconds)}s (${fmtNumber(pct, 1)}%)`}
            />
          )
        })}
      </div>
      <div className="flex gap-4 text-xs font-mono text-dim">
        {Object.entries(breakdown).map(([mode, seconds]) => (
          <span key={mode} className="flex items-center gap-1.5">
            <span
              className="w-2 h-2 rounded-full"
              style={{ backgroundColor: modeColors[mode] || colors.dim }}
            />
            {mode} {Math.round(seconds)}s
          </span>
        ))}
      </div>
    </div>
  )
}
