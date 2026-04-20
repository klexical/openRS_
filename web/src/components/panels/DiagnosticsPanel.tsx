import { useState, useMemo } from 'react'
import { useActiveSession } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { TimeSeriesChart } from '../charts/TimeSeriesChart'
import { colors } from '../../styles/tokens'
import type { DtcEntry, NrcSuppression } from '../../types/session'

export function DiagnosticsPanel() {
  const session = useActiveSession()

  if (!session) {
    return <EmptyState icon="⬡" title="No Session Selected" description="Select a session to view diagnostics." />
  }

  const diag = session.diagnostics
  if (!diag) {
    return <EmptyState icon="⬡" title="No Diagnostic Data" description="This session does not contain diagnostic data." />
  }

  return (
    <div className="max-w-6xl mx-auto space-y-2">
      {diag.dtcResults.length > 0 && <DtcTable entries={diag.dtcResults} />}
      {diag.fpsTimeline.length > 0 && <FpsChart data={diag.fpsTimeline} />}
      {diag.canInventory.length > 0 && <CanInventoryTable frames={diag.canInventory} />}
      {diag.sessionEvents.length > 0 && <EventsTable events={diag.sessionEvents} />}
      {diag.probeResults.length > 0 && <ProbeTable probes={diag.probeResults} />}
      {diag.decodeTrace.length > 0 && <DecodeTraceTable entries={diag.decodeTrace} />}
      {diag.nrcSuppression && diag.nrcSuppression.length > 0 && (
        <NrcSuppressionTable entries={diag.nrcSuppression} />
      )}
    </div>
  )
}

// ── CAN Inventory ──

