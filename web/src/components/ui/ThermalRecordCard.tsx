import { useUnitFormatters } from '../../lib/format'
import {
  SENSOR_LABEL, THERMAL_SENSORS, profileName, segmentNames, thermalRows, type ThermalLevel, type ThermalRecord, type ThermalSensor,
} from '../../lib/thermalRecord'
import type { ThermalProgression } from '../../types/session'
import { colors } from '../../styles/tokens'

const LINE = 'text-xs font-mono text-mid'
const CELL = 'px-2 py-1 text-right'
const HEAD = 'px-2 py-1 text-right font-normal text-dim'

/** A peak's level, in its own segment's limits: a word as well as a colour; NORMAL carries no colour. */
function levelColor(l: ThermalLevel | null): string | undefined {
  return l === 'CRIT' ? colors.orange : l === 'WARN' ? colors.warnText : undefined
}

/** The level word, in the words of the limit columns beside it. */
const LEVEL_WORD: Record<ThermalLevel, string> = { NORMAL: 'Normal', WARN: 'Warn', CRIT: 'Critical' }

const offset = (ms: number) => {
  const sec = Math.floor(ms / 1000)
  return `${Math.floor(sec / 60)}:${(sec % 60).toString().padStart(2, '0')}`
}

/**
 * The drive's thermal record from the Android export (#315): the limits saved with each part of the drive, the
 * per-sensor peaks with start and end where the export has them, and the reading behind each TEMP flag. Temperatures
 * follow the unit setting; a reading the record does not hold is "—", never a 0.
 */
