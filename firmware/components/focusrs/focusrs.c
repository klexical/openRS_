#include "focusrs.h"
#include "focusrs_nvs.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "focusrs";
static SemaphoreHandle_t s_state_mutex = NULL;

// CAN TX callback — set by main via frs_set_can_tx_fn() after CAN is initialised.
static frs_can_tx_fn_t s_can_tx = NULL;

void frs_set_can_tx_fn(frs_can_tx_fn_t fn) {
    s_can_tx = fn;
}

static frs_state_t s_state = {
    .drive_mode           = FRS_MODE_NORMAL,
    .boot_mode            = FRS_MODE_NORMAL,
    .esc_mode             = FRS_ESC_ON,
    .boot_esc             = FRS_ESC_ON,
    .lc_enabled           = false,
    .ass_kill             = false,
    .battery_mv           = 12000,
    .sleep_threshold_mv   = 12200,
    .frame_305_valid      = false,
    .frame_260_valid      = false,
};

void frs_init(void) {
    s_state_mutex = xSemaphoreCreateMutex();
    frs_nvs_load(&s_state);
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
        // Read-only: extract drive mode from status frame
        if (dlc >= 7 && data[4] == 0x00) {
            uint8_t raw_mode = (data[6] >> 4) & 0x0F;
            if (raw_mode <= FRS_MODE_TRACK) {
                s_state.drive_mode = raw_mode;
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
        // Capture template when button is NOT pressed (steady state)
        if (dlc >= 8 && !(data[FRS_DM_BTN_BYTE] & FRS_DM_BTN_BIT)) {
            memcpy(s_state.frame_305_template, data, 8);
            s_state.frame_305_valid = true;
        }
        break;

    case FRS_CAN_ID_BODY_CTRL:
        // Capture template when neither ESC nor ASS button is pressed
        if (dlc >= 8 &&
            !(data[FRS_ESC_BTN_BYTE] & FRS_ESC_BTN_BIT) &&
            !(data[FRS_ASS_BTN_BYTE] & FRS_ASS_BTN_BIT)) {
            memcpy(s_state.frame_260_template, data, 8);
            s_state.frame_260_valid = true;
        }
        break;

    default:
        break;
    }

    xSemaphoreGive(s_state_mutex);
}

// ── Generic button press helpers ─────────────────────────────

// Short press: sends FRS_BUTTON_TX_COUNT frames with the bit set,
// spaced FRS_BUTTON_TX_INTERVAL_MS apart (~240ms total hold).
static void frs_send_button(uint32_t can_id,
                            const uint8_t *tmpl,
                            uint8_t byte_idx,
                            uint8_t bit_mask) {
    if (!s_can_tx) {
        ESP_LOGE(TAG, "CAN TX callback not set");
        return;
    }

    uint8_t frame[8];
    memcpy(frame, tmpl, 8);
    frame[byte_idx] |= bit_mask;

    for (int i = 0; i < FRS_BUTTON_TX_COUNT; i++) {
        s_can_tx(can_id, frame, 8, 100);
        if (i < FRS_BUTTON_TX_COUNT - 1) {
            vTaskDelay(pdMS_TO_TICKS(FRS_BUTTON_TX_INTERVAL_MS));
        }
    }
    vTaskDelay(pdMS_TO_TICKS(FRS_BUTTON_TX_INTERVAL_MS));
}

// Long press: holds the bit for duration_ms (used for ESC Off).
static void frs_send_button_long(uint32_t can_id,
                                 const uint8_t *tmpl,
                                 uint8_t byte_idx,
                                 uint8_t bit_mask,
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
        s_can_tx(can_id, frame, 8, 100);
        vTaskDelay(pdMS_TO_TICKS(FRS_BUTTON_TX_INTERVAL_MS));
        elapsed += FRS_BUTTON_TX_INTERVAL_MS;
    }
    vTaskDelay(pdMS_TO_TICKS(FRS_BUTTON_TX_INTERVAL_MS));
}

// ── Drive mode write ─────────────────────────────────────────
// Simulates the physical drive mode button on CAN ID 0x305.
// Each press cycles: Normal → Sport → Track → Drift → Normal.
static void frs_send_dm_button(void) {
    if (!s_state.frame_305_valid) {
        ESP_LOGW(TAG, "No 0x305 template yet — cannot simulate drive mode button");
        return;
    }
    frs_send_button(FRS_CAN_ID_DRIVE_MODE_BTN,
                    s_state.frame_305_template,
                    FRS_DM_BTN_BYTE, FRS_DM_BTN_BIT);
}

static uint8_t s_pending_mode = 0xFF;

