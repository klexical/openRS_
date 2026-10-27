import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { useSettings } from './store/settings'
import { getAllSessions } from './lib/db'
import type { Session } from './types/session'
import { rsThemes, shadeHex, printAccent } from './styles/tokens'

export default function App() {
  const setSessions = useStore((s) => s.setSessions)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const themeId = useSettings((s) => s.themeId)

  // Load persisted sessions from IndexedDB on mount. Keep the remembered session if it still exists;
  // otherwise (deleted, or another browser's view) select the most recently imported one, or none.
  useEffect(() => {
    getAllSessions()
      .then((sessions) => {
        setSessions(sessions)
        const saved = useStore.getState().activeSessionId
        if (saved && sessions.some((s) => s.id === saved)) return
        const latest = sessions.reduce<Session | null>((a, b) => (!a || b.importedAt > a.importedAt ? b : a), null)
        setActiveSessionId(latest?.id ?? null)
      })
      .catch((err) => {
        console.error('[Sapphire] Failed to load sessions from IndexedDB:', err)
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
