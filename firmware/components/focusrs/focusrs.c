#include "focusrs.h"
#include "focusrs_nvs.h"
#include "focusrs_uds.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "driver/twai.h"
#include "esp_log.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static const char *TAG = "focusrs";
static SemaphoreHandle_t s_state_mutex = NULL;

// CAN TX callback — set by main via frs_set_can_tx_fn() after CAN is initialised.
static frs_can_tx_fn_t s_can_tx = NULL;

void frs_set_can_tx_fn(frs_can_tx_fn_t fn) {
    s_can_tx = fn;
}

frs_can_tx_fn_t frs_get_can_tx(void) {
    return s_can_tx;
}

static frs_state_t s_state = {
    .drive_mode           = FRS_MODE_NORMAL,
    .boot_mode            = FRS_MODE_NORMAL,
    .mode_420_detail      = 0xC4,
    .esc_mode             = FRS_ESC_ON,
    .boot_esc             = FRS_ESC_ON,
    .lc_enabled           = false,
    .ass_kill             = false,
    .battery_mv           = 12000,
    .sleep_threshold_mv   = 12200,
    .frame_305_valid      = false,
    .frame_260_valid      = false,
    .can_tx_errors        = 0,
    .can_bus_off          = false,
    .abs_reachable        = false,
};

void frs_init(void) {
    s_state_mutex = xSemaphoreCreateMutex();
    if (!s_state_mutex) {
        ESP_LOGE(TAG, "FATAL: failed to create state mutex");
        abort();
    }
    frs_nvs_load(&s_state);
    frs_uds_init();
    ESP_LOGI(TAG, "openrs-fw %s — Focus RS module init", OPENRS_FW_VERSION);
    ESP_LOGI(TAG, "Boot mode: %d, ESC: %d, LC: %d, ASS kill: %d",
             s_state.boot_mode, s_state.esc_mode,
             s_state.lc_enabled, s_state.ass_kill);
}

void frs_parse_can_frame(uint32_t can_id, const uint8_t *data, uint8_t dlc) {
    if (!data) return;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);

    switch (can_id) {

    case FRS_CAN_ID_AWD_MSG:
        if (dlc >= 7 && data[4] == 0x00) {
            uint8_t raw_mode = (data[6] >> 4) & 0x0F;
            if (raw_mode == 0) s_state.drive_mode = FRS_MODE_NORMAL;
            else if (raw_mode == 1) {
                // 0x1B0 nibble=1 is ambiguous (Sport OR Track).
                // Disambiguate via 0x420 detail: bit0=0→Sport, bit0=1→Track.
                s_state.drive_mode = (s_state.mode_420_detail & 0x01)
                    ? FRS_MODE_TRACK : FRS_MODE_SPORT;
            }
            else if (raw_mode == 2) s_state.drive_mode = FRS_MODE_DRIFT;
        }
        break;

    case 0x420:
        if (dlc >= 8) {
            s_state.mode_420_detail = data[7];
            uint8_t b6 = data[6];
            if (b6 == 0x11) {
                s_state.drive_mode = (data[7] & 0x01)
                    ? FRS_MODE_TRACK : FRS_MODE_SPORT;
            }
        }
        break;

    case FRS_CAN_ID_ESC_ABS:
        if (dlc >= 2) {
            uint8_t raw_esc = (data[1] >> 4) & 0x03;
            if (raw_esc == 0) s_state.esc_mode = FRS_ESC_ON;
            else if (raw_esc == 1) s_state.esc_mode = FRS_ESC_OFF;
            else if (raw_esc == 2) s_state.esc_mode = FRS_ESC_SPORT;
        }
        break;

    case FRS_CAN_ID_DRIVE_MODE_BTN:
        if (dlc >= 8 && !(data[FRS_DM_BTN_BYTE] & FRS_DM_BTN_BIT)) {
            memcpy(s_state.frame_305_template, data, 8);
            s_state.frame_305_valid = true;
        }
        break;

    case FRS_CAN_ID_BODY_CTRL:
        if (dlc >= 8 &&
            !(data[FRS_ESC_BTN_BYTE] & FRS_ESC_BTN_BIT) &&
            !(data[FRS_ASS_BTN_BYTE] & FRS_ASS_BTN_BIT)) {
            memcpy(s_state.frame_260_template, data, 8);
            s_state.frame_260_valid = true;
        }
        break;

    case FRS_UDS_ABS_RX:
        xSemaphoreGive(s_state_mutex);
        frs_uds_handle_response(can_id, data, dlc);
        return;

    default:
        break;
    }

    xSemaphoreGive(s_state_mutex);
}

