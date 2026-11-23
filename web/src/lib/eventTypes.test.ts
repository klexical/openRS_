import { describe, it, expect } from 'vitest'
import { colors } from '../styles/tokens'
import { eventStyle } from './eventTypes'

// #393: the app writes REC_GAP for three reasons (RecordingGapMonitor.Reason on staging: DISCONNECTED, PAUSED,
// NO_LOCATION), plus a notice while a missing position update is still going on. The glossary's "recording gap" is the
// adapter-disconnected reason only, so the group name follows the message's leading token. Invented drive numbers and
// durations; the wording after the token is the app's (Gap.describe and noLocationNotice).
const DISCONNECTED = 'drive 3: DISCONNECTED gap 12.0 s (adapter disconnected — samples skipped)'
const PAUSED = 'drive 3: PAUSED gap 30.0 s (recording paused)'
const NO_LOCATION = 'drive 3: NO_LOCATION gap 8.0 s (no location update while connected — telemetry not recorded)'
const NO_LOCATION_NOTICE = 'drive 3: no location update for 6 s while connected — drive points paused until location returns'

describe('#393 REC_GAP groups by its reason', () => {
  it('only the adapter-disconnected reason is called a recording gap', () => {
    expect(eventStyle('REC_GAP', DISCONNECTED).group).toBe('Recording gap')
    for (const m of [PAUSED, NO_LOCATION, NO_LOCATION_NOTICE]) expect(eventStyle('REC_GAP', m).group).not.toMatch(/recording gap/i)
  })

  it('a pause is a pause, and both missing-fix forms are a missing position update, not a pause', () => {
    expect(eventStyle('REC_GAP', PAUSED).group).toBe('Recording paused')
    expect(eventStyle('REC_GAP', NO_LOCATION).group).toBe('No position update')
    // the notice holds the word "paused"; the leading words decide, never a search for "paused"
    expect(eventStyle('REC_GAP', NO_LOCATION_NOTICE).group).toBe('No position update')
  })

  it('every reason keeps the warning colour, the disconnected one included', () => {
    for (const m of [DISCONNECTED, PAUSED, NO_LOCATION, NO_LOCATION_NOTICE]) expect(eventStyle('REC_GAP', m).color).toBe(colors.warn)
  })

  it('a message without the drive prefix reads the same, and any letter case of the type works', () => {
    expect(eventStyle('rec_gap', 'DISCONNECTED gap 12.0 s (adapter)').group).toBe('Recording gap')
    expect(eventStyle('REC_GAP', 'PAUSED gap 30.0 s').group).toBe('Recording paused')
  })

  it('a reason Sapphire cannot read, or no message, is named without the glossary term and keeps the warning colour', () => {
    for (const m of ['drive 1: LINK gap 6.0 s (link down)', 'recording paused DISCONNECTED gap', '', undefined]) {
      expect(eventStyle('REC_GAP', m)).toEqual({ group: 'Recording interrupted', color: colors.warn })
    }
  })

  it('other types ignore the message', () => {
    expect(eventStyle('REC', PAUSED)).toEqual(eventStyle('REC'))
    expect(eventStyle('SESSION', DISCONNECTED)).toEqual(eventStyle('SESSION'))
  })
})
