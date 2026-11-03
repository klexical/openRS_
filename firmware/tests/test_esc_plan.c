// Host unit test for focusrs_esc_plan.c (#125). Run: firmware/tests/run.sh — no ESP-IDF needed, nothing flashed.
#include <stdio.h>
#include "focusrs.h"
#include "focusrs_esc_plan.h"

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else printf("ok   %s\n", msg); } while (0)

int main(void) {
    frs_esc_press_t p[2];
    CHECK(frs_esc_plan(FRS_ESC_ON, FRS_ESC_ON, p) == 0, "already in the mode: no presses");
    CHECK(frs_esc_plan(FRS_ESC_ON, FRS_ESC_OFF, p) == 1 && p[0] == FRS_ESC_PRESS_LONG, "On -> Off: one long press");
    CHECK(frs_esc_plan(FRS_ESC_SPORT, FRS_ESC_OFF, p) == 1 && p[0] == FRS_ESC_PRESS_LONG, "Sport -> Off: one long press");
    CHECK(frs_esc_plan(FRS_ESC_OFF, FRS_ESC_SPORT, p) == 2 && p[0] == FRS_ESC_PRESS_SHORT && p[1] == FRS_ESC_PRESS_SHORT,
          "Off -> Sport: two short presses");
    CHECK(frs_esc_plan(FRS_ESC_ON, FRS_ESC_SPORT, p) == 1 && p[0] == FRS_ESC_PRESS_SHORT, "On -> Sport: one short press");
    CHECK(frs_esc_plan(FRS_ESC_OFF, FRS_ESC_ON, p) == 1 && p[0] == FRS_ESC_PRESS_SHORT, "Off -> On: one short press");
    CHECK(frs_esc_plan(FRS_ESC_ON, 7, p) == -1, "invalid target");

    // The criterion: do not persist unconfirmed requests (old code saved boot_esc after any sequence)
    CHECK(!frs_esc_should_persist(false, true, FRS_ESC_OFF, FRS_ESC_OFF), "no 0x260 template / aborted TX -> not saved");
    CHECK(!frs_esc_should_persist(true, false, FRS_ESC_OFF, FRS_ESC_OFF), "no 0x1C0 frame after the presses -> not saved");
    CHECK(!frs_esc_should_persist(true, true, FRS_ESC_ON, FRS_ESC_OFF), "car still reports another mode -> not saved");
    CHECK(frs_esc_should_persist(true, true, FRS_ESC_OFF, FRS_ESC_OFF), "presses sent and CAN confirms -> saved");

    printf("%s (%d failure(s))\n", failures ? "FAILED" : "PASSED", failures);
    return failures ? 1 : 0;
}
