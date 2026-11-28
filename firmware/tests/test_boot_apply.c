// Host test (#628): adapter start-up sends no drive mode, ESC or button command to the car on its own (the read-only
// ABS UDS probe remains; stubbed here). Runs the real focusrs.c (CAN parser, boot
// apply task, drive mode task, ESC task) and the real focusrs_nvs.c against an in-memory NVS (tests/stubs/nvs.h).
// FreeRTOS, TWAI and ESP log are host stubs; time is a fake clock advanced by vTaskDelay; created tasks run in order.
// Nothing is built for or flashed to an adapter. Run: firmware/tests/run.sh
#include <stdio.h>
#include <string.h>
#include "focusrs.h"
#include "freertos/task.h"
#include "nvs.h"

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } else printf("ok   %s\n", msg); } while (0)

// ── In-memory NVS ("openrs" namespace only) ─────────────────────────────────
typedef struct { char key[16]; uint16_t val; int used; } nvs_entry_t;
static nvs_entry_t store[16];
static int nvs_writes;   // set/erase calls (flash writes)
static nvs_entry_t *find(const char *key) {
    for (int i = 0; i < 16; i++) if (store[i].used && strcmp(store[i].key, key) == 0) return &store[i];
    return NULL;
}
static void put(const char *key, uint16_t v) {
    nvs_entry_t *e = find(key);
    for (int i = 0; !e && i < 16; i++) if (!store[i].used) e = &store[i];
    strncpy(e->key, key, sizeof e->key - 1); e->val = v; e->used = 1;
}
static int has(const char *key) { return find(key) != NULL; }
esp_err_t nvs_open(const char *ns, nvs_open_mode_t mode, nvs_handle_t *h) { (void)ns; (void)mode; *h = 1; return ESP_OK; }
esp_err_t nvs_get_u8(nvs_handle_t h, const char *key, uint8_t *out) {
    (void)h; nvs_entry_t *e = find(key); if (!e) return ESP_ERR_NVS_NOT_FOUND; *out = (uint8_t)e->val; return ESP_OK;
}
esp_err_t nvs_get_u16(nvs_handle_t h, const char *key, uint16_t *out) {
    (void)h; nvs_entry_t *e = find(key); if (!e) return ESP_ERR_NVS_NOT_FOUND; *out = e->val; return ESP_OK;
}
esp_err_t nvs_set_u8(nvs_handle_t h, const char *key, uint8_t v) { (void)h; put(key, v); nvs_writes++; return ESP_OK; }
esp_err_t nvs_set_u16(nvs_handle_t h, const char *key, uint16_t v) { (void)h; put(key, v); nvs_writes++; return ESP_OK; }
esp_err_t nvs_erase_key(nvs_handle_t h, const char *key) {
    (void)h; nvs_entry_t *e = find(key); if (!e) return ESP_ERR_NVS_NOT_FOUND; e->used = 0; nvs_writes++; return ESP_OK;
}
esp_err_t nvs_commit(nvs_handle_t h) { (void)h; return ESP_OK; }
void nvs_close(nvs_handle_t h) { (void)h; }

// ── Fake clock, scheduled CAN frames, task queue ────────────────────────────
static uint32_t now_ms;
typedef struct { uint32_t at; uint32_t id; uint8_t d[8]; } sched_t;
static sched_t sched[16]; static int n_sched, next_sched;
static void schedule(uint32_t at, uint32_t id, const uint8_t d[8]) {
    sched[n_sched].at = at; sched[n_sched].id = id; memcpy(sched[n_sched].d, d, 8); n_sched++;
}
void host_task_delay(TickType_t ticks) {
    now_ms += ticks;
    while (next_sched < n_sched && sched[next_sched].at <= now_ms) {
        frs_parse_can_frame(sched[next_sched].id, sched[next_sched].d, 8); next_sched++;
    }
}
static TaskFunction_t q_fn[8]; static void *q_arg[8]; static int q_head, q_tail;
BaseType_t host_task_create(TaskFunction_t fn, const char *name, uint32_t stack, void *arg, int prio, TaskHandle_t *h) {
    (void)name; (void)stack; (void)prio; (void)h; q_fn[q_tail] = fn; q_arg[q_tail] = arg; q_tail++; return pdPASS;
}
static void run_tasks(void) { while (q_head < q_tail) { int i = q_head++; q_fn[i](q_arg[i]); } }

