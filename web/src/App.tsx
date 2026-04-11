import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { useSettings } from './store/settings'
import { getAllSessions } from './lib/db'
import { rsThemes, shadeHex } from './styles/tokens'

export default function App() {
  const setSessions = useStore((s) => s.setSessions)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)
  const themeId = useSettings((s) => s.themeId)

  // Load persisted sessions from IndexedDB on mount
  useEffect(() => {
    getAllSessions()
      .then((sessions) => {
        setSessions(sessions)
        // Honour a persisted activeSessionId if its session still exists,
        // otherwise fall back to the most-recently imported session.
        if (sessions.length === 0) return
        const persistedId = useStore.getState().activeSessionId
        const persistedExists = persistedId && sessions.some((s) => s.id === persistedId)
        if (!persistedExists) {
          const latest = [...sessions].sort((a, b) => b.importedAt - a.importedAt)[0]
          setActiveSessionId(latest.id)
        }
      })
      .catch((err) => {
        console.error('[Sapphire] Failed to load sessions from IndexedDB:', err)
      })
  }, [setSessions, setActiveSessionId])

  // Apply RS paint accent to CSS custom properties on the document root.
  // Tailwind v4 utilities (`text-accent`, `bg-accent/10`, etc.) resolve
  // `--color-accent` at runtime, so updating it here propagates everywhere.
  useEffect(() => {
    const accent = rsThemes[themeId]
    document.documentElement.style.setProperty('--color-accent', accent)
    document.documentElement.style.setProperty('--color-accent-d', shadeHex(accent, -0.25))
  }, [themeId])

  return <Shell />
}
