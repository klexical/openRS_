import { useMemo } from 'react'
import { useStore, useCompareSessions } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { DeltaCard } from '../ui/DeltaCard'
import { ComparisonChart } from '../charts/ComparisonChart'
import { GpsMap } from '../charts/GpsMap'
import { computeDeltas } from '../../lib/compare'
import { fmtDateShort } from '../../lib/format'
import { colors, chartColors } from '../../styles/tokens'

const SESSION_COLORS = [chartColors[0], chartColors[1], chartColors[2], chartColors[4]]

export function ComparePanel() {
  const sessions = useStore((s) => s.sessions)
  const compareIds = useStore((s) => s.compareSessionIds)
  const toggleCompare = useStore((s) => s.toggleCompareSession)
  const clearCompare = useStore((s) => s.clearCompare)
  const compareSessions = useCompareSessions()

  // Only sessions with trip data are useful for comparison
  const eligibleSessions = useMemo(
    () => sessions.filter((s) => s.trip && s.trip.points.length > 0),
    [sessions]
  )

  if (eligibleSessions.length < 2) {
    return (
      <EmptyState
        icon="⟺"
        title="Need More Sessions"
        description="Import at least 2 sessions with trip data to compare them."
      />
    )
  }

  const hasEnough = compareSessions.length >= 2
  const baseSession = compareSessions[0]

  // KPI deltas: compare each session against the first (baseline)
  const allDeltas = useMemo(() => {
    if (!hasEnough || !baseSession?.trip) return []
    return compareSessions.slice(1).map((s) => {
      if (!s.trip) return []
      return computeDeltas(baseSession.trip!.summary, s.trip.summary)
    })
  }, [compareSessions, hasEnough, baseSession])

  return (
    <div className="max-w-6xl mx-auto space-y-2">
      {/* Session picker */}
      <SectionLabel>Select Sessions to Compare (2–4)</SectionLabel>
      <div className="space-y-1.5">
        {eligibleSessions.map((sess) => {
          const idx = compareIds.indexOf(sess.id)
          const isSelected = idx >= 0
          const color = isSelected ? SESSION_COLORS[idx % SESSION_COLORS.length] : undefined

          return (
            <button
              key={sess.id}
              onClick={() => toggleCompare(sess.id)}
              className={`
                w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-all
                ${isSelected
                  ? 'bg-surf3 border-accent/30'
                  : 'bg-surf2 border-brd hover:border-dim'
                }
              `}
            >
              {/* Color indicator */}
              <div
                className="w-3 h-3 rounded-full shrink-0 border-2"
                style={{
                  backgroundColor: isSelected ? color : 'transparent',
                  borderColor: isSelected ? color : colors.dim,
                }}
              />
              <div className="flex-1 min-w-0">
                <div className="text-sm font-mono text-frost truncate">{sess.name}</div>
                <div className="flex gap-3 text-[10px] font-mono text-dim uppercase mt-0.5">
                  <span>{fmtDateShort(sess.importedAt)}</span>
                  {sess.trip && <span>{sess.trip.summary.distanceKm.toFixed(1)} km</span>}
                  <span>v{sess.meta.appVersion}</span>
                </div>
              </div>
              {isSelected && (
                <span className="text-[10px] font-mono uppercase tracking-wider"
                  style={{ color }}
                >
                  {idx === 0 ? 'Base' : `#${idx + 1}`}
                </span>
              )}
            </button>
          )
        })}
      </div>

      {compareIds.length > 0 && (
        <button
          onClick={clearCompare}
          className="text-[10px] font-mono text-dim hover:text-frost uppercase tracking-wider transition-colors"
        >
          Clear Selection
        </button>
      )}

      {/* Comparison results */}
      {hasEnough && (
        <>
          {/* KPI Deltas — show comparison against baseline */}
          {allDeltas.map((deltas, ci) => (
            <div key={ci}>
              <SectionLabel>
                {compareSessions[ci + 1]?.name ?? 'Session'} vs {baseSession?.name ?? 'Base'}
              </SectionLabel>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                {deltas.map((d) => (
                  <DeltaCard key={d.label} delta={d} />
                ))}
              </div>
            </div>
          ))}

          {/* Overlay charts */}
          <SectionLabel>Speed Overlay</SectionLabel>
          <ComparisonChart sessions={compareSessions} dataKey="speedKph" label="Speed (KPH)" />

          <SectionLabel>RPM Overlay</SectionLabel>
          <ComparisonChart sessions={compareSessions} dataKey="rpm" label="RPM" />

          <SectionLabel>Boost Overlay</SectionLabel>
          <ComparisonChart sessions={compareSessions} dataKey="boostPsi" label="Boost (PSI)" />

          <SectionLabel>Lateral G Overlay</SectionLabel>
          <ComparisonChart
            sessions={compareSessions}
            dataKey="latG"
            label="Lateral G"
            yFormatter={(v) => `${v.toFixed(2)}G`}
          />

          <SectionLabel>Throttle Overlay</SectionLabel>
          <ComparisonChart sessions={compareSessions} dataKey="throttlePct" label="Throttle (%)" />

          {/* Split GPS maps */}
          <SectionLabel>Route Comparison</SectionLabel>
          <div className={`grid gap-3 ${compareSessions.length <= 2 ? 'grid-cols-1 md:grid-cols-2' : 'grid-cols-2'}`}>
            {compareSessions.map((s, i) => (
              <div key={s.id}>
                <div className="text-[10px] font-mono uppercase tracking-wider mb-1 flex items-center gap-1.5">
                  <span
                    className="w-2 h-2 rounded-full inline-block"
                    style={{ backgroundColor: SESSION_COLORS[i % SESSION_COLORS.length] }}
                  />
                  <span style={{ color: SESSION_COLORS[i % SESSION_COLORS.length] }}>
                    {s.name}
                  </span>
                </div>
                {s.trip && s.trip.points.some((p) => p.lat !== 0 || p.lng !== 0) ? (
                  <GpsMap
                    points={s.trip.points}
                    peakEvents={s.trip.summary.peakEvents}
                    height="30vh"
                  />
                ) : (
                  <div className="h-[30vh] flex items-center justify-center rounded-lg border border-brd bg-surf2 text-dim text-xs font-mono">
                    No GPS data
                  </div>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
