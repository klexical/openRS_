#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ── Firmware version (returned to Android app via OPENRS? probe) ───────────
#define OPENRS_FW_VERSION   "v1.5"

// ── Drive mode values ──────────────────────────────────────────
// Confirmed from live CAN log (0x1B0 byte6 upper nibble, DBC VAL_ 432):
//   0=Normal  1=Sport  2=Drift  3=Track  (Focus RS specific — Track=3 not in base DBC)
#define FRS_MODE_NORMAL  0
#define FRS_MODE_SPORT   1
#define FRS_MODE_DRIFT   2
#define FRS_MODE_TRACK   3

// ── ESC values ─────────────────────────────────────────────────
#define FRS_ESC_ON       0
#define FRS_ESC_SPORT    1
#define FRS_ESC_OFF      2

// ── CAN IDs ────────────────────────────────────────────────────
// HS-CAN (OBD pins 6/14 @ 500kbps)
//
// 0x1B0: drive mode STATUS frame (read-only).
//   Byte 6 upper nibble = mode (0=Normal 1=Sport 2=Drift 3=Track).
//   Byte 4 == 0x00 → steady-state status.
#define FRS_CAN_ID_AWD_MSG          0x1B0
// 0x1C0: ESC mode status (MSB-first bits 10–11, byte1 bits 5–4).
#define FRS_CAN_ID_ESC_ABS          0x1C0
// 0x305: drive mode BUTTON input frame.
//   Byte 5 bit 2 = button pressed. Confirmed on 2018 Focus RS via SLCAN log.
//   Steady state: byte[4]=0x08; pressed: byte[4]=0x0C.
#define FRS_CAN_ID_DRIVE_MODE_BTN   0x305
// 0x260: body control frame — ESC button + Auto Start/Stop button.
//   ESC Off button: byte 6 bit 4 (data[5] |= 0x10).
//   ASS button:     byte 1 bit 0 (data[0] |= 0x01).
#define FRS_CAN_ID_BODY_CTRL        0x260

// ── Button simulation parameters ───────────────────────────────
// Drive mode button (0x305): byte index 4, bit 2
#define FRS_DM_BTN_BYTE     4
#define FRS_DM_BTN_BIT      0x04

// ESC Off button (0x260): byte index 5, bit 4
#define FRS_ESC_BTN_BYTE    5
#define FRS_ESC_BTN_BIT     0x10

// Auto Start/Stop button (0x260): byte index 0, bit 0
#define FRS_ASS_BTN_BYTE    0
#define FRS_ASS_BTN_BIT     0x01

// Drive mode press: 3 frames at ~80ms intervals (~240ms hold).
#define FRS_BUTTON_TX_COUNT         3
#define FRS_BUTTON_TX_INTERVAL_MS   80

// Drive mode inter-press timing — two-stage for reliability.
// Press 1 opens the mode selector GUI; subsequent presses cycle modes.
#define FRS_DM_ACTIVATION_DELAY_MS  500
#define FRS_DM_CYCLE_DELAY_MS       300

// ESC / ASS injection rate — must outpace BCM's 40 Hz broadcast on 0x260.
// 10 ms intervals = 100 Hz → ABS module sees the pressed bit in >50% of frames.
#define FRS_ESC_BTN_TX_INTERVAL_MS  10
#define FRS_ESC_SHORT_PRESS_MS      300
#define FRS_ESC_LONG_PRESS_MS       5000

// CAN TX — abort a button sequence after this many consecutive failures.
#define FRS_TX_MAX_CONSECUTIVE_ERR  5

// ── UDS diagnostic addresses ──────────────────────────────────
#define FRS_UDS_ABS_TX   0x760    // ABS/AdvanceTrac module request
#define FRS_UDS_ABS_RX   0x768    // ABS/AdvanceTrac module response

// ── State struct ───────────────────────────────────────────────
typedef struct {
    uint8_t  drive_mode;          // Current drive mode from CAN (FRS_MODE_*)
    uint8_t  boot_mode;           // Drive mode to apply on next ignition (NVS)
    uint8_t  esc_mode;            // Current ESC mode from CAN (FRS_ESC_*)
    uint8_t  boot_esc;            // ESC mode to apply on next ignition (NVS)
    bool     lc_enabled;          // Launch control
    bool     ass_kill;            // Auto Start/Stop kill
    uint32_t battery_mv;          // 12V battery in millivolts
    uint32_t sleep_threshold_mv;  // Sleep below this voltage (default 12200)
    // Template frames captured from live CAN bus at runtime
    uint8_t  frame_305_template[8];   // 0x305 drive mode button
    bool     frame_305_valid;
    uint8_t  frame_260_template[8];   // 0x260 body control (ESC + ASS)
    bool     frame_260_valid;
    // CAN bus health
    uint8_t  can_tx_errors;       // Consecutive TX failures (reset on success)
    bool     can_bus_off;         // TWAI controller in bus-off state
    bool     abs_reachable;       // ABS module responded to UDS probe
} frs_state_t;

// ── CAN TX callback (set once from main after CAN is initialised) ─────────
// Signature: (can_id, data, dlc, timeout_ms) → 0 on success
typedef int (*frs_can_tx_fn_t)(uint32_t can_id, const uint8_t *data, uint8_t dlc, uint32_t timeout_ms);
void              frs_set_can_tx_fn(frs_can_tx_fn_t fn);
frs_can_tx_fn_t   frs_get_can_tx(void);

// ── Public API ────────────────────────────────────────────────
void     frs_init(void);
void     frs_parse_can_frame(uint32_t can_id, const uint8_t *data, uint8_t dlc);
void     frs_set_drive_mode(uint8_t mode);   // sends CAN + updates NVS boot mode
void     frs_set_esc(uint8_t esc_mode);      // sends CAN + updates NVS
void     frs_set_lc(bool enabled);
void     frs_set_ass_kill(bool enabled);     // sends CAN + updates NVS
void     frs_set_sleep_threshold(float volts);
void     frs_boot_apply(void);         // Apply NVS settings after CAN templates captured
frs_state_t  frs_get_state_copy(void);  // Thread-safe snapshot
frs_state_t *frs_get_state(void);      // DEPRECATED: not thread-safe on dual-core

// ── UDS diagnostic API ──────────────────────────────────────
void frs_uds_init(void);
void frs_uds_handle_response(uint32_t can_id, const uint8_t *data, uint8_t dlc);
void frs_uds_probe_abs(void);


#ifdef __cplusplus
}
#endif
