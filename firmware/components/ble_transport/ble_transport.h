#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// BLE GATT UUIDs — compatible with common BLE ELM327 adapters
// Service:        0xFFE0
// RX (write):     0xFFE1  — app writes AT commands / OBD requests here
// TX (notify):    0xFFE2  — firmware notifies app with ELM327 responses

void ble_transport_init(const char *device_name);
void ble_transport_notify(const uint8_t *data, uint16_t len);
bool ble_transport_is_connected(void);

// Callback — called when data is received from the BLE client (app → WiCAN)
typedef void (*ble_rx_cb_t)(const uint8_t *data, uint16_t len);
void ble_transport_set_rx_callback(ble_rx_cb_t cb);

#ifdef __cplusplus
}
#endif
