#include "focusrs_esc_plan.h"
#include "focusrs.h"

int frs_esc_plan(uint8_t current, uint8_t target, frs_esc_press_t presses[2]) {
    if (current > FRS_ESC_OFF || target > FRS_ESC_OFF) return -1;
    if (current == target) return 0;
    if (target == FRS_ESC_OFF) { presses[0] = FRS_ESC_PRESS_LONG; return 1; }
    if (target == FRS_ESC_SPORT && current == FRS_ESC_OFF) {      // Off → On → Sport
        presses[0] = FRS_ESC_PRESS_SHORT; presses[1] = FRS_ESC_PRESS_SHORT; return 2;
    }
    presses[0] = FRS_ESC_PRESS_SHORT;                              // On ↔ Sport, Off → On
    return 1;
}

bool frs_esc_should_persist(bool presses_sent, bool frame_after_presses, uint8_t observed, uint8_t target) {
    return presses_sent && frame_after_presses && observed == target && target <= FRS_ESC_OFF;
}
