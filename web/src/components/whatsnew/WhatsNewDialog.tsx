// "What's new" (#384 ticket 09; the owner's pick, mock-up A): a dialog over the page with this update's new things, the
// titles of the web/CHANGELOG.md section that names its release (#416 ticket 4), and one button. Loaded only when shown
// (its text is not in the first visit).
import { useEffect, useRef, type KeyboardEvent } from 'react'
import changelog from '../../../CHANGELOG.md?raw'
import { RELEASE } from '../../lib/whatsNew'
import { releaseTitles } from '../../lib/changelogSection'

// a release with no section shows no titles (a test holds that RELEASE has one: lib/changelogSection.test.ts)
const TITLES = releaseTitles(changelog, RELEASE) ?? { added: [], changed: [] }

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