// ── TWAI bus-off recovery ────────────────────────────────────

static void frs_attempt_recovery(void) {
    twai_status_info_t info;
    if (twai_get_status_info(&info) != ESP_OK) return;

    if (info.state == TWAI_STATE_BUS_OFF) {
        ESP_LOGW(TAG, "TWAI bus-off detected (tx_err=%lu rx_err=%lu) — recovering",
                 (unsigned long)info.tx_error_counter,
                 (unsigned long)info.rx_error_counter);

        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        s_state.can_bus_off = true;
        xSemaphoreGive(s_state_mutex);

        twai_initiate_recovery();
        for (int i = 0; i < 50; i++) {
            vTaskDelay(pdMS_TO_TICKS(10));
            if (twai_get_status_info(&info) == ESP_OK &&
                info.state == TWAI_STATE_STOPPED) {
                twai_start();
                ESP_LOGI(TAG, "TWAI recovered and restarted");
                xSemaphoreTake(s_state_mutex, portMAX_DELAY);
                s_state.can_bus_off = false;
                s_state.can_tx_errors = 0;
                xSemaphoreGive(s_state_mutex);
                return;
            }
        }
        ESP_LOGE(TAG, "TWAI recovery timed out — bus may be offline");
    } else if (info.state == TWAI_STATE_RECOVERING) {
        ESP_LOGW(TAG, "TWAI already recovering — waiting");
        for (int i = 0; i < 50; i++) {
            vTaskDelay(pdMS_TO_TICKS(10));
            if (twai_get_status_info(&info) == ESP_OK &&
                info.state == TWAI_STATE_STOPPED) {
                twai_start();
                ESP_LOGI(TAG, "TWAI recovery complete");
                xSemaphoreTake(s_state_mutex, portMAX_DELAY);
                s_state.can_bus_off = false;
                s_state.can_tx_errors = 0;
                xSemaphoreGive(s_state_mutex);
                return;
            }
        }
        ESP_LOGE(TAG, "TWAI RECOVERING wait timed out — bus may be offline");
    }
}

// ── CAN TX with error tracking ──────────────────────────────

static bool frs_tx_frame(uint32_t can_id, const uint8_t *frame, uint8_t dlc) {
    int ret = s_can_tx(can_id, frame, dlc, 100);
    if (ret == 0) {
        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        s_state.can_tx_errors = 0;
        xSemaphoreGive(s_state_mutex);
        return true;
    }

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.can_tx_errors++;
    uint8_t errs = s_state.can_tx_errors;
    xSemaphoreGive(s_state_mutex);

    ESP_LOGW(TAG, "CAN TX failed (id=0x%03lX err=%d consecutive=%d)",
             (unsigned long)can_id, ret, errs);

    if (errs >= FRS_TX_MAX_CONSECUTIVE_ERR) {
        frs_attempt_recovery();
    }
    return false;
}

// ── Generic button press helpers ─────────────────────────────

static void frs_send_button(uint32_t can_id,
                            const uint8_t *tmpl,
                            uint8_t byte_idx,
                            uint8_t bit_mask,
                            uint32_t interval_ms,
                            int count) {
    if (!s_can_tx) {
        ESP_LOGE(TAG, "CAN TX callback not set");
        return;
    }

    uint8_t frame[8];
    memcpy(frame, tmpl, 8);
    frame[byte_idx] |= bit_mask;

    for (int i = 0; i < count; i++) {
        if (!frs_tx_frame(can_id, frame, 8)) {
            xSemaphoreTake(s_state_mutex, portMAX_DELAY);
            bool fatal = s_state.can_tx_errors >= FRS_TX_MAX_CONSECUTIVE_ERR;
            xSemaphoreGive(s_state_mutex);
            if (fatal) {
                ESP_LOGE(TAG, "Aborting button sequence — too many TX errors");
                return;
            }
        }
        if (i < count - 1) {
            vTaskDelay(pdMS_TO_TICKS(interval_ms));
        }
    }
    vTaskDelay(pdMS_TO_TICKS(interval_ms));
}

