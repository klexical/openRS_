#pragma once
#include <stdint.h>
typedef int esp_err_t;
#define ESP_OK 0
typedef enum { TWAI_STATE_STOPPED, TWAI_STATE_RUNNING, TWAI_STATE_BUS_OFF, TWAI_STATE_RECOVERING } twai_state_t;
typedef struct { twai_state_t state; uint32_t tx_error_counter; uint32_t rx_error_counter; } twai_status_info_t;
static inline esp_err_t twai_get_status_info(twai_status_info_t *i) { i->state = TWAI_STATE_RUNNING; i->tx_error_counter = 0; i->rx_error_counter = 0; return ESP_OK; }
static inline esp_err_t twai_initiate_recovery(void) { return ESP_OK; }
static inline esp_err_t twai_start(void) { return ESP_OK; }