// ── UDS + CAN TX stubs ──────────────────────────────────────────────────────
void frs_uds_init(void) {}
void frs_uds_handle_response(uint32_t id, const uint8_t *d, uint8_t n) { (void)id; (void)d; (void)n; }
void frs_uds_probe_abs(void) {}
static int tx_305, tx_260;
static int tx(uint32_t id, const uint8_t *d, uint8_t n, uint32_t t) {
    (void)d; (void)n; (void)t;
    if (id == FRS_CAN_ID_DRIVE_MODE_BTN) tx_305++;
    if (id == FRS_CAN_ID_BODY_CTRL) tx_260++;
    return 0;
}

static const uint8_t F305[8]  = {0, 0, 0, 0, 0x08, 0, 0, 0};   // button template, no press bit, GUI closed
static const uint8_t F260[8]  = {0};                           // body control template, no button bits
static const uint8_t NORMAL[8] = {0};                          // 0x1B0 steady state, nibble 0
static const uint8_t SPORT[8] = {0, 0, 0, 0, 0, 0, 0x10, 0};   // 0x1B0 nibble 1 (0x420 default detail bit0=0 → Sport)
static const uint8_t ESC_ON[8] = {0};                          // 0x1C0 raw 0
static const uint8_t ESC_OFF[8] = {0, 0x10, 0, 0, 0, 0, 0, 0}; // 0x1C0 raw 1

/** The car is on the bus: templates captured, Normal + ESC On reported. */
static void bus_up(void) {
    frs_parse_can_frame(FRS_CAN_ID_DRIVE_MODE_BTN, F305, 8);
    frs_parse_can_frame(FRS_CAN_ID_BODY_CTRL, F260, 8);
    frs_parse_can_frame(FRS_CAN_ID_AWD_MSG, NORMAL, 8);
    frs_parse_can_frame(FRS_CAN_ID_ESC_ABS, ESC_ON, 8);
}
static void reset_run(void) { now_ms = 0; n_sched = next_sched = 0; tx_305 = tx_260 = 0; nvs_writes = 0; }

int main(void) {
    // An adapter that ran older firmware: last app-set mode Drift, ESC Off, start-stop kill on.
    put("rs_bootmode", FRS_MODE_DRIFT);
    put("rs_esc", FRS_ESC_OFF);
    put("rs_ass_kill", 1);

    frs_init();
    frs_set_can_tx_fn(tx);
    CHECK(!has("rs_bootmode"), "saved drive mode erased at start-up");
    CHECK(!has("rs_esc"), "saved ESC erased at start-up");
    CHECK(has("rs_ass_kill") && frs_get_state_copy().ass_kill, "start-stop kill setting kept");
    CHECK(frs_get_state_copy().esc_mode == FRS_ESC_ON, "ESC state not taken from flash before the car reports it");

    {   // 1. Start-up with the bus already up: no button press is sent to the car
        reset_run(); bus_up();
        frs_boot_apply();
        run_tasks();
        CHECK(tx_305 == 0, "start-up: no drive mode button press on 0x305");
        CHECK(tx_260 == 0, "start-up: no ESC or start-stop button press on 0x260");
        CHECK(frs_get_state_copy().drive_mode == FRS_MODE_NORMAL, "start-up: car stays in Normal");
    }
    {   // 2. A requested drive mode change that the car confirms is not saved for the next start-up
        reset_run(); bus_up();
        schedule(3000, FRS_CAN_ID_AWD_MSG, SPORT);
        CHECK(frs_set_drive_mode(FRS_MODE_SPORT), "drive mode request accepted");
        run_tasks();
        CHECK(tx_305 > 0, "drive mode presses were sent on request");
        CHECK(frs_get_state_copy().drive_mode == FRS_MODE_SPORT, "car confirmed Sport");
        CHECK(!has("rs_bootmode") && nvs_writes == 0, "confirmed drive mode not written to flash");
    }
    {   // 3. A requested ESC change that the car confirms is not saved for the next start-up
        reset_run(); bus_up();
        schedule(5500, FRS_CAN_ID_ESC_ABS, ESC_OFF);
        CHECK(frs_set_esc(FRS_ESC_OFF), "ESC request accepted");
        run_tasks();
        CHECK(tx_260 > 0, "ESC presses were sent on request");
        CHECK(frs_get_state_copy().esc_mode == FRS_ESC_OFF, "car confirmed ESC Off");
        CHECK(!has("rs_esc") && nvs_writes == 0, "confirmed ESC not written to flash");
    }
    {   // 4. A second start-up of the new firmware writes nothing (the keys are already gone)
        reset_run();
        frs_boot_apply();
        bus_up();
        run_tasks();
        CHECK(tx_305 == 0 && tx_260 == 0, "second start-up: no button press sent");
    }

    printf("%s (%d failure(s))\n", failures ? "FAILED" : "PASSED", failures);
    return failures ? 1 : 0;
}
