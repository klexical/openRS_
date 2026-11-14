import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, cleanup, fireEvent } from '@testing-library/react'
import { useStore } from '../../store'
import type { Session } from '../../types/session'
import { SessionsPanel } from './SessionsPanel'
import { storeWithSessions } from '../../test/storeState'

const db = vi.hoisted(() => ({ deleteDrives: vi.fn() }))
vi.mock('../../lib/storage', async (importOriginal) => ({ ...(await importOriginal<object>()), deleteDrives: db.deleteDrives }))

const session = (id: string, name: string, importedAt: number) =>
  ({ id, name, importedAt, tags: [], trip: null, diagnostics: null,
    meta: { appVersion: '2.2.7', firmwareVersion: 'unknown', sessionStart: '', exportedAt: '' } }) as unknown as Session
const rowBoxes = (c: HTMLElement) => [...c.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')].slice(1)   // [0] = Select All

beforeEach(() => {
  db.deleteDrives.mockReset().mockResolvedValue(undefined)
  useStore.setState(storeWithSessions([session('a', 'Alpha run', 3), session('b', 'Bravo run', 2), session('c', 'Charlie run', 1)], 'b'))
})
afterEach(() => { cleanup(); vi.useRealTimers() })

describe('SessionsPanel', () => {
  // #384: the selected drives are deleted in one transaction (each one's three records): all of them, or none
  it('bulk delete removes every selected drive in one call', async () => {
    const { container, getByText } = render(<SessionsPanel />)
    fireEvent.click(rowBoxes(container)[0]); fireEvent.click(rowBoxes(container)[2])
    await act(async () => { fireEvent.click(getByText('Delete Selected')) })
    expect(db.deleteDrives).toHaveBeenCalledTimes(1)
    expect([...db.deleteDrives.mock.calls[0][0]].sort()).toEqual(['a', 'c'])
    expect(useStore.getState().drives.map((s) => s.id)).toEqual(['b'])
    expect(useStore.getState().activeSessionId).toBe('b')
  })

  it('a failed bulk delete deletes nothing: every drive stays listed and selected for a retry', async () => {
    db.deleteDrives.mockRejectedValue(new Error('locked'))
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { container, getByText } = render(<SessionsPanel />)
    for (const box of rowBoxes(container)) fireEvent.click(box)
    await act(async () => { fireEvent.click(getByText('Delete Selected')) })
    expect(useStore.getState().drives.map((s) => s.id)).toEqual(['a', 'b', 'c'])
    expect(rowBoxes(container).every((b) => b.checked)).toBe(true)
    expect(container.textContent).toContain('3 selected')
    expect(container.textContent).toMatch(/Couldn't delete the selected drives: nothing was deleted/)   // says so (#384)
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