static void frs_send_button_long(uint32_t can_id,
                                 const uint8_t *tmpl,
                                 uint8_t byte_idx,
                                 uint8_t bit_mask,
                                 uint32_t interval_ms,
                                 uint32_t duration_ms) {
    if (!s_can_tx) {
        ESP_LOGE(TAG, "CAN TX callback not set");
        return;
    }

    uint8_t frame[8];
    memcpy(frame, tmpl, 8);
    frame[byte_idx] |= bit_mask;

    uint32_t elapsed = 0;
    while (elapsed < duration_ms) {
        if (!frs_tx_frame(can_id, frame, 8)) {
            xSemaphoreTake(s_state_mutex, portMAX_DELAY);
            bool fatal = s_state.can_tx_errors >= FRS_TX_MAX_CONSECUTIVE_ERR;
            xSemaphoreGive(s_state_mutex);
            if (fatal) {
                ESP_LOGE(TAG, "Aborting long press — too many TX errors");
                return;
            }
        }
        vTaskDelay(pdMS_TO_TICKS(interval_ms));
        elapsed += interval_ms;
    }
    vTaskDelay(pdMS_TO_TICKS(interval_ms));
}

// ── Drive mode write ─────────────────────────────────────────
// Copies template under mutex to avoid torn reads on dual-core S3.
static void frs_send_dm_button(void) {
    uint8_t tmpl[8];
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (!s_state.frame_305_valid) {
        xSemaphoreGive(s_state_mutex);
        ESP_LOGW(TAG, "No 0x305 template yet — cannot simulate drive mode button");
        return;
    }
    memcpy(tmpl, s_state.frame_305_template, 8);
    xSemaphoreGive(s_state_mutex);

    frs_send_button_long(FRS_CAN_ID_DRIVE_MODE_BTN, tmpl,
                         FRS_DM_BTN_BYTE, FRS_DM_BTN_BIT,
                         FRS_BUTTON_TX_INTERVAL_MS,
                         FRS_BUTTON_TX_DURATION_MS);
}

static uint8_t s_pending_mode = 0xFF;

// ── Hybrid scroll-then-wait drive mode controller ────────────
// Focus RS mode selector behaviour (owner's manual):
//   1. First press → mode list appears, current mode highlighted
//   2. Each press → highlight scrolls forward (N→S→T→D→N)
//   3. Wait ~4s (or press OK) → highlighted mode is SET, CAN updates
//
// CAN (0x1B0/0x420) does NOT update during scrolling — only after
// the auto-confirm. Previous closed-loop approach retried during the
// confirm window, scrolling past the target (e.g. Sport → Track).
//
// New approach: pre-calculate scroll count, send all presses, then
// wait for auto-confirm. Verify the result; retry if wrong.

static bool frs_dm_is_gui_open(void) {
    bool open = false;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    open = s_state.frame_305_valid &&
        (s_state.frame_305_template[FRS_DM_BTN_BYTE] & FRS_DM_GUI_OPEN_BIT);
    xSemaphoreGive(s_state_mutex);
    return open;
}

static uint8_t frs_dm_read_mode(void) {
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    uint8_t mode = s_state.drive_mode;
    xSemaphoreGive(s_state_mutex);
    return mode;
}

// Button cycle order: Normal(0) → Sport(1) → Track(2) → Drift(3) → Normal
// CAN mode values:    Normal=0,   Sport=1,    Drift=2,    Track=3
// Map CAN mode value to cycle position:
static const uint8_t can_to_pos[] = { 0, 1, 3, 2 };  // N=0, S=1, D=3, T=2
#define DM_CYCLE_LEN 4

