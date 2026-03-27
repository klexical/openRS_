import { useState } from 'react'
import { useActiveSession } from '../../store'
import { EmptyState } from '../ui/EmptyState'
import { SectionLabel } from '../ui/SectionLabel'
import { colors } from '../../styles/tokens'

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
      {diag.canInventory.length > 0 && <CanInventoryTable frames={diag.canInventory} />}
      {diag.sessionEvents.length > 0 && <EventsTable events={diag.sessionEvents} />}
      {diag.probeResults.length > 0 && <ProbeTable probes={diag.probeResults} />}
      {diag.decodeTrace.length > 0 && <DecodeTraceTable entries={diag.decodeTrace} />}
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
                  <td className="px-3 py-1.5 text-dim">{new Date(e.ts).toLocaleTimeString()}</td>
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
    s === 'ok' ? colors.ok : s === 'timeout' ? colors.dim : colors.orange

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
                <th className="px-3 py-2">Description</th>
              </tr>
            </thead>
            <tbody>
              {probes.map((p, i) => (
                <tr key={i} className="border-t border-brd hover:bg-surf3/50 transition-colors">
                  <td className="px-3 py-1.5 text-accent">{p.module}</td>
                  <td className="px-3 py-1.5 text-frost">{p.did}</td>
                  <td className="px-3 py-1.5" style={{ color: statusColor(p.status) }}>{p.status}</td>
                  <td className="px-3 py-1.5 text-dim">{p.responseHex}</td>
                  <td className="px-3 py-1.5 text-mid">{p.description}</td>
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
    : entries.slice(-500) // show last 500 by default

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
                  <td className="px-3 py-1.5 text-dim">{new Date(e.ts).toLocaleTimeString()}</td>
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
