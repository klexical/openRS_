import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { BackupButton } from './LazyBackupControls'

// QA F12: the backup controls' fallback, for a tab left open across a deploy (the controls' chunk is gone), takes the
// palette's warning text: look A's class on the new screens, the root token's class in the storage bar. A class check
// only: jsdom has no stylesheet, so the colours themselves are measured in a real browser (the AA audit, aa-08.mjs).

vi.mock('./BackupControls', () => { throw new Error('invented: the chunk is gone') })
afterEach(cleanup)

describe('QA F12: the backup controls\' fallback in the palette\'s warning text', () => {
  it('on a look A screen (Settings, Clear all): look A\'s warning text', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    render(<BackupButton label="Back up all drives" lookA />)
    const line = await screen.findByText('Sapphire was updated: reload this tab to back up or restore.')
    warn.mockRestore()
    expect(line).toHaveClass('a-failed')
    expect(line).not.toHaveClass('text-warn')
  })

  it('in the storage bar: the root\'s warning text', async () => {
    render(<BackupButton label="Back up the old drives" afterFailedUpgrade />)
    const line = await screen.findByText('Sapphire was updated: reload this tab to back up or restore.')
    expect(line).toHaveClass('text-warn-text')
  })
})