export function ThermalRecordCard({ record, progression }: { record: ThermalRecord; progression?: ThermalProgression }) {
  const fmt = useUnitFormatters()
  const temp = (c: number | null) => (c == null ? '—' : fmt.temp(c))
  // a reading beside limits keeps the decimals that put it on the correct side of each (the app's tVs, #315)
  const vs = (c: number, limits: (number | null | undefined)[]) =>
    fmt.tempVs(c, limits.filter((l): l is number => typeof l === 'number'))

  if (record.basis === 'none') {
    return (
      <div className="rounded-lg border border-brd bg-surf2 p-4 space-y-1" data-testid="thermal-record">
        <p className="text-sm font-mono text-frost">No thermal record</p>
        <p className={LINE}>
          {record.reason === 'unreadable'
            ? 'A thermal record was saved with this drive but could not be read, so the limits it was judged against are unknown.'
            : 'No thermal limits were saved with this drive, so the limits it was judged against are unknown.'}
        </p>
      </div>
    )
  }

  if (record.basis === 'legacy') {
    const sensors = THERMAL_SENSORS.filter((s) => record.sensors[s])
    return (
      <div className="rounded-lg border border-brd bg-surf2 p-4 space-y-2" data-testid="thermal-record">
        <p className={LINE}>Recorded before limits were saved with drives: drive-end readings against the old fixed limits, not peaks.</p>
        <div className="overflow-x-auto"><table className="text-xs font-mono text-frost">
          <thead>
            <tr><th className="px-2 py-1 text-left font-normal text-dim">Sensor</th><th className={HEAD}>Drive end</th>
              <th className={HEAD}>Old fixed limit</th><th className={HEAD}>Flag threshold</th></tr>
          </thead>
          <tbody>
            {sensors.map((s) => {
              const r = record.sensors[s]!
              return (
                <tr key={s}>
                  <td className="px-2 py-1 text-left">{SENSOR_LABEL[s]}</td>
                  <td className={CELL}>{vs(r.driveEndC, [r.fixedLimitC, r.flagThresholdC])}</td>
                  <td className={CELL}>{r.fixedLimitC == null ? '—' : fmt.tempLimit(r.fixedLimitC)}</td>
                  <td className={CELL}>{r.flagThresholdC == null ? '—' : fmt.tempLimit(r.flagThresholdC)}</td>
                </tr>
              )
            })}
          </tbody>
        </table></div>
        {sensors.filter((s) => record.sensors[s]!.flagged && record.sensors[s]!.flagThresholdC != null).map((s) => {
          const r = record.sensors[s]!
          return (
            <p key={s} className="text-xs font-mono text-warn-text">
              TEMP FLAG {SENSOR_LABEL[s]} {vs(r.driveEndC, [r.flagThresholdC])} at drive end · flag threshold {fmt.tempLimit(r.flagThresholdC!)} · old fixed limit
            </p>
          )
        })}
      </div>
    )
  }

  const names = segmentNames(record.segments)
  const rows = thermalRows(record, progression)
  const limitsOf = (s: ThermalSensor) => record.segments.flatMap((seg) => {
    const l = seg.limits[s]
    return l ? [l.warnC, l.critC, l.flagThresholdC] : []
  })
  const flagLines = THERMAL_SENSORS.filter((s) => record.sensors[s]?.flag)
  return (
    <div className="rounded-lg border border-brd bg-surf2 p-4 space-y-3" data-testid="thermal-record">
      <p className={LINE}>
        {record.segments.length > 1
          ? `Limits: ${names.join(' → ')}, each part judged by the profile in effect then`
          : `Limits: ${names[0]} profile, saved with the drive`}
      </p>

      {rows.length > 0 && (
        <div className="overflow-x-auto"><table className="text-xs font-mono text-frost">
          <thead>
            <tr><th className="px-2 py-1 text-left font-normal text-dim">Sensor</th><th className={HEAD}>Start</th>
              <th className={HEAD}>Peak</th><th className={HEAD}>End</th><th className="px-2 py-1 text-left font-normal text-dim">Level</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.sensor}>
                <td className="px-2 py-1 text-left">{SENSOR_LABEL[r.sensor]}</td>
                <td className={CELL}>{temp(r.startC)}</td>
                <td className={CELL} style={{ color: levelColor(r.level) }}>{vs(r.peakC, limitsOf(r.sensor))}</td>
                <td className={CELL}>{temp(r.endC)}</td>
                <td className="px-2 py-1 text-left" style={{ color: levelColor(r.level) }}>{r.level ? LEVEL_WORD[r.level] : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}

      {record.segments.map((seg, i) => {
        const sensors = THERMAL_SENSORS.filter((s) => seg.limits[s])
        return (
          <div key={i} className="space-y-1">
            <p className={LINE}>{names[i]} from {offset(seg.startOffsetMs)}</p>
            {sensors.length > 0 && (
              <div className="overflow-x-auto"><table className="text-xs font-mono text-frost">
                <thead>
                  <tr><th className="px-2 py-1 text-left font-normal text-dim">Sensor</th><th className={HEAD}>Peak here</th>
                    <th className={HEAD}>Warn</th><th className={HEAD}>Critical</th><th className={HEAD}>Flag threshold</th></tr>
                </thead>
                <tbody>
                  {sensors.map((s) => {
                    const l = seg.limits[s]!
                    return (
                      <tr key={s}>
                        <td className="px-2 py-1 text-left">{SENSOR_LABEL[s]}</td>
                        <td className={CELL}>{seg.peaksC[s] == null ? '—' : vs(seg.peaksC[s]!, [l.warnC, l.critC, l.flagThresholdC])}</td>
                        <td className={CELL}>{fmt.tempLimit(l.warnC)}</td>
                        <td className={CELL}>{fmt.tempLimit(l.critC)}</td>
                        <td className={CELL}>{fmt.tempLimit(l.flagThresholdC)}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table></div>
            )}
          </div>
        )
      })}

      {flagLines.map((s) => {
        const f = record.sensors[s]!.flag!
        return (
          <p key={s} className="text-xs font-mono text-warn-text">
            TEMP FLAG {SENSOR_LABEL[s]} {vs(f.readingC, [f.flagThresholdC])} · flag threshold {fmt.tempLimit(f.flagThresholdC)}
            {f.profile ? ` · ${profileName(f.profile)}` : ''}
          </p>
        )
      })}
      {record.flagged && flagLines.length === 0 && (
        <p className="text-xs font-mono text-warn-text">Flagged, but the reading behind the flag could not be read.</p>
      )}
    </div>
  )
}
