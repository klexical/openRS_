#include "focusrs_nvs.h"
#include "focusrs.h"

#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"

static const char *TAG     = "frs_nvs";
static const char *NS      = "openrs";   // NVS namespace

#define KEY_BOOT_MODE   "rs_bootmode"   // erased at start-up, never read or written (#628)
#define KEY_ESC         "rs_esc"        // erased at start-up, never read or written (#628)
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

    uint16_t u16;

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
    ESP_LOGI(TAG, "Loaded: lc=%d ass=%d sleep=%lumV",
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

// #628: older firmware saved the last drive mode and ESC here and restored them at every start-up (Drift and ESC Off
// included). Neither is read any more; erasing them means a later downgrade has nothing to restore. Writes flash only
// when a key is still there.
void frs_nvs_erase_restore_keys(void) {
    nvs_handle_t h;
    esp_err_t err = nvs_open(NS, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "NVS open for erase failed: %s", esp_err_to_name(err));
        return;
    }
    const char *keys[] = { KEY_BOOT_MODE, KEY_ESC };
    bool erased = false;
    for (int i = 0; i < 2; i++) {
        err = nvs_erase_key(h, keys[i]);
        if (err == ESP_OK) {
            erased = true;
            ESP_LOGI(TAG, "Erased saved %s (no start-up restore since #628)", keys[i]);
        } else if (err != ESP_ERR_NVS_NOT_FOUND) {
            ESP_LOGW(TAG, "NVS erase %s failed: %s", keys[i], esp_err_to_name(err));
        }
    }
    if (erased) {
        err = nvs_commit(h);
        if (err != ESP_OK) ESP_LOGW(TAG, "NVS commit after erase failed: %s", esp_err_to_name(err));
    }
    nvs_close(h);
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
