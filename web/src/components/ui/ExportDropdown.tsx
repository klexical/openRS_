import { useState, useRef, useEffect } from 'react'
import type { Session } from '../../types/session'
import { exportSessionCsv, exportSessionJson } from '../../lib/export'

export function ExportDropdown({ session }: { session: Session }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(!open)}
        className="px-3 py-1.5 rounded-md text-xs font-mono uppercase tracking-wider
                   text-dim border border-brd hover:text-frost hover:border-dim transition-colors"
      >
        Export ▾
      </button>

      {open && (
        <div className="absolute right-0 mt-1 w-40 rounded-md border border-brd bg-surf2 shadow-xl z-50 overflow-hidden">
          {session.trip && (
            <button
              onClick={() => { exportSessionCsv(session); setOpen(false) }}
              className="w-full px-3 py-2 text-left text-xs font-mono text-frost
                         hover:bg-accent/10 transition-colors"
            >
              Trip CSV
            </button>
          )}
          <button
            onClick={() => { exportSessionJson(session); setOpen(false) }}
            className="w-full px-3 py-2 text-left text-xs font-mono text-frost
                       hover:bg-accent/10 transition-colors"
          >
            Full JSON
          </button>
          <button
            onClick={() => { window.print(); setOpen(false) }}
            className="w-full px-3 py-2 text-left text-xs font-mono text-frost
                       hover:bg-accent/10 transition-colors border-t border-brd"
          >
            Print View
          </button>
        </div>
      )}
    </div>
  )
}
