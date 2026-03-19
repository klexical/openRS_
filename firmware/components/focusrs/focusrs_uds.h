#pragma once

#include "focusrs.h"
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ── UDS service IDs ──────────────────────────────────────────
#define UDS_SID_DIAG_SESSION    0x10
#define UDS_SID_READ_DID        0x22
#define UDS_SID_SECURITY_ACCESS 0x27
#define UDS_SID_IO_CONTROL      0x2F
#define UDS_SID_ROUTINE_CTRL    0x31
#define UDS_SID_TESTER_PRESENT  0x3E
#define UDS_SID_NEGATIVE_RSP    0x7F
#define UDS_POSITIVE_OFFSET     0x40  // positive response = SID + 0x40

// ── Ford ESC-related DIDs to probe ───────────────────────────
#define FRS_DID_ESC_CFG_1       0xDD01
#define FRS_DID_ESC_CFG_2       0xDD04
#define FRS_DID_ADVTRAC_STATUS  0x4003

// ── ISO-TP single-frame PCI ──────────────────────────────────
#define ISOTP_SF_PCI_MASK       0x0F  // lower nibble = payload length

// ── API (implemented in focusrs_uds.c) ───────────────────────
void frs_uds_init(void);
void frs_uds_handle_response(uint32_t can_id, const uint8_t *data, uint8_t dlc);

// Send a single-frame UDS request (max 7 payload bytes) via s_can_tx.
int  frs_uds_send(uint32_t target_id, const uint8_t *payload, uint8_t len);

// Block until a UDS response arrives or timeout_ms expires.
// Returns payload length (0 = timeout).  Caller provides an 8-byte buf.
int  frs_uds_recv(uint8_t *buf, uint32_t timeout_ms);

// Boot-time ABS module discovery.
void frs_uds_probe_abs(void);

#ifdef __cplusplus
}
#endif