static void frs_drive_mode_task(void *arg) {
    uint8_t target = (uint8_t)(uintptr_t)arg;
    uint8_t current = frs_dm_read_mode();

    if (current == target) {
        ESP_LOGI(TAG, "Drive mode: already in %d — no change", current);
        goto done;
    }

    ESP_LOGI(TAG, "Drive mode: %d → %d (scroll-then-wait)", current, target);

    for (int attempt = 0; attempt < FRS_DM_MAX_ATTEMPTS; attempt++) {
        current = frs_dm_read_mode();
        if (current == target) {
            ESP_LOGI(TAG, "Drive mode: target %d reached before attempt %d",
                     target, attempt);
            break;
        }

        // Calculate scroll distance in cycle order.
        uint8_t cur_pos = can_to_pos[current];
        uint8_t tgt_pos = can_to_pos[target];
        uint8_t cycle_dist = (tgt_pos - cur_pos + DM_CYCLE_LEN) % DM_CYCLE_LEN;

        ESP_LOGI(TAG, "Drive mode: attempt %d/%d — cur=%d(pos%d) tgt=%d(pos%d) "
                 "scrolls=%d",
                 attempt + 1, FRS_DM_MAX_ATTEMPTS,
                 current, cur_pos, target, tgt_pos, cycle_dist);

        if (cycle_dist == 0) {
            ESP_LOGW(TAG, "Drive mode: cycle_dist=0 but current!=target — abort");
            goto done;
        }

        // Step 1: Activation press if GUI not already open.
        bool gui_open = frs_dm_is_gui_open();
        if (!gui_open) {
            ESP_LOGI(TAG, "Drive mode: activation press");
            frs_send_dm_button();
            vTaskDelay(pdMS_TO_TICKS(FRS_DM_ACTIVATION_DELAY_MS));
        } else {
            ESP_LOGI(TAG, "Drive mode: GUI already open, skipping activation");
        }

        // Step 2: Send scroll presses (open-loop).
        for (uint8_t i = 0; i < cycle_dist; i++) {
            ESP_LOGI(TAG, "Drive mode: scroll press %d/%d", i + 1, cycle_dist);
            frs_send_dm_button();
            if (i < cycle_dist - 1) {
                vTaskDelay(pdMS_TO_TICKS(FRS_DM_SCROLL_DELAY_MS));
            }
        }

        // Step 3: Wait for auto-confirm — poll CAN for mode change.
        ESP_LOGI(TAG, "Drive mode: waiting up to %dms for auto-confirm",
                 FRS_DM_CONFIRM_WAIT_MS);

        bool mode_changed = false;
        int waited = 0;
        while (waited < FRS_DM_CONFIRM_WAIT_MS) {
            vTaskDelay(pdMS_TO_TICKS(FRS_DM_CONFIRM_POLL_MS));
            waited += FRS_DM_CONFIRM_POLL_MS;
            uint8_t now = frs_dm_read_mode();
            if (now != current) {
                mode_changed = true;
                current = now;
                ESP_LOGI(TAG, "Drive mode: CAN confirmed mode=%d after %dms",
                         current, waited);
                break;
            }
        }

        if (!mode_changed) {
            ESP_LOGE(TAG, "Drive mode: no CAN confirmation after %dms — "
                     "stuck at %d, wanted %d",
                     FRS_DM_CONFIRM_WAIT_MS, current, target);
            goto done;
        }

        // Step 4: Check if we landed on the right mode.
        if (current == target) {
            ESP_LOGI(TAG, "Drive mode: SUCCESS — reached %d on attempt %d",
                     target, attempt + 1);
            xSemaphoreTake(s_state_mutex, portMAX_DELAY);
            s_state.boot_mode = target;
            xSemaphoreGive(s_state_mutex);
            frs_nvs_save_boot_mode(target);
            goto done;
        }

        // Wrong mode confirmed — will retry from current position.
        ESP_LOGW(TAG, "Drive mode: landed on %d instead of %d — "
                 "retrying (%d/%d)",
                 current, target, attempt + 1, FRS_DM_MAX_ATTEMPTS);
    }

    ESP_LOGE(TAG, "Drive mode: exhausted %d attempts — current=%d, wanted %d",
             FRS_DM_MAX_ATTEMPTS, frs_dm_read_mode(), target);

done:
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_pending_mode = 0xFF;
    xSemaphoreGive(s_state_mutex);
    vTaskDelete(NULL);
}

bool frs_set_drive_mode(uint8_t target_mode) {
    if (target_mode > FRS_MODE_TRACK) return false;

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (s_pending_mode != 0xFF) {
        xSemaphoreGive(s_state_mutex);
        ESP_LOGW(TAG, "Drive mode change already in progress");
        return false;
    }
    s_pending_mode = target_mode;
    xSemaphoreGive(s_state_mutex);

    xTaskCreate(frs_drive_mode_task, "frs_dm", 2048,
                (void *)(uintptr_t)target_mode, 5, NULL);
    return true;
}

