import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, cleanup, fireEvent } from '@testing-library/react'
import { useStore } from '../../store'
import type { Session } from '../../types/session'
import { SessionsPanel } from './SessionsPanel'

const db = vi.hoisted(() => ({ deleteSession: vi.fn(), putSession: vi.fn() }))
vi.mock('../../lib/db', () => ({ deleteSession: db.deleteSession, putSession: db.putSession }))

const session = (id: string, name: string, importedAt: number) =>
  ({ id, name, importedAt, tags: [], trip: null, diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } }) as unknown as Session
const rowBoxes = (c: HTMLElement) => [...c.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')].slice(1)   // [0] = Select All

beforeEach(() => {
  db.deleteSession.mockReset()
  useStore.setState({
    sessions: [session('a', 'Alpha run', 3), session('b', 'Bravo run', 2), session('c', 'Charlie run', 1)],
    activeSessionId: 'b', compareSessionIds: [],
  })
})
afterEach(() => { cleanup(); vi.useRealTimers() })

describe('SessionsPanel', () => {
  it('bulk delete removes what IndexedDB deleted and keeps a failed one listed + selected', async () => {
    db.deleteSession.mockImplementation((id: string) => (id === 'b' ? Promise.reject(new Error('locked')) : Promise.resolve()))
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { container, getByText } = render(<SessionsPanel />)
    for (const box of rowBoxes(container)) fireEvent.click(box)
    expect(container.textContent).toContain('3 selected')
    await act(async () => { fireEvent.click(getByText('Delete Selected')) })

    expect(db.deleteSession).toHaveBeenCalledTimes(3)
    expect(useStore.getState().sessions.map((s) => s.id)).toEqual(['b'])      // deleted ones gone, failure kept
    expect(useStore.getState().activeSessionId).toBe('b')
    expect(rowBoxes(container)).toHaveLength(1)
    expect(rowBoxes(container)[0].checked).toBe(true)                         // still selected for a retry
    expect(container.textContent).toContain('1 selected')
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('search filters once typing pauses (SearchBar debounce, 200 ms)', () => {
    vi.useFakeTimers()
    const { container } = render(<SessionsPanel />)
    fireEvent.change(container.querySelector('input[type="text"]')!, { target: { value: 'char' } })
    act(() => { vi.advanceTimersByTime(150) })
    expect(container.textContent).toContain('Alpha run')            // not yet filtered
    act(() => { vi.advanceTimersByTime(50) })
    expect(container.textContent).not.toContain('Alpha run')
    expect(container.textContent).toContain('Charlie run')
  })
})
