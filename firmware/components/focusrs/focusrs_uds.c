#include "focusrs_uds.h"
#include "focusrs.h"

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "frs_uds";

// ── Response queue ───────────────────────────────────────────
typedef struct {
    uint8_t data[8];
    uint8_t len;
} uds_frame_t;

static QueueHandle_t s_uds_rx_queue = NULL;

void frs_uds_init(void) {
    if (!s_uds_rx_queue) {
        s_uds_rx_queue = xQueueCreate(4, sizeof(uds_frame_t));
    }
}

// ── Called from frs_parse_can_frame for 0x768 responses ──────
void frs_uds_handle_response(uint32_t can_id, const uint8_t *data, uint8_t dlc) {
    if (!s_uds_rx_queue || !data || dlc < 2) return;

    uds_frame_t frame;
    frame.len = (dlc > 8) ? 8 : dlc;
    memcpy(frame.data, data, frame.len);
    xQueueSend(s_uds_rx_queue, &frame, 0);
}

// ── ISO-TP single-frame send (max 7 payload bytes) ──────────
int frs_uds_send(uint32_t target_id, const uint8_t *payload, uint8_t len) {
    frs_can_tx_fn_t tx = frs_get_can_tx();
    if (!tx || len > 7 || len == 0) return -1;

    uint8_t frame[8];
    memset(frame, 0xAA, 8);  // padding
    frame[0] = len & ISOTP_SF_PCI_MASK;
    memcpy(&frame[1], payload, len);
    return tx(target_id, frame, 8, 100);
}

// ── Block for a response ─────────────────────────────────────
int frs_uds_recv(uint8_t *buf, uint32_t timeout_ms) {
    if (!s_uds_rx_queue || !buf) return 0;

    uds_frame_t frame;
    if (xQueueReceive(s_uds_rx_queue, &frame, pdMS_TO_TICKS(timeout_ms)) == pdTRUE) {
        uint8_t pci_len = frame.data[0] & ISOTP_SF_PCI_MASK;
        if (pci_len == 0 || pci_len > 7) {
            memcpy(buf, frame.data, frame.len);
            return frame.len;
        }
        memcpy(buf, &frame.data[1], pci_len);
        return pci_len;
    }
    return 0;
}

// ── Helper: send request, log response ───────────────────────
static void uds_request_log(uint32_t target, const uint8_t *req, uint8_t req_len,
                            const char *label) {
    xQueueReset(s_uds_rx_queue);
    int err = frs_uds_send(target, req, req_len);
    if (err != 0) {
        ESP_LOGW(TAG, "%s: TX failed (%d)", label, err);
        return;
    }

    uint8_t rsp[8];
    int rsp_len = frs_uds_recv(rsp, 200);
    if (rsp_len == 0) {
        ESP_LOGW(TAG, "%s: no response (timeout)", label);
        return;
    }

    char hex[24];
    int pos = 0;
    for (int i = 0; i < rsp_len && pos < (int)sizeof(hex) - 3; i++)
        pos += snprintf(hex + pos, sizeof(hex) - pos, "%02X ", rsp[i]);
    ESP_LOGI(TAG, "%s: [%d] %s", label, rsp_len, hex);
}

// ── Boot-time ABS module discovery ───────────────────────────
void frs_uds_probe_abs(void) {
    if (!s_uds_rx_queue) frs_uds_init();

    ESP_LOGI(TAG, "Probing ABS module at 0x%03X ...", FRS_UDS_ABS_TX);

    // 1. TesterPresent — check if module is alive
    uint8_t tp[] = { UDS_SID_TESTER_PRESENT, 0x00 };
    xQueueReset(s_uds_rx_queue);
    int err = frs_uds_send(FRS_UDS_ABS_TX, tp, sizeof(tp));
    if (err != 0) {
        ESP_LOGW(TAG, "ABS probe: TX failed (%d)", err);
        return;
    }

    uint8_t rsp[8];
    int rsp_len = frs_uds_recv(rsp, 300);
    if (rsp_len == 0) {
        ESP_LOGW(TAG, "ABS probe: no response — module not reachable on 0x%03X",
                 FRS_UDS_ABS_TX);
        return;
    }

    bool positive = (rsp_len >= 1 && rsp[0] == (UDS_SID_TESTER_PRESENT + UDS_POSITIVE_OFFSET));
    ESP_LOGI(TAG, "ABS probe: %s (rsp[0]=0x%02X, len=%d)",
             positive ? "ALIVE" : "unexpected response", rsp[0], rsp_len);

    // 2. ReadDID — probe known ESC-related identifiers
    const uint16_t dids[] = { FRS_DID_ESC_CFG_1, FRS_DID_ESC_CFG_2, FRS_DID_ADVTRAC_STATUS };
    for (int i = 0; i < (int)(sizeof(dids) / sizeof(dids[0])); i++) {
        uint8_t req[] = { UDS_SID_READ_DID, (uint8_t)(dids[i] >> 8), (uint8_t)(dids[i] & 0xFF) };
        char label[32];
        snprintf(label, sizeof(label), "ReadDID 0x%04X", dids[i]);
        uds_request_log(FRS_UDS_ABS_TX, req, sizeof(req), label);
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    ESP_LOGI(TAG, "ABS probe complete");
}
