// Look after a drive from its row (#384 ticket 05): one menu per row (the visible menu button; right-click with a mouse
// on a desktop; no hand-built long press, owner 2026-11-14), the inline editors it opens, and the confirmation that every
// delete asks first. Keyboard: the menu button opens with Enter, Space or the arrows; inside, the arrows, Home and End
// move, Escape closes and returns to the button; every editor closes with Escape and returns there too.
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { TAG_PRESETS } from '../../lib/tags'

export type RowAction = 'rename' | 'note' | 'tags' | 'csv' | 'json' | 'delete'
const ITEMS: { id: RowAction; label: string; needsDrive?: boolean }[] = [
  { id: 'rename', label: 'Rename…' }, { id: 'note', label: 'Note…' }, { id: 'tags', label: 'Tags…' },
  { id: 'csv', label: 'Export trip CSV', needsDrive: true }, { id: 'json', label: 'Export full JSON' },
  { id: 'delete', label: 'Delete…' },
]


/** The menu of one row: [open] says where focus goes when it opens ('first' | 'last'); null = closed. */
export function RowMenu({ menuId, name, hasDrive, open, onOpen, onClose, onChoose, buttonRef }: {
  menuId: string; name: string; hasDrive: boolean; open: 'first' | 'last' | null
  onOpen: (focus: 'first' | 'last') => void; onClose: (returnFocus: boolean) => void; onChoose: (a: RowAction) => void
  buttonRef: (el: HTMLButtonElement | null) => void
}) {
  const menu = useRef<HTMLDivElement>(null)
  const items = ITEMS.filter((i) => hasDrive || !i.needsDrive)
  const label = `Actions for ${name}`
  const id = menuId

  useEffect(() => {
    if (!open) return
    const all = menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')
    all?.[open === 'first' ? 0 : all.length - 1]?.focus()
    const outside = (e: MouseEvent) => { if (!menu.current?.parentElement?.contains(e.target as Node)) onClose(false) }
    document.addEventListener('mousedown', outside)
    return () => document.removeEventListener('mousedown', outside)
  }, [open, onClose])

  const onButtonKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') { e.preventDefault(); onOpen('first') }
    else if (e.key === 'ArrowUp') { e.preventDefault(); onOpen('last') }
  }
  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const all = [...(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])]
    const at = all.indexOf(document.activeElement as HTMLButtonElement)
    const move = (i: number) => { e.preventDefault(); all[(i + all.length) % all.length]?.focus() }
    if (e.key === 'ArrowDown') move(at + 1)
    else if (e.key === 'ArrowUp') move(at - 1)
    else if (e.key === 'Home') move(0)
    else if (e.key === 'End') move(all.length - 1)
    else if (e.key === 'Escape') { e.preventDefault(); onClose(true) }
    else if (e.key === 'Tab') onClose(false)
  }
  return (
    <div className="a-menuwrap">
      <button ref={buttonRef} type="button" className="a-btn a-menubtn" aria-label={label} aria-haspopup="menu"
        aria-expanded={open !== null} aria-controls={open ? id : undefined}
        onClick={() => (open ? onClose(false) : onOpen('first'))} onKeyDown={onButtonKey}>
        <span aria-hidden="true">⋯</span>
      </button>
      {open && (
        <div ref={menu} id={id} role="menu" aria-label={label} className="a-menu" onKeyDown={onMenuKey}>
          {items.map((i) => (
            <button key={i.id} type="button" role="menuitem" tabIndex={-1} className={i.id === 'delete' ? 'danger' : undefined}
              onClick={() => { onClose(false); onChoose(i.id) }}>{i.label}</button>
          ))}
        </div>
      )}
    </div>
  )
}

/** A text editor in the row (rename: one line; note: several). Enter saves a name; Escape cancels either. */
export function TextEditor({ label, initial, multiline, onSave, onCancel }: {
  label: 'Name' | 'Note'; initial: string; multiline?: boolean; onSave: (text: string) => void; onCancel: () => void
}) {
  const [text, setText] = useState(initial)
  const field = useRef<HTMLInputElement & HTMLTextAreaElement>(null)
  useEffect(() => { field.current?.focus(); field.current?.select() }, [])
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); onCancel() }
    else if (e.key === 'Enter' && !multiline) { e.preventDefault(); onSave(text) }   // a name saves on Enter; a note takes new lines
  }
  return (
    <form className="a-editor" onSubmit={(e) => { e.preventDefault(); onSave(text) }}>
      <label>{label}
        {multiline
          ? <textarea ref={field} rows={3} value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKey} />
          : <input ref={field} type="text" value={text} onChange={(e) => setText(e.target.value)} onKeyDown={onKey} />}
      </label>
      <div className="a-editor-actions">
        <button type="submit" className="a-btn primary">Save</button>
        <button type="button" className="a-btn" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  )
}

/** The tag choices as toggle buttons; each change is saved at once, as today. Done or Escape closes. */
export function TagEditor({ tags, onToggle, onDone }: { tags: string[]; onToggle: (tag: string) => void; onDone: () => void }) {
  const first = useRef<HTMLButtonElement>(null)
  useEffect(() => { first.current?.focus() }, [])
  return (
    <div className="a-editor" onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); onDone() } }}>
      <div role="group" aria-label="Tags" className="a-tagchoices">
        {TAG_PRESETS.map((t, i) => (
          <button key={t} ref={i === 0 ? first : undefined} type="button" className="a-btn a-chip" aria-pressed={tags.includes(t)}
            onClick={() => onToggle(t)}>{t}</button>
        ))}
      </div>
      <div className="a-editor-actions"><button type="button" className="a-btn" onClick={onDone}>Done</button></div>
    </div>
  )
}

/** Every delete asks first (owner, 2026-11-14). Focus starts on Cancel, the safe choice; Escape cancels. */
export function ConfirmDialog({ title, children, confirm, onConfirm, onCancel }: {
  title: string; children: ReactNode; confirm: string; onConfirm: () => void; onCancel: () => void
}) {
  const cancel = useRef<HTMLButtonElement>(null)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => { cancel.current?.focus() }, [])
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); onCancel() }
    if (e.key === 'Tab') {                         // keep focus on the dialog's two buttons
      const buttons = [...(box.current?.querySelectorAll('button') ?? [])]
      const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
      e.preventDefault()
      buttons[(at + (e.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus()
    }
  }
  return (
    <div className="a-scrim">
      <div ref={box} role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-body"
        className="a-dialog" onKeyDown={onKey}>
        <h2 id="confirm-title">{title}</h2>
        <div id="confirm-body">{children}</div>
        <div className="a-editor-actions">
          <button ref={cancel} type="button" className="a-btn" onClick={onCancel}>Cancel</button>
          <button type="button" className="a-btn danger" onClick={onConfirm}>{confirm}</button>
        </div>
      </div>
    </div>
  )
}
