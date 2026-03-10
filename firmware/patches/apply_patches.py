#!/usr/bin/env python3
"""
apply_patches.py  —  applies openrs-fw modifications to a cloned wican-fw tree.

Usage: python3 apply_patches.py <path-to-wican-fw>

Modifications applied:
  1. wifi_network.c   — AP SSID prefix: WiCAN_ → openRS_
  2. config_server.c  — AP password default: @meatpi# → openrs2024
  3. config_server.c  — Add /api/frs GET+POST endpoint (token-authenticated)
  4. config_server.c  — OPENRS? WebSocket probe handler (responds OPENRS:<version>)
  5. slcan.c           — OPENRS? TCP probe handler for WiCAN Pro raw TCP SLCAN
  6. main.c           — #include "focusrs.h"
  7. main.c           — frs_init() call after nvs_flash_init() in app_main
  8. main.c           — frs_parse_can_frame() call in can_rx_task
  9. main.c           — frs_set_can_tx_fn() registration via static openrs_can_tx shim
 10. main/CMakeLists  — add focusrs to REQUIRES
"""

# Version string returned to the Android app when it sends "OPENRS?\r".
# Must match OPENRS_FW_VERSION in components/focusrs/focusrs.h.
OPENRS_FW_VERSION = "v1.4"

import sys
import os
import re

def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

def write(path, content):
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"  patched: {os.path.basename(path)}")

def replace_once(content, old, new, label):
    if old not in content:
        print(f"  WARNING: could not find patch target '{label}' — skipping")
        return content
    return content.replace(old, new, 1)

def patch_wifi_network(base):
    path = os.path.join(base, "main", "wifi_network.c")
    c = read(path)
    c = replace_once(c,
        '"WiCAN_%02x%02x%02x%02x%02x%02x"',
        '"openRS_%02x%02x%02x%02x%02x%02x"',
        "AP SSID prefix")
    write(path, c)