// ── ESC mode write ───────────────────────────────────────────
// Focus RS ESC button behavior (confirmed via car test):
//   Short press: toggles On ↔ Sport
//   Long press (~5s): activates ESC Off from On or Sport
//   Short press from Off: returns to On
// Injection at 100 Hz (10 ms) to outpace BCM's 40 Hz on 0x260.
static void frs_send_esc_short(void) {
    uint8_t tmpl[8];
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (!s_state.frame_260_valid) {
        xSemaphoreGive(s_state_mutex);
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ESC button");
        return;
    }
    memcpy(tmpl, s_state.frame_260_template, 8);
    xSemaphoreGive(s_state_mutex);

    int count = FRS_ESC_SHORT_PRESS_MS / FRS_ESC_BTN_TX_INTERVAL_MS;
    frs_send_button(FRS_CAN_ID_BODY_CTRL, tmpl,
                    FRS_ESC_BTN_BYTE, FRS_ESC_BTN_BIT,
                    FRS_ESC_BTN_TX_INTERVAL_MS, count);
}

static void frs_send_esc_long(void) {
    uint8_t tmpl[8];
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (!s_state.frame_260_valid) {
        xSemaphoreGive(s_state_mutex);
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ESC long press");
        return;
    }
    memcpy(tmpl, s_state.frame_260_template, 8);
    xSemaphoreGive(s_state_mutex);

    frs_send_button_long(FRS_CAN_ID_BODY_CTRL, tmpl,
                         FRS_ESC_BTN_BYTE, FRS_ESC_BTN_BIT,
                         FRS_ESC_BTN_TX_INTERVAL_MS,
                         FRS_ESC_LONG_PRESS_MS);
}

static uint8_t s_pending_esc = 0xFF;

static void frs_esc_mode_task(void *arg) {
    uint8_t target_esc = (uint8_t)(uintptr_t)arg;

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    uint8_t current = s_state.esc_mode;
    xSemaphoreGive(s_state_mutex);

    if (current == target_esc) {
        ESP_LOGI(TAG, "ESC: already in %d — no change", current);
        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        s_pending_esc = 0xFF;
        xSemaphoreGive(s_state_mutex);
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "ESC mode: %d → %d via 0x260", current, target_esc);

    if (target_esc == FRS_ESC_OFF) {
        ESP_LOGI(TAG, "ESC: long press for Off");
        frs_send_esc_long();
    } else if (target_esc == FRS_ESC_SPORT) {
        if (current == FRS_ESC_OFF) {
            ESP_LOGI(TAG, "ESC: Off → On → Sport (2 short presses)");
            frs_send_esc_short();
            vTaskDelay(pdMS_TO_TICKS(1000));
            frs_send_esc_short();
        } else {
            frs_send_esc_short();
        }
    } else {
        if (current == FRS_ESC_OFF || current == FRS_ESC_SPORT) {
            frs_send_esc_short();
        }
    }

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.boot_esc = target_esc;
    s_pending_esc = 0xFF;
    xSemaphoreGive(s_state_mutex);

    frs_nvs_save_esc(target_esc);
    vTaskDelete(NULL);
}

void frs_set_esc(uint8_t esc_mode) {
    ESP_LOGI(TAG, "frs_set_esc(%d) called — pending=%d, f260=%d, current_esc=%d",
             esc_mode, s_pending_esc, s_state.frame_260_valid, s_state.esc_mode);
    if (esc_mode > FRS_ESC_OFF) {
        ESP_LOGW(TAG, "ESC: invalid mode %d (max=%d)", esc_mode, FRS_ESC_OFF);
        return;
    }

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (s_pending_esc != 0xFF) {
        ESP_LOGW(TAG, "ESC mode change already in progress (pending=%d)", s_pending_esc);
        xSemaphoreGive(s_state_mutex);
        return;
    }
    s_pending_esc = esc_mode;
    xSemaphoreGive(s_state_mutex);

    ESP_LOGI(TAG, "ESC: creating task for mode %d", esc_mode);
    xTaskCreate(frs_esc_mode_task, "frs_esc", 2048,
                (void *)(uintptr_t)esc_mode, 5, NULL);
}

