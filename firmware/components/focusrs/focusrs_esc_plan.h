#pragma once
// ESC change planning + persistence decision (#125) — pure functions, no ESP-IDF dependencies, so the decisions are
// unit-tested on the host (firmware/tests/run.sh). The CAN injection itself stays in focusrs.c.

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    FRS_ESC_PRESS_SHORT = 1,   // FRS_ESC_SHORT_PRESS_MS
    FRS_ESC_PRESS_LONG  = 2,   // FRS_ESC_LONG_PRESS_MS (ESC Off)
} frs_esc_press_t;

// Button presses for current → target (Focus RS: short toggles On ↔ Sport, long (~5 s) = Off, short from Off = On).
// Writes up to 2 presses to `presses`; returns the count (0 = already there, -1 = invalid mode).
int frs_esc_plan(uint8_t current, uint8_t target, frs_esc_press_t presses[2]);

// Whether the requested mode may be saved as boot_esc: only when every planned press was sent (a valid 0x260
// template, no TX abort) AND a 0x1C0 frame received after the presses reported the target. Anything else — no
// template, aborted sequence, no frame, a different mode — leaves boot_esc unchanged.
bool frs_esc_should_persist(bool presses_sent, bool frame_after_presses, uint8_t observed, uint8_t target);

#ifdef __cplusplus
}
#endif