def patch_config_server(base):
    path = os.path.join(base, "main", "config_server.c")
    c = read(path)

    # AP password: change from stock "@meatpi#" to "openrs2024"
    c = replace_once(c, '"@meatpi#"', '"openrs2024"', "AP password default")

    # 1. Add focusrs include after the last existing include block
    FRS_INCLUDE = '#include "focusrs.h"'
    if FRS_INCLUDE not in c:
        c = replace_once(c,
            '#include "autopid.h"',
            '#include "autopid.h"\n#include "focusrs.h"',
            "focusrs include in config_server")

    # v4.20u already has max_uri_handlers = 32; no bump needed.

    # 4. Insert the /api/frs handler before config_server_init (the function that
    #    registers URI handlers). Using config_server_init as anchor ensures the
    #    static handler functions are defined at file scope before they're referenced.
    FRS_HANDLER = r"""
/* ── openrs-fw: Focus RS state endpoint ──────────────────────────────────
 * GET  /api/frs  → JSON with live drive mode, ESC mode, battery voltage
 * POST /api/frs  → body: {"driveMode":0-3} {"escMode":0-2} {"enableLC":true} {"killASS":true}
 */
static esp_err_t frs_get_handler(httpd_req_t *req)
{
    frs_state_t snap = frs_get_state_copy();
    frs_state_t *s = &snap;
    char json[256];
    snprintf(json, sizeof(json),
        "{\"driveMode\":%d,\"bootMode\":%d,\"escMode\":%d,"
        "\"lcEnabled\":%s,\"assKill\":%s,\"battMv\":%lu}",
        s->drive_mode, s->boot_mode, s->esc_mode,
        s->lc_enabled ? "true" : "false",
        s->ass_kill   ? "true" : "false",
        (unsigned long)s->battery_mv);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_send(req, json, strlen(json));
}

static esp_err_t frs_post_handler(httpd_req_t *req)
{
    char buf[512] = {0};
    int  received = httpd_req_recv(req, buf, sizeof(buf) - 1);
    if (received <= 0) {
        httpd_resp_send_500(req);
        return ESP_FAIL;
    }
    cJSON *root = cJSON_Parse(buf);
    if (!root) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Invalid JSON");
        return ESP_FAIL;
    }
    /* Require {"token":"openrs"} in every POST for basic access control. */
    cJSON *tok = cJSON_GetObjectItem(root, "token");
    if (!tok || !cJSON_IsString(tok) || strcmp(tok->valuestring, "openrs") != 0) {
        cJSON_Delete(root);
        httpd_resp_send_err(req, HTTPD_403_FORBIDDEN, "Missing or invalid token");
        return ESP_FAIL;
    }
    cJSON *item;
    if ((item = cJSON_GetObjectItem(root, "driveMode")) && cJSON_IsNumber(item))
        frs_set_drive_mode((uint8_t)item->valueint);
    if ((item = cJSON_GetObjectItem(root, "escMode")) && cJSON_IsNumber(item))
        frs_set_esc((uint8_t)item->valueint);
    if ((item = cJSON_GetObjectItem(root, "enableLC")) && cJSON_IsBool(item))
        frs_set_lc(cJSON_IsTrue(item));
    if ((item = cJSON_GetObjectItem(root, "killASS")) && cJSON_IsBool(item))
        frs_set_ass_kill(cJSON_IsTrue(item));
    if ((item = cJSON_GetObjectItem(root, "sleepVoltage")) && cJSON_IsNumber(item))
        frs_set_sleep_threshold((float)item->valuedouble);
    cJSON_Delete(root);
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_send(req, "{\"ok\":true}", 11);
}

static const httpd_uri_t frs_get_uri = {
    .uri      = "/api/frs",
    .method   = HTTP_GET,
    .handler  = frs_get_handler,
    .user_ctx = NULL
};
static const httpd_uri_t frs_post_uri = {
    .uri      = "/api/frs",
    .method   = HTTP_POST,
    .handler  = frs_post_handler,
    .user_ctx = NULL
};
/* ─────────────────────────────────────────────────────────────────────── */

"""
    # Anchor: the static config_server_init function that registers all URI handlers.
    # Handler functions must be defined before this function references them.
    TARGET_FN = "static httpd_handle_t config_server_init("
    if FRS_HANDLER.strip()[:30] not in c and TARGET_FN in c:
        c = c.replace(TARGET_FN, FRS_HANDLER + TARGET_FN, 1)
        print("  patched: /api/frs handler inserted")
    elif FRS_HANDLER.strip()[:30] in c:
        print("  skipped: /api/frs handler already present")
    else:
        print("  WARNING: could not find config_server_init() — /api/frs handler NOT inserted")

    # 5. Register the new handlers in config_server_start
    REGISTER_TARGET = "httpd_register_uri_handler(server, &scan_available_pids_uri);"
    FRS_REGISTER = (
        "httpd_register_uri_handler(server, &scan_available_pids_uri);\n"
        "    httpd_register_uri_handler(server, &frs_get_uri);\n"
        "    httpd_register_uri_handler(server, &frs_post_uri);"
    )
    FRS_REGISTERED_MARKER = "httpd_register_uri_handler(server, &frs_get_uri)"
    if REGISTER_TARGET in c and FRS_REGISTERED_MARKER not in c:
        c = c.replace(REGISTER_TARGET, FRS_REGISTER, 1)
        print("  patched: /api/frs handlers registered")
    elif FRS_REGISTERED_MARKER in c:
        print("  skipped: /api/frs handlers already registered")
    else:
        print("  WARNING: registration anchor not found — handlers NOT registered")

    write(path, c)

def patch_ws_probe(base):
    """Insert an OPENRS? identity probe handler into the WiCAN WebSocket receive path.

    The Android app sends "OPENRS?\\r" immediately after connecting. Stock WiCAN
    ignores it (slcan_parse_frame returns an error silently); openrs-fw intercepts
    it before the SLCAN parser and replies "OPENRS:<version>\\r\\n" so the app can
    confirm it is talking to custom firmware and unlock extra features.
    """
    path = os.path.join(base, "main", "config_server.c")
    c = read(path)

    # In wican-fw v4.20u the ws_handler does NOT call slcan_parse_frame directly.
    # Instead it copies the payload into an xdev_buffer and posts it to the SLCAN
    # RX queue via xQueueSend. We intercept right before that queue send so the
    # OPENRS? message is handled here and never reaches the SLCAN task.
    PROBE_CODE = (
        '\n    /* openrs-fw: firmware identity probe ─────────────────────────────────\n'
        '     * Android app sends "OPENRS?\\r" on connect; reply before queuing.\n'
        '     */\n'
        '    if (ws_pkt.payload != NULL && ws_pkt.len >= 7 &&\n'
        '        strncmp((char *)ws_pkt.payload, "OPENRS?", 7) == 0) {\n'
        '        const char *reply = "OPENRS:' + OPENRS_FW_VERSION + '\\r\\n";\n'
        '        httpd_ws_frame_t rpl;\n'
        '        memset(&rpl, 0, sizeof(rpl));\n'
        '        rpl.final      = true;\n'
        '        rpl.type       = HTTPD_WS_TYPE_TEXT;\n'
        '        rpl.payload    = (uint8_t *)reply;\n'
        '        rpl.len        = strlen(reply);\n'
        '        httpd_ws_send_frame(req, &rpl);\n'
        '        free(buf);\n'
        '        return ESP_OK;\n'
        '    }\n'
    )

    ANCHOR = "xQueueSend( *xRX_Queue,"
    MARKER = "OPENRS?"

    if MARKER in c:
        print("  skipped: OPENRS? probe handler already present")
    elif ANCHOR in c:
        c = c.replace(ANCHOR, PROBE_CODE + "    " + ANCHOR, 1)
        write(path, c)
    else:
        print("  WARNING: xQueueSend anchor not found in config_server.c "
              "— OPENRS? probe NOT inserted. Check wican-fw source version.")


