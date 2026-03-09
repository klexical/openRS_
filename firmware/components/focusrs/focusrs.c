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
// Using a callback keeps this component independent of main/can.c.
static frs_can_tx_fn_t s_can_tx = NULL;

void frs_set_can_tx_fn(frs_can_tx_fn_t fn) {
    s_can_tx = fn;
}

static frs_state_t s_state = {
    .drive_mode           = FRS_MODE_NORMAL,
    .boot_mode            = FRS_MODE_NORMAL,
    .esc_mode             = FRS_ESC_ON,
    .lc_enabled           = false,
    .ass_kill             = false,
    .battery_mv           = 12000,
    .sleep_threshold_mv   = 12200,
    .frame_template_valid = false,
};

void frs_init(void) {
    s_state_mutex = xSemaphoreCreateMutex();
    frs_nvs_load(&s_state);
    ESP_LOGI(TAG, "openrs-fw %s — Focus RS module init", OPENRS_FW_VERSION);
    ESP_LOGI(TAG, "Boot mode: %d, ESC: %d, LC: %d",
             s_state.boot_mode, s_state.esc_mode, s_state.lc_enabled);

    // On boot, apply the persisted drive mode once the bus is stable
    // (called from app_main after 2s delay to allow BCM to initialise)
}

void frs_parse_can_frame(uint32_t can_id, const uint8_t *data, uint8_t dlc) {
    if (!data) return;
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);

    switch (can_id) {

    case FRS_CAN_ID_AWD_MSG:
        if (dlc >= 7 && data[4] == 0x00) {
            uint8_t raw_mode = (data[6] >> 4) & 0x0F;
            if (raw_mode <= FRS_MODE_TRACK) {
                s_state.drive_mode = raw_mode;
            }
        } else if (!s_state.frame_template_valid && dlc >= 8 && data[4] != 0x00) {
            memcpy(s_state.frame_1b0_template, data, 8);
            s_state.frame_template_valid = true;
            ESP_LOGI(TAG, "0x1B0 button event template captured (byte4=0x%02X)", data[4]);
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

    default:
        break;
    }

    xSemaphoreGive(s_state_mutex);
}

// ── Drive mode write ──────────────────────────────────────────
// Simulates the physical drive mode button press on 0x1B0.
// The car cycles N→S→T→D→N. We calculate the number of presses
// needed from current to target and send that many press/release pairs.
static void frs_send_button_press(void) {
    if (!s_state.frame_template_valid) {
        ESP_LOGW(TAG, "No 0x1B0 template yet — cannot simulate button");
        return;
    }
    if (!s_can_tx) {
        ESP_LOGE(TAG, "CAN TX callback not set — call frs_set_can_tx_fn() at startup");
        return;
    }

    uint8_t pressed[8], released[8];
    memcpy(pressed,  s_state.frame_1b0_template, 8);
    memcpy(released, s_state.frame_1b0_template, 8);
    pressed[1]  = FRS_BUTTON_PRESSED;
    released[1] = FRS_BUTTON_RELEASED;

    s_can_tx(FRS_CAN_ID_AWD_MSG, pressed,  8, 100);
    vTaskDelay(pdMS_TO_TICKS(FRS_BUTTON_HOLD_MS));
    s_can_tx(FRS_CAN_ID_AWD_MSG, released, 8, 100);
    vTaskDelay(pdMS_TO_TICKS(100));
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
    uint8_t presses = (tgt_pos - cur_pos + cycle_len) % cycle_len;

    ESP_LOGI(TAG, "Drive mode: %d → %d (pos %d→%d, %d presses)",
             current, target_mode, cur_pos, tgt_pos, presses);

    for (uint8_t i = 0; i < presses; i++) {
        frs_send_button_press();
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

void frs_set_esc(uint8_t esc_mode) {
    if (esc_mode > FRS_ESC_OFF) return;
    // CAN write pending empirical frame capture — state is persisted for boot restore
    ESP_LOGI(TAG, "ESC set requested: %d (pending frame capture)", esc_mode);
    s_state.esc_mode = esc_mode;
    frs_nvs_save_esc(esc_mode);
}

void frs_set_lc(bool enabled) {
    s_state.lc_enabled = enabled;
    frs_nvs_save_lc(enabled);
    ESP_LOGI(TAG, "Launch control: %s", enabled ? "ON" : "OFF");
}

void frs_set_ass_kill(bool enabled) {
    s_state.ass_kill = enabled;
    frs_nvs_save_ass_kill(enabled);
    ESP_LOGI(TAG, "Auto S/S kill: %s", enabled ? "ON" : "OFF");
}

void frs_set_sleep_threshold(float volts) {
    if (volts < 10.0f || volts > 15.0f) return;
    uint16_t mv = (uint16_t)(volts * 1000.0f);
    s_state.sleep_threshold_mv = mv;
    frs_nvs_save_sleep_threshold(mv);
    ESP_LOGI(TAG, "Sleep threshold: %.1fV (%umV)", volts, mv);
}

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