static void frs_drive_mode_task(void *arg) {
    uint8_t target_mode = (uint8_t)(uintptr_t)arg;

    // Physical button cycle: N→S→T→D→N
    // CAN values:            0  1  3  2
    static const uint8_t can_to_pos[] = {0, 1, 3, 2};
    const uint8_t cycle_len = 4;
    uint8_t current = s_state.drive_mode;
    uint8_t cur_pos = can_to_pos[current];
    uint8_t tgt_pos = can_to_pos[target_mode];
    uint8_t cycle_dist = (tgt_pos - cur_pos + cycle_len) % cycle_len;

    if (cycle_dist == 0) {
        ESP_LOGI(TAG, "Drive mode: already in %d — no change", current);
        s_pending_mode = 0xFF;
        vTaskDelete(NULL);
        return;
    }

    // The Focus RS drive mode button has two stages:
    //   Press 1: opens the mode selector GUI on the cluster (no mode change)
    //   Press 2+: cycles through modes (N→S→T→D→N)
    // So total presses = 1 (activation) + cycle_distance.
    uint8_t presses = 1 + cycle_dist;

    ESP_LOGI(TAG, "Drive mode: %d → %d (1 activation + %d cycle = %d presses via 0x305)",
             current, target_mode, cycle_dist, presses);

    for (uint8_t i = 0; i < presses; i++) {
        frs_send_dm_button();
        vTaskDelay(pdMS_TO_TICKS(150));
    }

    s_state.boot_mode = target_mode;
    frs_nvs_save_boot_mode(target_mode);
    s_pending_mode = 0xFF;
    vTaskDelete(NULL);
}

void frs_set_drive_mode(uint8_t target_mode) {
    if (target_mode > FRS_MODE_TRACK) return;
    if (s_pending_mode != 0xFF) {
        ESP_LOGW(TAG, "Drive mode change already in progress");
        return;
    }
    s_pending_mode = target_mode;
    xTaskCreate(frs_drive_mode_task, "frs_dm", 2048,
                (void *)(uintptr_t)target_mode, 5, NULL);
}

// ── ESC mode write ───────────────────────────────────────────
// Focus RS ESC button behavior (confirmed via car test):
//   Short press: toggles On ↔ Sport
//   Long press (~5s): activates ESC Off from On or Sport
//   Short press from Off: returns to On
static void frs_send_esc_short(void) {
    if (!s_state.frame_260_valid) {
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ESC button");
        return;
    }
    frs_send_button(FRS_CAN_ID_BODY_CTRL,
                    s_state.frame_260_template,
                    FRS_ESC_BTN_BYTE, FRS_ESC_BTN_BIT);
}

static void frs_send_esc_long(void) {
    if (!s_state.frame_260_valid) {
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ESC long press");
        return;
    }
    frs_send_button_long(FRS_CAN_ID_BODY_CTRL,
                         s_state.frame_260_template,
                         FRS_ESC_BTN_BYTE, FRS_ESC_BTN_BIT,
                         FRS_ESC_LONG_PRESS_MS);
}

static uint8_t s_pending_esc = 0xFF;

static void frs_esc_mode_task(void *arg) {
    uint8_t target_esc = (uint8_t)(uintptr_t)arg;
    uint8_t current = s_state.esc_mode;

    if (current == target_esc) {
        ESP_LOGI(TAG, "ESC: already in %d — no change", current);
        s_pending_esc = 0xFF;
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "ESC mode: %d → %d via 0x260", current, target_esc);

    if (target_esc == FRS_ESC_OFF) {
        // Long press (~5s) to enter ESC Off from any state
        ESP_LOGI(TAG, "ESC: long press for Off");
        frs_send_esc_long();
    } else if (target_esc == FRS_ESC_SPORT) {
        if (current == FRS_ESC_OFF) {
            // Off → On (short), then On → Sport (short)
            ESP_LOGI(TAG, "ESC: Off → On → Sport (2 short presses)");
            frs_send_esc_short();
            vTaskDelay(pdMS_TO_TICKS(1000));
            frs_send_esc_short();
        } else {
            // On → Sport (short)
            frs_send_esc_short();
        }
    } else {
        // Target is On
        if (current == FRS_ESC_OFF || current == FRS_ESC_SPORT) {
            // Off → On or Sport → On (short press)
            frs_send_esc_short();
        }
    }

    s_state.boot_esc = target_esc;
    frs_nvs_save_esc(target_esc);
    s_pending_esc = 0xFF;
    vTaskDelete(NULL);
}