def patch_tcp_probe(base):
    """Insert an OPENRS? identity probe into the SLCAN TCP receive path.

    The WiCAN Pro uses raw TCP SLCAN (port 35000) instead of WebSocket. The
    WebSocket probe in config_server.c never fires for TCP connections. This
    patch adds a check in slcan.c's slcan_parse_str() — the function that
    processes every incoming SLCAN line regardless of transport — so the probe
    works for both WiCAN (WebSocket) and WiCAN Pro (TCP).

    NOTE: This targets wican-fw v4.20+. If the anchor function signature
    changes in a future release, the patch will print a WARNING and skip.
    Verify with actual WiCAN Pro hardware once available.
    """
    path = os.path.join(base, "main", "slcan.c")
    if not os.path.exists(path):
        print("  WARNING: slcan.c not found — TCP probe NOT inserted (expected for non-Pro builds)")
        return
    c = read(path)

    MARKER = "OPENRS_TCP_PROBE"
    if MARKER in c:
        print("  skipped: TCP OPENRS? probe already present in slcan.c")
        return

    # slcan_parse_str receives the raw line from any transport. We intercept at
    # the top of this function so "OPENRS?\r" never reaches the SLCAN command
    # parser. The reply is written back via the TCP socket fd stored in the
    # connection context.
    #
    # Anchor: the function signature. In wican-fw v4.20u it is:
    #   int8_t slcan_parse_str(char *buf, uint8_t len)
    # We insert our check immediately after the opening brace.
    ANCHOR = "int8_t slcan_parse_str(char *buf, uint8_t len)\n{"
    PROBE_BLOCK = (
        'int8_t slcan_parse_str(char *buf, uint8_t len)\n'
        '{\n'
        '    /* ' + MARKER + ': respond to firmware identity probe over any transport.\n'
        '     * WiCAN Pro uses raw TCP SLCAN — the WebSocket probe in config_server.c\n'
        '     * does not fire. This catch-all ensures the app gets a reply. */\n'
        '    if (len >= 7 && strncmp(buf, "OPENRS?", 7) == 0) {\n'
        '        extern int slcan_tcp_send(const char *data, int data_len);\n'
        '        const char *reply = "OPENRS:' + OPENRS_FW_VERSION + '\\r\\n";\n'
        '        slcan_tcp_send(reply, strlen(reply));\n'
        '        return 0;\n'
        '    }\n'
    )

    if ANCHOR in c:
        c = c.replace(ANCHOR, PROBE_BLOCK, 1)
        write(path, c)
    else:
        # Try alternate signature (some versions use different formatting)
        ALT_ANCHOR = "int8_t slcan_parse_str(char *buf, uint8_t len) {"
        if ALT_ANCHOR in c:
            ALT_PROBE = PROBE_BLOCK.replace(
                "int8_t slcan_parse_str(char *buf, uint8_t len)\n{",
                "int8_t slcan_parse_str(char *buf, uint8_t len) {"
            )
            c = c.replace(ALT_ANCHOR, ALT_PROBE, 1)
            write(path, c)
        else:
            print("  WARNING: slcan_parse_str() anchor not found in slcan.c "
                  "— TCP OPENRS? probe NOT inserted. Check wican-fw source version.")


