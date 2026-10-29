// Host test (#125): CAN 0x1C0 frames through the real focusrs.c parser (frs_parse_can_frame) → the real ESC task
// (frs_set_esc → frs_esc_mode_task: presses, confirmation wait) → boot ESC persistence (frs_nvs_save_esc). FreeRTOS,
// TWAI and NVS are host stubs (tests/stubs); time is a fake clock advanced by vTaskDelay. Nothing is built for or
// flashed to an adapter. Run: firmware/tests/run.sh
#include <stdio.h>
#include <string.h>
#include "focusrs.h"
#include "freertos/task.h"

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else printf("ok   %s\n", msg); } while (0)

// ── Fake clock + scheduled 0x1C0 frames ─────────────────────────────────────
static uint32_t now_ms;
typedef struct { uint32_t at; uint8_t raw; } sched_t;
static sched_t sched[256]; static int n_sched, next_sched;
static void schedule(uint32_t at, uint8_t raw) { sched[n_sched].at = at; sched[n_sched].raw = raw; n_sched++; }
/** 0x1C0 with ESCMode raw value [raw] (byte 1 bits 5–4, MSB-first 10|2). */
static void deliver_1c0(uint8_t raw) { uint8_t d[8] = {0}; d[1] = (uint8_t)(raw << 4); frs_parse_can_frame(0x1C0, d, 8); }
void host_task_delay(TickType_t ticks) {
    now_ms += ticks;
    while (next_sched < n_sched && sched[next_sched].at <= now_ms) deliver_1c0(sched[next_sched++].raw);
}
static TaskFunction_t pending_fn; static void *pending_arg;
BaseType_t host_task_create(TaskFunction_t fn, const char *name, uint32_t stack, void *arg, int prio, TaskHandle_t *h) {
    (void)name; (void)stack; (void)prio; (void)h; pending_fn = fn; pending_arg = arg; return pdPASS;
}

// ── NVS / UDS / CAN TX stubs ────────────────────────────────────────────────
static int esc_saves; static int saved_esc = -1; static int tx_frames;
void frs_nvs_load(frs_state_t *s) { (void)s; }
void frs_nvs_save_boot_mode(uint8_t m) { (void)m; }
void frs_nvs_save_esc(uint8_t m) { esc_saves++; saved_esc = m; }
void frs_nvs_save_lc(bool e) { (void)e; }
void frs_nvs_save_ass_kill(bool e) { (void)e; }
void frs_nvs_save_sleep_threshold(uint16_t mv) { (void)mv; }
void frs_uds_init(void) {}
void frs_uds_handle_response(uint32_t id, const uint8_t *d, uint8_t n) { (void)id; (void)d; (void)n; }
void frs_uds_probe_abs(void) {}
static int tx(uint32_t id, const uint8_t *d, uint8_t n, uint32_t t) { (void)id; (void)d; (void)n; (void)t; tx_frames++; return 0; }

/** Fresh session in ESC On with a 0x260 template; runs one ESC request to [target] with [frames] scheduled. */
static void run_request(uint8_t target, const sched_t *frames, int n) {
    static int inited = 0;
    if (!inited) { frs_init(); frs_set_can_tx_fn(tx); inited = 1; }
    now_ms = 0; n_sched = next_sched = 0; esc_saves = 0; saved_esc = -1; tx_frames = 0; pending_fn = NULL;
    uint8_t body[8] = {0}; frs_parse_can_frame(FRS_CAN_ID_BODY_CTRL, body, 8);   // 0x260 template (no button bits)
    deliver_1c0(0);                                                          // car reports ESC On before the request
    for (int i = 0; i < n; i++) schedule(frames[i].at, frames[i].raw);
    if (!frs_set_esc(target) || !pending_fn) { printf("FAIL: request not accepted\n"); failures++; return; }
    pending_fn(pending_arg);                                                 // the ESC task, start to finish
}

int main(void) {
    // ESC On → Off = one 5 s long press (+10 ms), then up to 3 s for a 0x1C0 frame reporting Off.
    {   // 1. Confirmed: a post-press frame reports Off
        sched_t f[] = { {5500, 1} };
        run_request(FRS_ESC_OFF, f, 1);
        CHECK(tx_frames > 0, "presses were sent on 0x260");
        CHECK(esc_saves == 1 && saved_esc == FRS_ESC_OFF, "post-press 0x1C0 reports Off -> boot ESC saved");
    }
    {   // 2. Reserved raw 3 after the presses must not confirm a mode reported before them
        sched_t f[] = { {4000, 1}, {5200, 3}, {5300, 3}, {5400, 3}, {6000, 3} };
        run_request(FRS_ESC_OFF, f, 5);
        CHECK(esc_saves == 0, "Off reported during the presses + only raw-3 frames after -> not saved");
        CHECK(frs_get_state()->esc_mode == FRS_ESC_OFF, "raw 3 keeps the last decoded mode");
    }
    {   // 3. Only raw 3 after the presses
        sched_t f[] = { {5200, 3}, {5600, 3} };
        run_request(FRS_ESC_OFF, f, 2);
        CHECK(esc_saves == 0, "only raw-3 frames after the presses -> not saved");
    }
    {   // 4. No 0x1C0 frame after the presses
        run_request(FRS_ESC_OFF, NULL, 0);
        CHECK(esc_saves == 0, "no 0x1C0 frame after the presses -> not saved");
    }
    {   // 5. The car reports another mode
        sched_t f[] = { {5500, 2} };
        run_request(FRS_ESC_OFF, f, 1);
        CHECK(esc_saves == 0, "post-press frame reports Sport, target Off -> not saved");
    }
    {   // 6. raw 3 then a real confirmation
        sched_t f[] = { {5200, 3}, {5800, 1} };
        run_request(FRS_ESC_OFF, f, 2);
        CHECK(esc_saves == 1 && saved_esc == FRS_ESC_OFF, "raw 3, then Off -> saved on the Off frame");
    }
    printf("%s (%d failure(s))\n", failures ? "FAILED" : "PASSED", failures);
    return failures ? 1 : 0;
}
