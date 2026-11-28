// Host stub (firmware/tests only): an in-memory NVS for the "openrs" namespace, so tests run the real focusrs_nvs.c.
// The test file defines the functions; tests inspect and preload keys with host_nvs_* helpers.
#pragma once
#include <stdint.h>
#include "esp_err.h"
typedef uint32_t nvs_handle_t;
typedef enum { NVS_READONLY, NVS_READWRITE } nvs_open_mode_t;
esp_err_t nvs_open(const char *ns, nvs_open_mode_t mode, nvs_handle_t *h);
esp_err_t nvs_get_u8(nvs_handle_t h, const char *key, uint8_t *out);
esp_err_t nvs_get_u16(nvs_handle_t h, const char *key, uint16_t *out);
esp_err_t nvs_set_u8(nvs_handle_t h, const char *key, uint8_t v);
esp_err_t nvs_set_u16(nvs_handle_t h, const char *key, uint16_t v);
esp_err_t nvs_erase_key(nvs_handle_t h, const char *key);
esp_err_t nvs_commit(nvs_handle_t h);
void nvs_close(nvs_handle_t h);