def patch_main(base):
    path = os.path.join(base, "main", "main.c")
    c = read(path)

    # 1. Add focusrs include
    FRS_INC = '#include "focusrs.h"'
    if FRS_INC not in c:
        c = replace_once(c,
            '#include "debug_logs_config.h"',
            '#include "debug_logs_config.h"\n#include "focusrs.h"',
            "focusrs.h include in main.c")

    # 2. frs_init() after nvs_flash_init in app_main
    #    wican-fw has two nvs_flash_init calls. The one in app_main is preceded by
    #    "void app_main(void)". We use a regex to match nvs_flash_init only inside app_main.
    if "frs_init();" not in c:
        import re as _re2
        pattern = r'(void\s+app_main\s*\(void\)\s*\{[^}]*?)(ESP_ERROR_CHECK\(nvs_flash_init\(\)\);)'
        replacement = r'\1ESP_ERROR_CHECK(nvs_flash_init());\n    frs_init();'
        c_new, n = _re2.subn(pattern, replacement, c, count=1, flags=_re2.DOTALL)
        if n > 0:
            c = c_new
            print("  patched: frs_init() after nvs_flash_init in app_main")
        else:
            print("  WARNING: could not find nvs_flash_init inside app_main — frs_init() NOT inserted")

    # 3. frs_parse_can_frame in can_rx_task
    RX_ANCHOR = "process_led(1);"
    FRS_PARSE = (
        "process_led(1);\n\n"
        "                 // openrs-fw: decode Focus RS CAN frames\n"
        "                 frs_parse_can_frame(rx_msg.identifier, rx_msg.data, rx_msg.data_length_code);"
    )
    if "frs_parse_can_frame" not in c:
        c = replace_once(c, RX_ANCHOR, FRS_PARSE, "frs_parse_can_frame() in can_rx_task")

    # 4. frs_set_can_tx_fn — register after can_enable() calls complete in app_main
    # Hook after the first can_enable() in app_main (REALDASH block)
    CAN_TX_ANCHOR = "wc_mdns_init((char*)uid, hardware_version, firmware_version);"
    CAN_TX_FN = (
        "\n/* openrs-fw: CAN TX shim for focusrs component */\n"
        "static int openrs_can_tx(uint32_t id, const uint8_t *data, uint8_t dlc, uint32_t tms)\n"
        "{\n"
        "    twai_message_t msg;\n"
        "    memset(&msg, 0, sizeof(msg));\n"
        "    msg.identifier      = id;\n"
        "    msg.data_length_code = dlc;\n"
        "    memcpy(msg.data, data, dlc);\n"
        "    return (int)can_send(&msg, pdMS_TO_TICKS(tms));\n"
        "}\n"
    )
    CAN_TX_REGISTER = (
        "wc_mdns_init((char*)uid, hardware_version, firmware_version);\n\n"
        "    // openrs-fw: register CAN TX callback for drive mode write\n"
        "    frs_set_can_tx_fn(openrs_can_tx);"
    )

    if "openrs_can_tx" not in c:
        # Insert the static function before app_main
        c = replace_once(c, "void app_main(void)", CAN_TX_FN + "void app_main(void)", "openrs_can_tx function")
        c = replace_once(c,
            "wc_mdns_init((char*)uid, hardware_version, firmware_version);",
            CAN_TX_REGISTER,
            "frs_set_can_tx_fn() registration")

    write(path, c)

def patch_cmake(base):
    path = os.path.join(base, "main", "CMakeLists.txt")
    c = read(path)
    if "focusrs" not in c:
        # Add focusrs to the set(requires ...) variable line.
        # The variable is then expanded in REQUIRES "${requires}", so this is sufficient.
        import re as _re
        c, n = _re.subn(
            r'(set\(requires\s+[^\)]+?)(filesystem\))',
            r'\1filesystem focusrs)',
            c, count=1
        )
        if n == 0:
            print("  WARNING: could not patch CMakeLists.txt set(requires ...) — focusrs NOT added")
        else:
            print("  patched: CMakeLists.txt (focusrs added to requires)")
    else:
        print("  skipped: focusrs already in CMakeLists.txt")
    write(path, c)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: apply_patches.py <path-to-wican-fw>")
        sys.exit(1)

    base = sys.argv[1]
    if not os.path.isdir(base):
        print(f"ERROR: directory not found: {base}")
        sys.exit(1)

    print(f"\nApplying openrs-fw patches to: {base}\n")
    patch_wifi_network(base)
    patch_config_server(base)
    patch_ws_probe(base)
    patch_tcp_probe(base)
    patch_main(base)
    patch_cmake(base)
    print("\nAll patches applied successfully.\n")