// ── Auto Start/Stop kill ─────────────────────────────────────
// Same 100 Hz injection on 0x260 as ESC.
static void frs_send_ass_button(void) {
    uint8_t tmpl[8];
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (!s_state.frame_260_valid) {
        xSemaphoreGive(s_state_mutex);
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ASS button");
        return;
    }
    memcpy(tmpl, s_state.frame_260_template, 8);
    xSemaphoreGive(s_state_mutex);

    int count = FRS_ESC_SHORT_PRESS_MS / FRS_ESC_BTN_TX_INTERVAL_MS;
    frs_send_button(FRS_CAN_ID_BODY_CTRL, tmpl,
                    FRS_ASS_BTN_BYTE, FRS_ASS_BTN_BIT,
                    FRS_ESC_BTN_TX_INTERVAL_MS, count);
}

void frs_set_ass_kill(bool enabled) {
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.ass_kill = enabled;
    xSemaphoreGive(s_state_mutex);

    frs_nvs_save_ass_kill(enabled);
    ESP_LOGI(TAG, "Auto S/S kill: %s", enabled ? "ON" : "OFF");

    if (enabled) {
        frs_send_ass_button();
    }
}

// ── Launch control (NVS-only, no CAN write) ──────────────────
void frs_set_lc(bool enabled) {
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.lc_enabled = enabled;
    xSemaphoreGive(s_state_mutex);

    frs_nvs_save_lc(enabled);
    ESP_LOGI(TAG, "Launch control: %s", enabled ? "ON" : "OFF");
}

// ── Sleep threshold ──────────────────────────────────────────
void frs_set_sleep_threshold(float volts) {
    if (volts < 10.0f || volts > 15.0f) return;
    uint16_t mv = (uint16_t)(volts * 1000.0f);

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.sleep_threshold_mv = mv;
    xSemaphoreGive(s_state_mutex);

    frs_nvs_save_sleep_threshold(mv);
    ESP_LOGI(TAG, "Sleep threshold: %.1fV (%umV)", volts, mv);
}

