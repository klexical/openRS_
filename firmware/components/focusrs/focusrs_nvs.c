#include "focusrs_nvs.h"
#include "focusrs.h"

#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"

static const char *TAG     = "frs_nvs";
static const char *NS      = "openrs";   // NVS namespace

#define KEY_BOOT_MODE   "rs_bootmode"
#define KEY_ESC         "rs_esc"
#define KEY_LC          "rs_lc"
#define KEY_ASS         "rs_ass_kill"
#define KEY_SLEEP_MV    "sleep_thresh_mv"

void frs_nvs_load(frs_state_t *state) {
    nvs_handle_t h;
    esp_err_t err = nvs_open(NS, NVS_READONLY, &h);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "NVS namespace not found — using defaults");
        return;
    }

    uint8_t u8;
    uint16_t u16;

    if (nvs_get_u8(h, KEY_BOOT_MODE, &u8) == ESP_OK) {
        if (u8 <= FRS_MODE_TRACK) {
            state->boot_mode = u8;
        } else {
            ESP_LOGW(TAG, "NVS boot_mode out of range (%d) — using default", u8);
        }
    }

    if (nvs_get_u8(h, KEY_ESC, &u8) == ESP_OK) {
        if (u8 <= FRS_ESC_OFF) {
            state->esc_mode = u8;
            state->boot_esc = u8;
        } else {
            ESP_LOGW(TAG, "NVS esc_mode out of range (%d) — using default", u8);
        }
    }

    uint8_t lc = 0, ass = 0;
    nvs_get_u8(h, KEY_LC,  &lc);
    nvs_get_u8(h, KEY_ASS, &ass);
    state->lc_enabled = (lc != 0);
    state->ass_kill   = (ass != 0);

    if (nvs_get_u16(h, KEY_SLEEP_MV, &u16) == ESP_OK) {
        if (u16 >= 10000 && u16 <= 15000) {
            state->sleep_threshold_mv = u16;
        } else {
            ESP_LOGW(TAG, "NVS sleep_thresh out of range (%u) — using default", u16);
        }
    }

    nvs_close(h);
    ESP_LOGI(TAG, "Loaded: boot=%d esc=%d lc=%d ass=%d sleep=%lumV",
             state->boot_mode, state->esc_mode,
             state->lc_enabled, state->ass_kill,
             (unsigned long)state->sleep_threshold_mv);
}

static esp_err_t nvs_write_u8(const char *key, uint8_t val) {
    nvs_handle_t h;
    esp_err_t err = nvs_open(NS, NVS_READWRITE, &h);
    if (err != ESP_OK) return err;
    err = nvs_set_u8(h, key, val);
    if (err == ESP_OK) err = nvs_commit(h);
    nvs_close(h);
    return err;
}

void frs_nvs_save_boot_mode(uint8_t mode) {
    esp_err_t err = nvs_write_u8(KEY_BOOT_MODE, mode);
    if (err != ESP_OK) ESP_LOGW(TAG, "NVS write boot_mode failed: %s", esp_err_to_name(err));
}

void frs_nvs_save_esc(uint8_t mode) {
    esp_err_t err = nvs_write_u8(KEY_ESC, mode);
    if (err != ESP_OK) ESP_LOGW(TAG, "NVS write esc failed: %s", esp_err_to_name(err));
}

void frs_nvs_save_lc(bool enabled) {
    esp_err_t err = nvs_write_u8(KEY_LC, enabled ? 1 : 0);
    if (err != ESP_OK) ESP_LOGW(TAG, "NVS write lc failed: %s", esp_err_to_name(err));
}

void frs_nvs_save_ass_kill(bool enabled) {
    esp_err_t err = nvs_write_u8(KEY_ASS, enabled ? 1 : 0);
    if (err != ESP_OK) ESP_LOGW(TAG, "NVS write ass_kill failed: %s", esp_err_to_name(err));
}

void frs_nvs_save_sleep_threshold(uint16_t mv) {
    nvs_handle_t h;
    esp_err_t err = nvs_open(NS, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "NVS open for sleep_thresh failed: %s", esp_err_to_name(err));
        return;
    }
    err = nvs_set_u16(h, KEY_SLEEP_MV, mv);
    if (err == ESP_OK) err = nvs_commit(h);
    if (err != ESP_OK) ESP_LOGW(TAG, "NVS write sleep_thresh failed: %s", esp_err_to_name(err));
    nvs_close(h);
}
