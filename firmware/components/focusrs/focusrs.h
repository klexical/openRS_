#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ── Firmware version (returned to Android app via OPENRS? probe) ───────────
#define OPENRS_FW_VERSION   "v1.2"

// ── Drive mode values ──────────────────────────────────────────
#define FRS_MODE_NORMAL  0
#define FRS_MODE_SPORT   1
#define FRS_MODE_TRACK   2
#define FRS_MODE_DRIFT   3

// ── ESC values ─────────────────────────────────────────────────
#define FRS_ESC_ON       0
#define FRS_ESC_SPORT    1
#define FRS_ESC_OFF      2

// ── CAN IDs ────────────────────────────────────────────────────
// HS-CAN (OBD pins 6/14 @ 500kbps)
// 0x1B0: drive mode status + button event frame.
//   Byte 4 == 0x00 → steady-state; byte 6 upper nibble = mode (0=Normal 1=Sport 2=Track 3=Drift).
//   Byte 4 != 0x00 → button event transition; used for WRITE template capture only.
// 0x17E (DriveModeRequest) ONLY reflects Normal/Sport — Track and Drift are absent.
//   Confirmed via live log cross-reference: 0x1B0 byte6=0x20 (Track) while 0x17E stayed at nibble=1.
#define FRS_CAN_ID_AWD_MSG    0x1B0   // Drive mode status + button event frame
#define FRS_CAN_ID_ESC_ABS    0x1C0   // ESC mode (bits 13–14)

// Drive mode button byte values (0x1B0 byte 1)
#define FRS_BUTTON_RELEASED   0x5A
#define FRS_BUTTON_PRESSED    0x5E
#define FRS_BUTTON_HOLD_MS    150     // Duration to hold press frame

// ── State struct ───────────────────────────────────────────────
typedef struct {
    uint8_t  drive_mode;          // Current drive mode (FRS_MODE_*)
    uint8_t  boot_mode;           // Mode to apply on next ignition (from NVS)
    uint8_t  esc_mode;            // ESC mode (FRS_ESC_*)
    bool     lc_enabled;          // Launch control
    bool     ass_kill;            // Auto Start/Stop kill
    uint32_t battery_mv;          // 12V battery in millivolts
    uint32_t sleep_threshold_mv;  // Sleep below this voltage (default 12200)
    // 0x1B0 frame template — captured from live vehicle, populated at runtime
    uint8_t  frame_1b0_template[8];
    bool     frame_template_valid;
} frs_state_t;

// ── CAN TX callback (set once from main after CAN is initialised) ─────────
// Signature: (can_id, data, dlc, timeout_ms) → 0 on success
typedef int (*frs_can_tx_fn_t)(uint32_t can_id, const uint8_t *data, uint8_t dlc, uint32_t timeout_ms);
void     frs_set_can_tx_fn(frs_can_tx_fn_t fn);

// ── Public API ────────────────────────────────────────────────
void     frs_init(void);
void     frs_parse_can_frame(uint32_t can_id, const uint8_t *data, uint8_t dlc);
void     frs_set_drive_mode(uint8_t mode);   // sends CAN + updates NVS boot mode
void     frs_set_esc(uint8_t esc_mode);
void     frs_set_lc(bool enabled);
void     frs_set_ass_kill(bool enabled);
frs_state_t *frs_get_state(void);

// ── REST handler helpers ─────────────────────────────────────
void     frs_handle_settings_post(const char *key, const char *value);

#ifdef __cplusplus
}
#endif
