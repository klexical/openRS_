import { useEffect } from 'react'
import { Shell } from './components/layout/Shell'
import { useStore } from './store'
import { getAllSessions } from './lib/db'

export default function App() {
  const setSessions = useStore((s) => s.setSessions)
  const setActiveSessionId = useStore((s) => s.setActiveSessionId)

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

  return <Shell />
}
