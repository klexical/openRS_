// Clear all's confirmation (#384 ticket 07; the owner's pick, mock-up C): two steps, the backup offered first. Step one:
// back up all drives (then continue) or skip; step two: the sentence, word for word, and Clear all. A sheet from the
// bottom on a phone, a centred box on a desktop. Escape or Cancel closes it with nothing changed.
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { clearAll } from '../../lib/driveLibrary'
import { useStore } from '../../store'
import { BackupButton } from '../ui/LazyBackupControls'

export const CLEAR_SENTENCE = 'Removes every drive and session from this browser. Your settings stay.'

const FOCUSABLE = 'button:not([disabled]), a[href], input:not([disabled])'

export function ClearAllDialog({ what, onClose, onCleared }: {
  /** What is stored, in words ("6 drives", "2 drives and 1 session-only entry"); null while the list is not read yet. */
  what: string | null
  onClose: () => void
  onCleared: () => void
}) {
  const [step, setStep] = useState<1 | 2>(1)
  const [downloaded, setDownloaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  // the database waits for an older tab (QA R2): the clear waits too, and the sheet says what unblocks it
  const blocked = useStore((s) => s.storage.state === 'blocked')
  const box = useRef<HTMLDivElement>(null)
  const cancel = useRef<HTMLButtonElement>(null)
  // step one starts on its first control (the backup); step two on Cancel, never on Clear all: a second Enter or a held
  // key must not clear everything (as ConfirmDialog)
  useEffect(() => {
    if (step === 2) cancel.current?.focus()
    else box.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
  }, [step])

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && !busy) { e.preventDefault(); onClose() }
    if (e.key === 'Tab') {                                       // focus stays in the dialog
      const items = [...(box.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])]
      if (items.length === 0) return
      const at = items.indexOf(document.activeElement as HTMLElement)
      e.preventDefault()
      items[(at + (e.shiftKey ? -1 : 1) + items.length) % items.length]?.focus()
    }
  }
  const clear = async () => {
    setBusy(true); setFailed(false)
    try {
      await clearAll()                                           // the three stores in one transaction, then the list
      onCleared()
    } catch (err) {
      console.warn('[Sapphire] Clear all failed:', err)
      setFailed(true)
      setBusy(false)
    }
  }

  return (
    <div className="a-scrim a-sheet-scrim">
      <div ref={box} role="dialog" aria-modal="true" aria-labelledby="clear-title" className="a-dialog a-sheet" onKeyDown={onKey}>
        <ol className="a-steps" aria-label="Steps">
          <li aria-current={step === 1 ? 'step' : undefined}>1 Back up</li>
          <li aria-current={step === 2 ? 'step' : undefined}>2 Clear</li>
        </ol>
        {step === 1 ? <>
          <h2 id="clear-title">Back up before clearing?</h2>
          <p>A backup is one file with every drive and its log. It can be restored into Sapphire later.</p>
          <div className="a-stack">
            {/* the way on says "Continue" only once the file's link was followed: a file built but not saved is no backup */}
            <BackupButton label="Back up all drives" buttonClass="a-btn primary" onDownloaded={() => setDownloaded(true)} lookA />
            <button type="button" className={downloaded ? 'a-btn primary' : 'a-btn'} onClick={() => setStep(2)}>
              {downloaded ? 'Continue' : 'Skip the backup'}</button>
            <button type="button" className="a-btn" onClick={onClose}>Cancel</button>
          </div>
        </> : <>
          <h2 id="clear-title">{what ? `Clear all ${what}?` : 'Clear all drives and sessions?'}</h2>
          <p>{CLEAR_SENTENCE}</p>
          {/* the owner's words (2026-11-15, Q4): a page cannot know the file was saved */}
          {downloaded && <p className="a-meta">The backup file was offered for download. Check it was saved before you clear.</p>}
          {failed && <p role="alert" className="a-notice">Nothing was cleared: this browser's storage did not answer. Try again.</p>}
          {busy && blocked && <p role="status" className="a-notice">Close Sapphire's other tabs: Clear all continues once they are closed.</p>}
          <div className="a-stack">
            <button type="button" className="a-btn danger strong" disabled={busy} onClick={() => void clear()}>Clear all</button>
            <button type="button" className="a-btn" disabled={busy} onClick={() => setStep(1)}>Back</button>
            <button ref={cancel} type="button" className="a-btn" disabled={busy} onClick={onClose}>Cancel</button>
          </div>
        </>}
      </div>
    </div>
  )
}
