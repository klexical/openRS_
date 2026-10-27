import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { useSettings } from './store/settings'
import { getAllSessions } from './lib/db'
import { rsThemes, shadeHex, printAccent } from './styles/tokens'

export default function App() {
  const setSessions = useStore((s) => s.setSessions)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const themeId = useSettings((s) => s.themeId)

  // Load persisted sessions from IndexedDB on mount
  useEffect(() => {
    getAllSessions().then((sessions) => {
      setSessions(sessions)
      // Auto-select the most recent session
      if (sessions.length > 0) {
        const latest = sessions.sort((a, b) => b.importedAt - a.importedAt)[0]
        setActiveSessionId(latest.id)
      }
    })
  }, [setSessions, setActiveSessionId])

  // RS paint accent → CSS custom properties on the document root. Tailwind utilities (`text-accent`,
  // `bg-accent/10`, …) and `colors.accent` (a var()) resolve them at runtime, so this repaints everything.
  useEffect(() => {
    const accent = rsThemes[themeId] ?? rsThemes.cyan
    const root = document.documentElement.style
    root.setProperty('--color-accent', accent)
    root.setProperty('--color-accent-d', shadeHex(accent, -0.25))
    root.setProperty('--color-accent-print', printAccent(accent))
  }, [themeId])

  return <Shell />
}
