import { useMemo } from 'react'
import type { TripPoint } from '../../types/session'
import { colors } from '../../styles/tokens'
import { fmtDuration } from '../../lib/format'

const MODE_COLORS: Record<string, string> = {
  NORMAL: colors.accent,
  Normal: colors.accent,
  SPORT: '#FF6D00',
  Sport: '#FF6D00',
  TRACK: colors.red,
  Track: colors.red,
  DRIFT: '#E040FB',
  Drift: '#E040FB',
}

interface ModeSegment {
  mode: string
  startMs: number
  endMs: number
  durationMs: number
  pct: number
}

/**
 * Horizontal segmented bar showing drive mode transitions over time.
 * Each segment is color-coded by mode with hover details.
 */
export function ModeTimeline({ points }: { points: TripPoint[] }) {
  const { segments, totalMs } = useMemo(() => {
    if (points.length < 2) return { segments: [] as ModeSegment[], totalMs: 0 }

    const segs: ModeSegment[] = []
    let currentMode = points[0].driveMode
    let segStart = points[0].ts

    for (let i = 1; i < points.length; i++) {
      if (points[i].driveMode !== currentMode) {
        const dur = points[i].ts - segStart
        segs.push({ mode: currentMode, startMs: segStart, endMs: points[i].ts, durationMs: dur, pct: 0 })
        currentMode = points[i].driveMode
        segStart = points[i].ts
      }
    }
    // Final segment
    const last = points[points.length - 1].ts
    segs.push({ mode: currentMode, startMs: segStart, endMs: last, durationMs: last - segStart, pct: 0 })

    const total = last - points[0].ts
    if (total > 0) {
      segs.forEach((s) => { s.pct = (s.durationMs / total) * 100 })
    }

    return { segments: segs, totalMs: total }
  }, [points])

  if (segments.length === 0 || totalMs <= 0) return null

  // Dedupe modes for legend
  const modes = [...new Set(segments.map((s) => s.mode))]

  return (
    <div className="rounded-lg border border-brd bg-surf2 p-3 space-y-2">
      {/* Timeline bar */}
      <div className="flex h-7 rounded-md overflow-hidden">
        {segments.map((seg, i) => (
          <div
            key={i}
            className="h-full relative group transition-opacity hover:opacity-100"
            style={{
              width: `${Math.max(seg.pct, 0.5)}%`,
              backgroundColor: MODE_COLORS[seg.mode] || colors.dim,
              opacity: 0.75,
            }}
          >
            {/* Tooltip on hover */}
            <div
              className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 px-2 py-1 rounded
                         bg-bg border border-brd text-[10px] font-mono text-frost whitespace-nowrap
                         opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-10"
            >
              {seg.mode} — {fmtDuration(seg.durationMs)}
            </div>
            {/* Label inside segment if wide enough */}
            {seg.pct > 12 && (
              <span className="absolute inset-0 flex items-center justify-center text-[10px] font-mono font-semibold"
                style={{ color: colors.bg }}
              >
                {seg.mode}
              </span>
            )}
          </div>
        ))}
      </div>

      {/* Legend + transition count */}
      <div className="flex items-center justify-between">
        <div className="flex gap-4 text-xs font-mono text-dim">
          {modes.map((mode) => {
            const totalSec = segments.filter((s) => s.mode === mode).reduce((sum, s) => sum + s.durationMs, 0) / 1000
            return (
              <span key={mode} className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full" style={{ backgroundColor: MODE_COLORS[mode] || colors.dim }} />
                {mode} {Math.round(totalSec)}s
              </span>
            )
          })}
        </div>
        {segments.length > 1 && (
          <span className="text-[10px] font-mono text-dim">
            {segments.length - 1} transition{segments.length > 2 ? 's' : ''}
          </span>
        )}
      </div>
    </div>
  )
}
