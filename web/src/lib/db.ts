import { openDB, type IDBPDatabase } from 'idb'
import type { Session } from '../types/session'

const DB_NAME = 'sapphire'
const DB_VERSION = 1
const STORE = 'sessions'

let dbPromise: Promise<IDBPDatabase> | null = null

function getDb() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: 'id' })
        }
      },
    })
  }
  return dbPromise
}

export async function getAllSessions(): Promise<Session[]> {
  const db = await getDb()
  return db.getAll(STORE)
}

export async function putSession(session: Session): Promise<void> {
  const db = await getDb()
  await db.put(STORE, session)
}

export async function deleteSession(id: string): Promise<void> {
  const db = await getDb()
  await db.delete(STORE, id)
}

export async function clearAllSessions(): Promise<void> {
  const db = await getDb()
  await db.clear(STORE)
}