// ── Boot apply task ──────────────────────────────────────────
// Waits for CAN templates to be captured, then applies persisted
// boot mode, ESC mode, and ASS kill settings.
static void frs_boot_apply_task(void *arg) {
    const int max_wait_ms = 10000;
    const int poll_ms = 200;
    int waited = 0;

    while (waited < max_wait_ms) {
        xSemaphoreTake(s_state_mutex, portMAX_DELAY);
        bool ready = s_state.frame_305_valid && s_state.frame_260_valid
                     && s_can_tx != NULL;
        xSemaphoreGive(s_state_mutex);
        if (ready) break;
        vTaskDelay(pdMS_TO_TICKS(poll_ms));
        waited += poll_ms;
    }

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    bool f305 = s_state.frame_305_valid;
    bool f260 = s_state.frame_260_valid;
    uint8_t boot_mode  = s_state.boot_mode;
    uint8_t drive_mode = s_state.drive_mode;
    uint8_t boot_esc   = s_state.boot_esc;
    uint8_t esc_mode   = s_state.esc_mode;
    bool ass_kill      = s_state.ass_kill;
    xSemaphoreGive(s_state_mutex);

    if (!f305 || !f260) {
        ESP_LOGW(TAG, "Boot apply: templates not captured after %dms "
                 "(305=%d, 260=%d) — skipping",
                 max_wait_ms, f305, f260);
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "Boot apply: templates ready after %dms", waited);

    if (boot_mode != drive_mode && boot_mode <= FRS_MODE_TRACK) {
        ESP_LOGI(TAG, "Boot apply: drive mode %d → %d",
                 drive_mode, boot_mode);
        frs_set_drive_mode(boot_mode);
        vTaskDelay(pdMS_TO_TICKS(3000));
    }

    if (boot_esc != esc_mode && boot_esc <= FRS_ESC_OFF) {
        ESP_LOGI(TAG, "Boot apply: ESC %d → %d",
                 esc_mode, boot_esc);
        frs_set_esc(boot_esc);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    if (ass_kill) {
        ESP_LOGI(TAG, "Boot apply: sending ASS kill button press");
        frs_send_ass_button();
    }

    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP_LOGI(TAG, "Boot apply: running ABS module UDS probe");
    frs_uds_probe_abs();

    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_state.abs_reachable = true;  // updated by probe logs; default optimistic
    xSemaphoreGive(s_state_mutex);

    ESP_LOGI(TAG, "Boot apply: complete");
    vTaskDelete(NULL);
}

void frs_boot_apply(void) {
    xTaskCreate(frs_boot_apply_task, "frs_boot", 3072, NULL, 4, NULL);
}

// ── BLE command channel (AT+FRS) ─────────────────────────────
// Parses commands received via SLCAN transport (BLE, TCP, or WebSocket).
// Same handlers as REST /api/frs — second entry point for the same logic.
// cmd: null-terminated string after "AT+FRS" prefix (e.g., "?" or "=driveMode,1")
// Returns length of response written to resp_buf.
int frs_handle_at_command(const char *cmd, char *resp_buf, int resp_buf_size) {
    if (!cmd || !resp_buf || resp_buf_size < 16) {
        return 0;
    }

    // AT+FRS? → state query (same data as GET /api/frs)
    if (cmd[0] == '?') {
        frs_state_t snap = frs_get_state_copy();
        return snprintf(resp_buf, resp_buf_size,
            "+FRS:driveMode=%d,escMode=%d,lcEnabled=%s,assKill=%s,"
            "battMv=%lu,sleepMv=%u\r",
            snap.drive_mode, snap.esc_mode,
            snap.lc_enabled ? "true" : "false",
            snap.ass_kill   ? "true" : "false",
            (unsigned long)snap.battery_mv,
            (unsigned)snap.sleep_threshold_mv);
    }

    // AT+FRS=key,value → set command (same logic as POST /api/frs)
    if (cmd[0] != '=') {
        return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,bad syntax\r");
    }

    const char *kv = cmd + 1;  // skip '='
    const char *comma = strchr(kv, ',');
    if (!comma) {
        return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,missing value\r");
    }

    int key_len = (int)(comma - kv);
    const char *val = comma + 1;

    if (key_len == 9 && strncmp(kv, "driveMode", 9) == 0) {
        int mode = atoi(val);
        if (mode < 0 || mode > FRS_MODE_TRACK) {
            return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,invalid mode\r");
        }
        if (!frs_set_drive_mode((uint8_t)mode)) {
            return snprintf(resp_buf, resp_buf_size, "+FRS:BUSY\r");
        }
        return snprintf(resp_buf, resp_buf_size, "+FRS:OK\r");
    }

    if (key_len == 7 && strncmp(kv, "escMode", 7) == 0) {
        int mode = atoi(val);
        if (mode < 0 || mode > FRS_ESC_OFF) {
            return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,invalid escMode\r");
        }
        frs_set_esc((uint8_t)mode);
        return snprintf(resp_buf, resp_buf_size, "+FRS:OK\r");
    }

    if (key_len == 8 && strncmp(kv, "enableLC", 8) == 0) {
        bool en = (strncmp(val, "true", 4) == 0 || val[0] == '1');
        frs_set_lc(en);
        return snprintf(resp_buf, resp_buf_size, "+FRS:OK\r");
    }

    if (key_len == 7 && strncmp(kv, "killASS", 7) == 0) {
        bool en = (strncmp(val, "true", 4) == 0 || val[0] == '1');
        frs_set_ass_kill(en);
        return snprintf(resp_buf, resp_buf_size, "+FRS:OK\r");
    }

    if (key_len == 12 && strncmp(kv, "sleepVoltage", 12) == 0) {
        int mv = atoi(val);
        if (mv < 10000 || mv > 15000) {
            return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,out of range\r");
        }
        frs_set_sleep_threshold((float)mv / 1000.0f);
        return snprintf(resp_buf, resp_buf_size, "+FRS:OK\r");
    }

    return snprintf(resp_buf, resp_buf_size, "+FRS:ERROR,unknown key\r");
}

// ── Thread-safe state access ─────────────────────────────────
frs_state_t frs_get_state_copy(void) {
    frs_state_t copy;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    memcpy(&copy, &s_state, sizeof(frs_state_t));
    xSemaphoreGive(s_state_mutex);
    return copy;
}

// DEPRECATED: raw pointer, not thread-safe on dual-core (Pro).
// Use frs_get_state_copy() instead.
frs_state_t *frs_get_state(void) {
    return &s_state;
}
