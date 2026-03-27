import { useState, useCallback } from 'react'
import { useStore } from '../../store'
import { importZip } from '../../lib/import'
import { putSession } from '../../lib/db'
import { colors } from '../../styles/tokens'

export function ImportPanel() {
  const addSession = useStore((s) => s.addSession)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const setActivePanel = useStore((s) => s.setActivePanel)

  const [dragging, setDragging] = useState(false)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const handleFile = useCallback(async (file: File) => {
    if (!file.name.endsWith('.zip')) {
      setError('Please drop a .zip file exported from the openRS_ app.')
      return
    }

    setImporting(true)
    setError(null)
    setSuccess(null)

    try {
      const session = await importZip(file)
      await putSession(session)
      addSession(session)
      setActiveSessionId(session.id)
      setSuccess(`Imported: ${session.name}`)

      // Auto-navigate to dashboard after short delay
      setTimeout(() => setActivePanel('dashboard'), 1200)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Import failed')
    } finally {
      setImporting(false)
    }
  }, [addSession, setActiveSessionId, setActivePanel])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files[0]
    if (file) handleFile(file)
  }, [handleFile])

  const handleFileInput = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) handleFile(file)
  }, [handleFile])

  return (
    <div className="max-w-2xl mx-auto">
      {/* Drop zone */}
      <div
        className={`
          flex flex-col items-center justify-center gap-4 p-16
          rounded-xl border-2 border-dashed transition-all cursor-pointer
          ${dragging
            ? 'border-accent bg-accent/5'
            : 'border-brd hover:border-dim bg-surf2/50'
          }
        `}
        onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => document.getElementById('file-input')?.click()}
      >
        <span className="text-4xl opacity-40">
          {importing ? '⟳' : dragging ? '⊕' : '⬡'}
        </span>
        <div className="text-center">
          <p className="text-sm text-frost">
            {importing ? 'Importing...' : 'Drop an openRS_ export ZIP here'}
          </p>
          <p className="text-xs text-dim mt-1">
            or click to browse
          </p>
        </div>
        <input
          id="file-input"
          type="file"
          accept=".zip"
          className="hidden"
          onChange={handleFileInput}
        />
      </div>

      {/* Status messages */}
      {error && (
        <div className="mt-4 px-4 py-3 rounded-lg border text-sm font-mono"
             style={{ borderColor: colors.orange + '40', backgroundColor: colors.orange + '08', color: colors.orange }}>
          {error}
        </div>
      )}
      {success && (
        <div className="mt-4 px-4 py-3 rounded-lg border text-sm font-mono"
             style={{ borderColor: colors.ok + '40', backgroundColor: colors.ok + '08', color: colors.ok }}>
          {success}
        </div>
      )}

      {/* Instructions */}
      <div className="mt-8 space-y-3">
        <h3 className="text-xs font-mono uppercase tracking-[0.2em] text-dim">How to export from openRS_</h3>
        <ol className="space-y-2 text-sm text-mid list-decimal list-inside">
          <li>Open the <strong className="text-frost">DIAG</strong> tab in the openRS_ app</li>
          <li>Tap <strong className="text-frost">Capture &amp; Share Snapshot</strong></li>
          <li>Share or save the ZIP file to your computer</li>
          <li>Drop the ZIP file here</li>
        </ol>
        <p className="text-xs text-dim mt-4">
          For trip data, use <strong className="text-frost">SHARE TRIP DATA</strong> from the trip overlay.
        </p>
      </div>
    </div>
  )
}
