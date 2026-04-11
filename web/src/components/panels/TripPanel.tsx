import { useMemo } from 'react'
import { useActiveSession } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { ExportDropdown } from '../ui/ExportDropdown'
import { TimeSeriesChart } from '../charts/TimeSeriesChart'
import { GpsMap } from '../charts/GpsMap'
import { ModeTimeline } from '../charts/ModeTimeline'
import { GForceScatter } from '../charts/GForceScatter'
import { TempStackChart } from '../charts/TempStackChart'
import { Histogram } from '../charts/Histogram'
import { useUnitFormatters } from '../../lib/format'
import { colors, chartColors } from '../../styles/tokens'

export function TripPanel() {
  // ── Hooks (must run on every render path — keep above any early return) ──
  const session = useActiveSession()
  const fmt = useUnitFormatters()

  const trip = session?.trip ?? null
  // Stabilize points identity so downstream useMemo deps don't churn every render
  const points = useMemo(() => trip?.points ?? [], [trip])
  const t0 = points[0]?.ts ?? 0
  const peakEvents = trip?.summary.peakEvents ?? []

  // Build chart data with relative time in seconds.
  // Sentinel-aware helpers return null instead of 0 so Recharts renders gaps for
  // missing data instead of misleading dips, and explicitly reject NaN/Infinity
  // so a malformed CSV row can't poison downstream rendering.
  const chartData = useMemo(() => {
    const safeTemp = (v: number) => (Number.isFinite(v) && v > -90 ? v : null)
    const safePct = (v: number) => (Number.isFinite(v) && v >= 0 ? v : null)
    const safeNum = (v: number) => (Number.isFinite(v) ? v : null)
    return points.map((p) => ({
      ts: (p.ts - t0) / 1000,
      rpm: safeNum(p.rpm),
      boostPsi: safeNum(p.boostPsi),
      speedKph: safeNum(p.speedKph),
      coolantC: safeTemp(p.coolantC),
      oilTempC: safeTemp(p.oilTempC),
      rduTempC: safeTemp(p.rduTempC),
      ptuTempC: safeTemp(p.ptuTempC),
      latG: safeNum(p.latG),
      fuelPct: safePct(p.fuelPct),
      wsFL: safeNum(p.wheelSpeedFL),
      wsFR: safeNum(p.wheelSpeedFR),
      wsRL: safeNum(p.wheelSpeedRL),
      wsRR: safeNum(p.wheelSpeedRR),
      awdL: safeNum(p.awdTorqueL),
      awdR: safeNum(p.awdTorqueR),
      throttlePct: safePct(p.throttlePct),
      tirePressLF: safePct(p.tirePressLF),
      tirePressRF: safePct(p.tirePressRF),
      tirePressLR: safePct(p.tirePressLR),
      tirePressRR: safePct(p.tirePressRR),
      tireTempLF: safeTemp(p.tireTempLF),
      tireTempRF: safeTemp(p.tireTempRF),
      tireTempLR: safeTemp(p.tireTempLR),
      tireTempRR: safeTemp(p.tireTempRR),
    }))
  }, [points, t0])

  const hasThrottle = useMemo(() => points.some((p) => p.throttlePct >= 0), [points])
  const hasTpmsPress = useMemo(() => points.some((p) => p.tirePressLF >= 0 || p.tirePressRF >= 0), [points])
  const hasTpmsTemp = useMemo(() => points.some((p) => p.tireTempLF > -90 || p.tireTempRF > -90), [points])

  // ── Early returns (after all hooks) ──
  if (!session) {
    return <EmptyState icon="◎" title="No Session Selected" description="Select a session to view trip data." />
  }

  if (!trip || points.length === 0) {
    const hasDiagnostics = !!session.diagnostics
    return (
      <EmptyState
        icon="◎"
        title="No Trip Data"
        description={
          hasDiagnostics
            ? "This session contains diagnostic data but no drive recording. To export trip data, open the openRS_ app → MAP tab → drive history → tap a drive → Share."
            : "This session does not contain trip data."
        }
      />
    )
  }

  const fmtTime = (sec: number) => {
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}:${s.toString().padStart(2, '0')}`
  }

  const syncId = 'trip-charts'

  return (
    <div className="max-w-6xl mx-auto space-y-2">
      {/* Header with export */}
      <div className="flex items-center justify-between mb-2">
        <h2 className="text-sm font-display tracking-wide text-frost">{session.name}</h2>
        <ExportDropdown session={session} />
      </div>

      <SectionLabel>Route</SectionLabel>
      <GpsMap points={points} peakEvents={peakEvents} />

      <SectionLabel>Drive Modes</SectionLabel>
      <ModeTimeline points={points} />

      <SectionLabel>RPM</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'rpm', label: 'RPM', color: colors.accent }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="rpm"
      />

      <SectionLabel>Boost ({fmt.boostUnit})</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'boostPsi', label: `Boost ${fmt.boostUnit}`, color: chartColors[1] }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="boost"
      />

      <SectionLabel>Speed ({fmt.speedUnit})</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'speedKph', label: 'Speed', color: chartColors[2] }]}
        xFormatter={fmtTime}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="speed"
      />

      {hasThrottle && (
        <>
          <SectionLabel>Throttle (%)</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[{ key: 'throttlePct', label: 'Throttle', color: chartColors[5] }]}
            xFormatter={fmtTime}
            syncId={syncId}
          />
        </>
      )}

      <SectionLabel>Temperatures ({fmt.tempUnit})</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'coolantC', label: 'Coolant', color: colors.accent },
          { key: 'oilTempC', label: 'Oil', color: colors.orange },
          { key: 'rduTempC', label: 'RDU', color: chartColors[4] },
          { key: 'ptuTempC', label: 'PTU', color: chartColors[3] },
        ]}
        xFormatter={fmtTime}
        yFormatter={(v) => `${v}°`}
        height={220}
        syncId={syncId}
      />

      <SectionLabel>Lateral G</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'latG', label: 'Lat G', color: chartColors[3] }]}
        xFormatter={fmtTime}
        yFormatter={(v) => `${v.toFixed(2)}G`}
        syncId={syncId}
        peakEvents={peakEvents}
        peakFilter="latG"
      />

      <SectionLabel>Fuel Level (%)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'fuelPct', label: 'Fuel %', color: chartColors[6] }]}
        xFormatter={fmtTime}
        syncId={syncId}
      />

      <SectionLabel>Wheel Speeds</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'wsFL', label: 'FL', color: colors.accent },
          { key: 'wsFR', label: 'FR', color: chartColors[1] },
          { key: 'wsRL', label: 'RL', color: chartColors[2] },
          { key: 'wsRR', label: 'RR', color: chartColors[3] },
        ]}
        xFormatter={fmtTime}
        height={220}
        syncId={syncId}
      />

      {hasTpmsPress && (
        <>
          <SectionLabel>Tire Pressure ({fmt.tirePressUnit})</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[
              { key: 'tirePressLF', label: 'LF', color: colors.accent },
              { key: 'tirePressRF', label: 'RF', color: chartColors[1] },
              { key: 'tirePressLR', label: 'LR', color: chartColors[2] },
              { key: 'tirePressRR', label: 'RR', color: chartColors[3] },
            ]}
            xFormatter={fmtTime}
            yFormatter={(v) => `${v.toFixed(1)}`}
            height={200}
            syncId={syncId}
          />
        </>
      )}

      {hasTpmsTemp && (
        <>
          <SectionLabel>Tire Temperature ({fmt.tempUnit})</SectionLabel>
          <TimeSeriesChart
            data={chartData}
            series={[
              { key: 'tireTempLF', label: 'LF', color: colors.accent },
              { key: 'tireTempRF', label: 'RF', color: chartColors[1] },
              { key: 'tireTempLR', label: 'LR', color: chartColors[2] },
              { key: 'tireTempRR', label: 'RR', color: chartColors[3] },
            ]}
            xFormatter={fmtTime}
            yFormatter={(v) => `${v}°`}
            height={200}
            syncId={syncId}
          />
        </>
      )}

      <SectionLabel>AWD Torque (Nm)</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[
          { key: 'awdL', label: 'Left', color: colors.accent },
          { key: 'awdR', label: 'Right', color: chartColors[1] },
        ]}
        xFormatter={fmtTime}
        syncId={syncId}
      />

      {/* ── Advanced Visualizations ── */}

      <SectionLabel>G-Force vs Speed</SectionLabel>
      <GForceScatter points={points} />

      <SectionLabel>Thermal Soak</SectionLabel>
      <TempStackChart points={points} syncId={syncId} />

      <SectionLabel>RPM Distribution</SectionLabel>
      <Histogram
        values={points.map((p) => p.rpm)}
        bins={14}
        color={colors.accent}
        xLabel="RPM"
        yLabel="Samples"
      />

      <SectionLabel>Boost Distribution ({fmt.boostUnit})</SectionLabel>
      <Histogram
        values={points.map((p) => p.boostPsi).filter((v) => v > -5)}
        bins={12}
        color={chartColors[1]}
        xLabel={fmt.boostUnit}
        yLabel="Samples"
      />
    </div>
  )
}