void frs_set_esc(uint8_t esc_mode) {
    if (esc_mode > FRS_ESC_OFF) return;
    if (s_pending_esc != 0xFF) {
        ESP_LOGW(TAG, "ESC mode change already in progress");
        return;
    }
    s_pending_esc = esc_mode;
    xTaskCreate(frs_esc_mode_task, "frs_esc", 2048,
                (void *)(uintptr_t)esc_mode, 5, NULL);
}

// ── Auto Start/Stop kill ─────────────────────────────────────
// Simulates the ASS button on CAN ID 0x260 byte 1 bit 0.
// A single press toggles ASS on/off.
static void frs_send_ass_button(void) {
    if (!s_state.frame_260_valid) {
        ESP_LOGW(TAG, "No 0x260 template yet — cannot simulate ASS button");
        return;
    }
    frs_send_button(FRS_CAN_ID_BODY_CTRL,
                    s_state.frame_260_template,
                    FRS_ASS_BTN_BYTE, FRS_ASS_BTN_BIT);
}

void frs_set_ass_kill(bool enabled) {
    s_state.ass_kill = enabled;
    frs_nvs_save_ass_kill(enabled);
    ESP_LOGI(TAG, "Auto S/S kill: %s", enabled ? "ON" : "OFF");

    if (enabled) {
        frs_send_ass_button();
    }
}

// ── Launch control (NVS-only, no CAN write) ──────────────────
void frs_set_lc(bool enabled) {
    s_state.lc_enabled = enabled;
    frs_nvs_save_lc(enabled);
    ESP_LOGI(TAG, "Launch control: %s", enabled ? "ON" : "OFF");
}

// ── Sleep threshold ──────────────────────────────────────────
void frs_set_sleep_threshold(float volts) {
    if (volts < 10.0f || volts > 15.0f) return;
    uint16_t mv = (uint16_t)(volts * 1000.0f);
    s_state.sleep_threshold_mv = mv;
    frs_nvs_save_sleep_threshold(mv);
    ESP_LOGI(TAG, "Sleep threshold: %.1fV (%umV)", volts, mv);
}

// ── Boot apply task ──────────────────────────────────────────
// Waits for CAN templates to be captured, then applies persisted
// boot mode, ESC mode, and ASS kill settings.
static void frs_boot_apply_task(void *arg) {
    // Wait for the CAN bus to stabilise and templates to be captured.
    // 0x305 at ~10 Hz and 0x260 at ~50 Hz — should be valid within 1s.
    const int max_wait_ms = 10000;
    const int poll_ms = 200;
    int waited = 0;

    while (waited < max_wait_ms) {
        if (s_state.frame_305_valid && s_state.frame_260_valid) break;
        vTaskDelay(pdMS_TO_TICKS(poll_ms));
        waited += poll_ms;
    }

    if (!s_state.frame_305_valid || !s_state.frame_260_valid) {
        ESP_LOGW(TAG, "Boot apply: templates not captured after %dms "
                 "(305=%d, 260=%d) — skipping",
                 max_wait_ms, s_state.frame_305_valid, s_state.frame_260_valid);
        vTaskDelete(NULL);
        return;
    }

    ESP_LOGI(TAG, "Boot apply: templates ready after %dms", waited);

    // Apply boot drive mode if different from current
    if (s_state.boot_mode != s_state.drive_mode &&
        s_state.boot_mode <= FRS_MODE_TRACK) {
        ESP_LOGI(TAG, "Boot apply: drive mode %d → %d",
                 s_state.drive_mode, s_state.boot_mode);
        frs_set_drive_mode(s_state.boot_mode);
        vTaskDelay(pdMS_TO_TICKS(3000));
    }

    // Apply boot ESC mode if different from live CAN value
    if (s_state.boot_esc != s_state.esc_mode &&
        s_state.boot_esc <= FRS_ESC_OFF) {
        ESP_LOGI(TAG, "Boot apply: ESC %d → %d",
                 s_state.esc_mode, s_state.boot_esc);
        frs_set_esc(s_state.boot_esc);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    // Apply ASS kill if persisted
    if (s_state.ass_kill) {
        ESP_LOGI(TAG, "Boot apply: sending ASS kill button press");
        frs_send_ass_button();
    }

    ESP_LOGI(TAG, "Boot apply: complete");
    vTaskDelete(NULL);
}

void frs_boot_apply(void) {
    xTaskCreate(frs_boot_apply_task, "frs_boot", 2048, NULL, 4, NULL);
}

// ── Thread-safe state access ─────────────────────────────────
frs_state_t frs_get_state_copy(void) {
    frs_state_t copy;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    memcpy(&copy, &s_state, sizeof(frs_state_t));
    xSemaphoreGive(s_state_mutex);
    return copy;
}

frs_state_t *frs_get_state(void) {
    return &s_state;
}
