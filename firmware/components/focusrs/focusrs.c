#include "focusrs.h"
#include "focusrs_nvs.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include <string.h>
#include <stdlib.h>

static const char *TAG = "focusrs";

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
    frs_nvs_load(&s_state);
    ESP_LOGI(TAG, "openrs-fw %s — Focus RS module init", OPENRS_FW_VERSION);
    ESP_LOGI(TAG, "Boot mode: %d, ESC: %d, LC: %d",
             s_state.boot_mode, s_state.esc_mode, s_state.lc_enabled);

    // On boot, apply the persisted drive mode once the bus is stable
    // (called from app_main after 2s delay to allow BCM to initialise)
}

void frs_parse_can_frame(uint32_t can_id, const uint8_t *data, uint8_t dlc) {
    switch (can_id) {

    case FRS_CAN_ID_AWD_MSG:
        // 0x1B0: drive mode status + button event frame.
        // Steady-state (byte4==0): byte 6 upper nibble = mode (0=Normal 1=Sport 2=Drift 3=Track).
        // Button event (byte4!=0): attempt to capture template for write simulation.
        // 0x17E only reflects Normal/Sport and is NOT used — Track/Drift absent there.
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
            // ESC mode: Motorola bit 13, 2-bit wide = byte1 bits [5:4]
            uint8_t raw_esc = (data[1] >> 4) & 0x03;
            // Remap: DBC 0=Normal/On, 1=Off, 2=Sport/Partial → our FRS_ESC_*
            if (raw_esc == 0) s_state.esc_mode = FRS_ESC_ON;
            else if (raw_esc == 1) s_state.esc_mode = FRS_ESC_OFF;
            else if (raw_esc == 2) s_state.esc_mode = FRS_ESC_SPORT;
        }
        break;

    default:
        break;
    }
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

void frs_set_drive_mode(uint8_t target_mode) {
    if (target_mode > FRS_MODE_TRACK) return;

    const uint8_t cycle_len = 4; // N→S→T→D→N
    uint8_t current = s_state.drive_mode;
    uint8_t presses = (target_mode - current + cycle_len) % cycle_len;

    ESP_LOGI(TAG, "Drive mode: %d → %d (%d presses)", current, target_mode, presses);

    for (uint8_t i = 0; i < presses; i++) {
        frs_send_button_press();
    }

    // Update boot mode in NVS
    s_state.boot_mode = target_mode;
    frs_nvs_save_boot_mode(target_mode);
}

void frs_set_esc(uint8_t esc_mode) {
    // TODO: identify ESC write frames from car (requires empirical capture)
    // For now, log intent — will be implemented after frame capture
    ESP_LOGI(TAG, "ESC set requested: %d (pending frame capture)", esc_mode);
    s_state.esc_mode = esc_mode;
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

frs_state_t *frs_get_state(void) {
    return &s_state;
}

void frs_handle_settings_post(const char *key, const char *value) {
    if (!key || !value) return;

    if (strcmp(key, "driveMode") == 0) {
        frs_set_drive_mode((uint8_t)atoi(value));
    } else if (strcmp(key, "escMode") == 0) {
        frs_set_esc((uint8_t)atoi(value));
    } else if (strcmp(key, "enableLC") == 0) {
        frs_set_lc(strcmp(value, "true") == 0 || strcmp(value, "1") == 0);
    } else if (strcmp(key, "killASS") == 0) {
        frs_set_ass_kill(strcmp(value, "true") == 0 || strcmp(value, "1") == 0);
    }
}