function CanInventoryTable({ frames }: { frames: { id: string; count: number; changed: boolean; firstHex: string; lastHex: string }[] }) {
  const [sortKey, setSortKey] = useState<'id' | 'count'>('id')
  const sorted = [...frames].sort((a, b) =>
    sortKey === 'count' ? b.count - a.count : a.id.localeCompare(b.id)
  )

  return (
    <>
      <SectionLabel>CAN Frame Inventory</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[400px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2 cursor-pointer hover:text-frost" onClick={() => setSortKey('id')}>
                  CAN ID {sortKey === 'id' ? '▼' : ''}
                </th>
                <th className="px-3 py-2 cursor-pointer hover:text-frost" onClick={() => setSortKey('count')}>
                  Count {sortKey === 'count' ? '▼' : ''}
                </th>
                <th className="px-3 py-2">Changed</th>
                <th className="px-3 py-2">First Hex</th>
                <th className="px-3 py-2">Last Hex</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((f) => (
                <tr key={f.id} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-accent">{f.id}</td>
                  <td className="px-3 py-1.5 text-frost">{f.count.toLocaleString()}</td>
                  <td className="px-3 py-1.5">
                    <span className={f.changed ? 'text-ok' : 'text-dim'}>
                      {f.changed ? 'yes' : 'no'}
                    </span>
                  </td>
                  <td className="px-3 py-1.5 text-dim">{f.firstHex}</td>
                  <td className="px-3 py-1.5 text-dim">{f.lastHex}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── Session Events ──

function EventsTable({ events }: { events: { ts: number; type: string; message: string }[] }) {
  const typeColors: Record<string, string> = {
    error: colors.orange,
    session: colors.accent,
    slcan: colors.dim,
    firmware: colors.ok,
    dm_cmd: colors.warn,
  }

  return (
    <>
      <SectionLabel>Session Events</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[400px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2 w-24">Time</th>
                <th className="px-3 py-2 w-20">Type</th>
                <th className="px-3 py-2">Message</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e, i) => (
                <tr key={i} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-dim">{fmtRelMs(e.ts)}</td>
                  <td className="px-3 py-1.5">
                    <span
                      className="inline-block px-1.5 py-0.5 rounded text-[10px] uppercase"
                      style={{
                        color: typeColors[e.type] || colors.frost,
                        backgroundColor: (typeColors[e.type] || colors.dim) + '15',
                      }}
                    >
                      {e.type}
                    </span>
                  </td>
                  <td className="px-3 py-1.5 text-frost">{e.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── DID Probe Results ──

function ProbeTable({ probes }: { probes: { module: string; did: string; status: string; responseHex: string; description: string }[] }) {
  const statusColor = (s: string) =>
    s === 'ok' || s === 'found' ? colors.ok : s === 'timeout' ? colors.dim : colors.orange

  return (
    <>
      <SectionLabel>DID Probe Results</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[400px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2">Module</th>
                <th className="px-3 py-2">DID</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Response</th>
              </tr>
            </thead>
            <tbody>
              {probes.map((p, i) => (
                <tr key={i} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-accent">{p.module}</td>
                  <td className="px-3 py-1.5 text-frost">{p.did}</td>
                  <td className="px-3 py-1.5" style={{ color: statusColor(p.status) }}>{p.status}</td>
                  <td className="px-3 py-1.5 text-dim">{p.responseHex}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── Decode Trace ──

function DecodeTraceTable({ entries }: { entries: { ts: number; canId: string; rawHex: string; decoded: string; issue: string }[] }) {
  const [search, setSearch] = useState('')
  const filtered = search
    ? entries.filter((e) =>
        e.canId.toLowerCase().includes(search.toLowerCase()) ||
        e.decoded.toLowerCase().includes(search.toLowerCase())
      )
    : entries.slice(-500)

  return (
    <>
      <SectionLabel>Decode Trace</SectionLabel>
      <div className="mb-2">
        <input
          type="text"
          placeholder="Filter by CAN ID or decoded value..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full max-w-sm px-3 py-1.5 rounded-md bg-surf3 border border-brd
                     text-xs font-mono text-frost placeholder:text-dim
                     focus:outline-none focus:border-accent/40"
        />
      </div>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[400px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2 w-20">Time</th>
                <th className="px-3 py-2 w-16">CAN ID</th>
                <th className="px-3 py-2">Raw Hex</th>
                <th className="px-3 py-2">Decoded</th>
                <th className="px-3 py-2 w-20">Issue</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((e, i) => (
                <tr key={i} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-dim">{fmtRelMs(e.ts)}</td>
                  <td className="px-3 py-1.5 text-accent">{e.canId}</td>
                  <td className="px-3 py-1.5 text-dim">{e.rawHex}</td>
                  <td className="px-3 py-1.5 text-frost">{e.decoded}</td>
                  <td className="px-3 py-1.5 text-orange">{e.issue}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── DTC Fault Codes (T3A + T3B) ──

function DtcTable({ entries }: { entries: DtcEntry[] }) {
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)
  const [sortKey, setSortKey] = useState<'module' | 'severity'>('module')

  const sorted = useMemo(() => {
    if (sortKey === 'severity') {
      const order: Record<string, number> = { CRITICAL: 0, WARNING: 1, INFO: 2, UNKNOWN: 3 }
      return [...entries].sort((a, b) => (order[a.severity || 'UNKNOWN'] ?? 3) - (order[b.severity || 'UNKNOWN'] ?? 3))
    }
    return entries
  }, [entries, sortKey])

  const statusColor = (s: string) => {
    const sl = s.toUpperCase()
    if (sl === 'ACTIVE' || sl === 'STORED') return colors.orange
    if (sl === 'PENDING') return colors.warn
    if (sl === 'PERMANENT') return colors.red
    return colors.dim
  }

  const severityColor = (s?: string) => {
    if (!s) return colors.dim
    if (s === 'CRITICAL') return colors.red
    if (s === 'WARNING') return colors.orange
    if (s === 'INFO') return colors.accent
    return colors.dim
  }

  return (
    <>
      <SectionLabel>Fault Codes ({entries.length})</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[500px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2 w-16 cursor-pointer hover:text-frost" onClick={() => setSortKey('module')}>Module</th>
                <th className="px-3 py-2 w-20">Code</th>
                <th className="px-3 py-2 w-24">Status</th>
                <th className="px-3 py-2 w-20 cursor-pointer hover:text-frost" onClick={() => setSortKey('severity')}>Severity</th>
                <th className="px-3 py-2">Description</th>
                <th className="px-3 py-2 w-8"></th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((dtc, i) => (
                <>
                  <tr
                    key={i}
                    className={`border-t border-brd transition-colors ${dtc.freezeFrame ? 'cursor-pointer hover:bg-surf3/50' : ''}`}
                    onClick={() => dtc.freezeFrame && setExpandedIdx(expandedIdx === i ? null : i)}
                  >
                    <td className="px-3 py-1.5 text-accent">{dtc.module}</td>
                    <td className="px-3 py-1.5 text-frost font-semibold">{dtc.code}</td>
                    <td className="px-3 py-1.5">
                      <span
                        className="inline-block px-1.5 py-0.5 rounded text-[10px] uppercase font-semibold"
                        style={{ color: statusColor(dtc.status), backgroundColor: statusColor(dtc.status) + '18' }}
                      >
                        {dtc.status}
                      </span>
                    </td>
                    <td className="px-3 py-1.5">
                      {dtc.severity && (
                        <span
                          className="inline-block px-1.5 py-0.5 rounded text-[10px] uppercase font-semibold"
                          style={{ color: severityColor(dtc.severity), backgroundColor: severityColor(dtc.severity) + '15' }}
                        >
                          {dtc.severity}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-1.5 text-mid">{dtc.description}</td>
                    <td className="px-3 py-1.5 text-dim">
                      {dtc.freezeFrame && (expandedIdx === i ? '▲' : '▼')}
                    </td>
                  </tr>
                  {/* Freeze Frame detail (T3A) */}
                  {expandedIdx === i && dtc.freezeFrame && (
                    <tr key={`ff-${i}`}>
                      <td colSpan={6} className="p-0">
                        <div className="bg-surf3 px-6 py-3 border-t border-brd">
                          <div className="text-[10px] font-mono uppercase tracking-wider text-dim mb-2">
                            Freeze Frame — Record #{dtc.freezeFrame.recordNumber}
                          </div>
                          <div className="grid grid-cols-2 md:grid-cols-3 gap-2">
                            {dtc.freezeFrame.entries.map((entry, j) => (
                              <div key={j} className="px-2 py-1 rounded bg-surf2 border border-brd">
                                <div className="text-[9px] text-dim uppercase">{entry.did} — {entry.label}</div>
                                <div className="text-xs text-frost font-semibold">{entry.value}</div>
                              </div>
                            ))}
                          </div>
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── NRC Suppression Table (T3C) ──

function NrcSuppressionTable({ entries }: { entries: NrcSuppression[] }) {
  return (
    <>
      <SectionLabel>NRC Suppression ({entries.length})</SectionLabel>
      <div className="rounded-lg border border-brd bg-surf2 overflow-hidden">
        <div className="max-h-[300px] overflow-y-auto">
          <table className="w-full text-xs font-mono">
            <thead className="sticky top-0 bg-surf3">
              <tr className="text-left text-dim uppercase tracking-wider">
                <th className="px-3 py-2">DID</th>
                <th className="px-3 py-2">NRC Code</th>
                <th className="px-3 py-2">NRC Name</th>
                <th className="px-3 py-2">First Seen</th>
                <th className="px-3 py-2">Suppressed After</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((n, i) => (
                <tr key={i} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-accent">{n.did}</td>
                  <td className="px-3 py-1.5 text-frost">0x{n.nrcCode.toString(16).toUpperCase().padStart(2, '0')}</td>
                  <td className="px-3 py-1.5 text-mid">{n.nrcName}</td>
                  <td className="px-3 py-1.5 text-dim">{fmtRelMs(n.firstSeenMs)}</td>
                  <td className="px-3 py-1.5 text-dim">{n.suppressedAfterCount} attempts</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ── FPS Timeline ──

function FpsChart({ data }: { data: { ts: number; fps: number }[] }) {
  const chartData = useMemo(() => {
    const t0 = data[0]?.ts ?? 0
    return data.map((d) => ({ ts: (d.ts - t0) / 1000, fps: d.fps }))
  }, [data])

  const fmtTime = (sec: number) => {
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}:${s.toString().padStart(2, '0')}`
  }

  return (
    <>
      <SectionLabel>App FPS Timeline</SectionLabel>
      <TimeSeriesChart
        data={chartData}
        series={[{ key: 'fps', label: 'FPS', color: colors.ok }]}
        xFormatter={fmtTime}
        height={140}
      />
    </>
  )
}

/** Format relative milliseconds as m:ss. */
function fmtRelMs(ms: number): string {
  const sec = Math.floor(ms / 1000)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}
