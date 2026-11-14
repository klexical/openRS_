import { chartColors, colors } from '../styles/tokens'

// Session event types as the app writes them (upper case), grouped for the Diagnostics events table (#382). The list
// comes from the Android source (DiagnosticLogger.event / eventAt and its EVENT_* constants, SessionEvent, ObserverNote);
// components/panels/DiagnosticsPanel.eventColours.test.tsx holds the same list. Lookup ignores letter case; a type
// not listed (a newer app, or one Sapphire does not know yet) gets the neutral style.
// Colours are fixed tokens, never the paint accent, and each meets WCAG AA (4.5:1) as badge text on its own 8 % tint
// over surf2 (#382).

export interface EventStyle {
  /** The group in words: shown on hover, so the colour is never the only cue. */
  group: string
  color: string
}

const GROUPS: { group: string; color: string; types: string[] }[] = [
  { group: 'Error', color: colors.orange,
    types: ['EXPORT_ERROR', 'DRIVE_EXPORT_ERROR', 'BATCH_EXPORT_ERROR', 'LINK_START_REFUSED'] },
  { group: 'Connection', color: colors.cyan,
    types: ['SESSION', 'STATE', 'DEVICE_STATUS', 'FGS_LOCATION', 'LINK_STOP', 'LINK_STOP_IDLE', 'LINK_PREV',
      'LINK_PREV_EXIT', 'LINK_TASK_REMOVED'] },
  { group: 'Adapter', color: colors.mid, types: ['FIRMWARE', 'SLCAN', 'BLE_WRITE', 'FRAMING'] },
  { group: 'Command to the car', color: colors.warn, types: ['DM_CMD', 'ESC_CMD'] },
  { group: 'Module query', color: chartColors[6],
    types: ['DTC', 'AUTO_DTC_SCAN', 'PROBE', 'NRC_RECOVER', 'NRC_SUPPRESS', 'NRC_UNATTRIBUTED', 'BOOST_SOURCE'] },
  { group: 'Recording', color: colors.ok, types: ['REC', 'REC_GAP', 'LAP', 'SHARE', 'DEMO'] },
  { group: 'Observer note', color: chartColors[4], types: ['NOTE'] },
]

const BY_TYPE = new Map(GROUPS.flatMap((g) => g.types.map((t) => [t, { group: g.group, color: g.color }] as const)))

/** Neutral style for a type Sapphire does not know. */
export const NEUTRAL_EVENT_STYLE: EventStyle = { group: 'Other', color: colors.frost }

export function eventStyle(type: string): EventStyle {
  return BY_TYPE.get(type.toUpperCase()) ?? NEUTRAL_EVENT_STYLE
}
