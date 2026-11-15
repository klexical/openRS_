// "What's new" (#384 ticket 09; the owner's pick, mock-up A): a dialog over the page with this update's new things, the
// titles of web/CHANGELOG.md's newest section, and one button. Loaded only when shown (its text is not in the first visit).
import { useEffect, useRef, type KeyboardEvent } from 'react'
import changelog from '../../../CHANGELOG.md?raw'

/** The newest section's entry titles under "Added" and "Changed", in the changelog's order. */
function newestTitles(): { added: string[]; changed: string[] } {
  const start = changelog.indexOf('\n## ')
  const end = changelog.indexOf('\n## ', start + 4)
  const section = changelog.slice(start, end < 0 ? undefined : end)
  const out = { added: [] as string[], changed: [] as string[] }
  let into: string[] | null = null
  for (const line of section.split('\n')) {
    const h = /^### (\w+)/.exec(line)
    if (h) { into = h[1] === 'Added' ? out.added : h[1] === 'Changed' ? out.changed : null; continue }
    const m = /^- \*\*(.+?)\*\*/.exec(line)
    if (m && into) into.push(m[1])
  }
  return out
}
const TITLES = newestTitles()

export function WhatsNewDialog({ onClose }: { onClose: () => void }) {
  const button = useRef<HTMLButtonElement>(null)
  useEffect(() => { button.current?.focus() }, [])
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); onClose() }
    if (e.key === 'Tab') { e.preventDefault(); button.current?.focus() }       // one control: focus stays on it
  }
  return (
    <div className="a-scrim">
      <div role="dialog" aria-modal="true" aria-labelledby="whats-new-title" className="a-dialog a-whatsnew" onKeyDown={onKey}>
        <p className="a-meta">Sapphire was updated</p>
        <h2 id="whats-new-title">What's new</h2>
        <ul className="a-whatsnew-list">{TITLES.added.map((t) => <li key={t}>{t}</li>)}</ul>
        {TITLES.changed.length > 0 && <>
          <h3>Changed</h3>
          <ul className="a-whatsnew-list">{TITLES.changed.map((t) => <li key={t}>{t}</li>)}</ul>
        </>}
        <p className="a-meta">You can read this again in Settings, under About.</p>
        <button ref={button} type="button" className="a-btn primary" onClick={onClose}>Got it</button>
      </div>
    </div>
  )
}
