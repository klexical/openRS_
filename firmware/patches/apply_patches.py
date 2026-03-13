#!/usr/bin/env python3
"""
apply_patches.py — applies openrs-fw modifications to a cloned wican-fw tree.

Usage:
  python3 apply_patches.py <path-to-wican-fw> [--target usb|pro]

Supports two build targets via device profiles:
  usb  — WiCAN USB-C3 (ESP32-C3), wican-fw v4.20u  [default]
  pro  — WiCAN Pro (ESP32-S3), wican-fw v4.48p      [UNVERIFIED]

Common patches (both targets):
  1. wifi_network.c   — AP SSID prefix: WiCAN_ → openRS_
  2. config_server.c  — AP password default: @meatpi# → openrs_2026
  3. config_server.c  — #include "focusrs.h"
  4. config_server.c  — Add /api/frs GET+POST endpoint (token-authenticated)
  5. config_server.c  — Register /api/frs URI handlers
  6. slcan.c          — OPENRS? probe in slcan_parse_str (all transports)
  7. main.c           — #include "focusrs.h"
  8. main.c           — frs_init() call after nvs_flash_init() in app_main
  9. main.c           — frs_parse_can_frame() call in can_rx_task
 10. main/CMakeLists  — add focusrs to REQUIRES

USB-only patches:
 11. config_server.c  — OPENRS? WebSocket probe (xQueueSend intercept)
 12. main.c           — openrs_can_tx shim + frs_set_can_tx_fn() registration

Pro-only patches:
  (same CAN TX shim as USB — wc_mdns_init anchor confirmed in v4.48p)
"""

OPENRS_FW_VERSION = "v1.4"

import sys
import os
import re
import argparse
import importlib

# ── Utilities ────────────────────────────────────────────────────────────────

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

def load_profile(target):
    """Import and return the device profile dict for the given target."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    profiles_dir = os.path.join(script_dir, "profiles")
    sys.path.insert(0, profiles_dir)
    try:
        mod = importlib.import_module(target)
        return mod.PROFILE
    except ModuleNotFoundError:
        print(f"ERROR: no profile found for target '{target}'")
        print(f"  Available profiles: {', '.join(f[:-3] for f in os.listdir(profiles_dir) if f.endswith('.py') and f != '__init__.py')}")
        sys.exit(1)

# ── Common patches (all targets) ────────────────────────────────────────────

def patch_wifi_network(base):
    """Change AP SSID prefix from WiCAN_ to openRS_."""
    path = os.path.join(base, "main", "wifi_network.c")
    c = read(path)
    c = replace_once(c,
        '"WiCAN_%02x%02x%02x%02x%02x%02x"',
        '"openRS_%02x%02x%02x%02x%02x%02x"',
        "AP SSID prefix")
    write(path, c)

def patch_config_server(base):
    """Add focusrs include, AP password change, and /api/frs REST endpoints."""
    path = os.path.join(base, "main", "config_server.c")
    c = read(path)

    c = replace_once(c, '"@meatpi#"', '"openrs_2026"', "AP password default")

    FRS_INCLUDE = '#include "focusrs.h"'
    if FRS_INCLUDE not in c:
        c = replace_once(c,
            '#include "autopid.h"',
            '#include "autopid.h"\n#include "focusrs.h"',
            "focusrs include in config_server")

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
    TARGET_FN = "static httpd_handle_t config_server_init("
    if FRS_HANDLER.strip()[:30] not in c and TARGET_FN in c:
        c = c.replace(TARGET_FN, FRS_HANDLER + TARGET_FN, 1)
        print("  patched: /api/frs handler inserted")
    elif FRS_HANDLER.strip()[:30] in c:
        print("  skipped: /api/frs handler already present")
    else:
        print("  WARNING: could not find config_server_init() — /api/frs handler NOT inserted")

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

def patch_slcan_probe(base):
    """Insert OPENRS? probe into slcan_parse_str() — works for all transports.

    The actual function signature in wican-fw is:
      char* slcan_parse_str(uint8_t *buf, uint8_t len, twai_message_t *frame, QueueHandle_t *q)

    The slcan_response function pointer (set during slcan_init) is used to send
    the reply back to whichever transport originated the message (TCP or WS).
    """
    path = os.path.join(base, "main", "slcan.c")
    if not os.path.exists(path):
        print("  WARNING: slcan.c not found — SLCAN probe NOT inserted")
        return
    c = read(path)

    MARKER = "OPENRS_SLCAN_PROBE"
    if MARKER in c:
        print("  skipped: SLCAN OPENRS? probe already present")
        return

    # The probe intercepts at the top of slcan_parse_str, before the existing
    # buffer copy and command parsing. Uses the slcan_response callback to reply
    # back to the originating transport (TCP socket or WebSocket frame).
    PROBE_CODE = (
        '\n    /* ' + MARKER + ': firmware identity probe ──────────────────────────\n'
        '     * Android app sends "OPENRS?\\r" after connecting. Intercept here\n'
        '     * before the SLCAN parser so it works over any transport (TCP/WS). */\n'
        '    if (len >= 7 && strncmp((char *)buf, "OPENRS?", 7) == 0) {\n'
        '        const char *reply = "OPENRS:' + OPENRS_FW_VERSION + '\\r\\n";\n'
        '        if (slcan_response != NULL) {\n'
        '            slcan_response((char *)reply, strlen(reply), q);\n'
        '        }\n'
        '        return NULL;\n'
        '    }\n'
    )

    # Match the function body opening. The signature is stable across v4.20u and v4.48p.
    ANCHOR = "char* slcan_parse_str(uint8_t *buf, uint8_t len, twai_message_t *frame, QueueHandle_t *q)\n{"
    ALT_ANCHOR = "char* slcan_parse_str(uint8_t *buf, uint8_t len, twai_message_t *frame, QueueHandle_t *q) {"

    for anchor in [ANCHOR, ALT_ANCHOR]:
        if anchor in c:
            replacement = anchor + PROBE_CODE
            c = c.replace(anchor, replacement, 1)
            write(path, c)
            return

    print("  WARNING: slcan_parse_str() anchor not found in slcan.c "
          "— OPENRS? probe NOT inserted. Check wican-fw source version.")

def patch_main_common(base):
    """Apply common main.c patches: focusrs include, init, CAN RX hook."""
    path = os.path.join(base, "main", "main.c")
    c = read(path)

    # 1. focusrs include
    FRS_INC = '#include "focusrs.h"'
    if FRS_INC not in c:
        c = replace_once(c,
            '#include "debug_logs_config.h"',
            '#include "debug_logs_config.h"\n#include "focusrs.h"',
            "focusrs.h include in main.c")

    # 2. frs_init() after nvs_flash_init in app_main (regex to target only app_main's call)
    if "frs_init();" not in c:
        pattern = r'(void\s+app_main\s*\(void\)\s*\{[^}]*?)(ESP_ERROR_CHECK\(nvs_flash_init\(\)\);)'
        replacement = r'\1ESP_ERROR_CHECK(nvs_flash_init());\n    frs_init();'
        c_new, n = re.subn(pattern, replacement, c, count=1, flags=re.DOTALL)
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

    write(path, c)
    return c

def patch_cmake(base):
    """Add focusrs to the REQUIRES list in main/CMakeLists.txt."""
    path = os.path.join(base, "main", "CMakeLists.txt")
    c = read(path)
    if "focusrs" not in c:
        c, n = re.subn(
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

# ── USB-only patches ─────────────────────────────────────────────────────────

def patch_ws_probe(base, profile):
    """Insert OPENRS? probe into the WebSocket handler (USB only).

    The WiCAN USB uses WebSocket SLCAN. This intercepts the probe in the
    ws_handler before xQueueSend so it never reaches the SLCAN task.
    The Pro does NOT have this anchor — it uses the universal slcan.c probe.
    """
    if not profile["has_ws_probe"]:
        print("  skipped: WebSocket probe not applicable for target '%s'" % profile["name"])
        return

    anchor = profile["anchors"]["ws_probe_queue"]
    if anchor is None:
        print("  skipped: no WS probe anchor for target '%s'" % profile["name"])
        return

    path = os.path.join(base, "main", "config_server.c")
    c = read(path)

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

    MARKER = "OPENRS?"
    if MARKER in c:
        print("  skipped: OPENRS? probe handler already present")
    elif anchor in c:
        c = c.replace(anchor, PROBE_CODE + "    " + anchor, 1)
        write(path, c)
    else:
        print("  WARNING: xQueueSend anchor not found in config_server.c "
              "— OPENRS? probe NOT inserted. Check wican-fw source version.")

def patch_can_tx(base, profile):
    """Insert the CAN TX shim and register it in app_main.

    The anchor for registration differs between USB (wc_mdns_init) and Pro
    (TBD — pending hardware). If the profile has no anchor, the patch is skipped
    and CAN write features won't be available until the anchor is identified.
    """
    if not profile["has_can_tx"]:
        print("  skipped: CAN TX registration not available for target '%s' (anchor TBD)" % profile["name"])
        return

    anchor = profile["anchors"]["can_tx_register"]
    replacement = profile["anchors"]["can_tx_register_replacement"]
    if anchor is None or replacement is None:
        print("  skipped: CAN TX anchor not defined for target '%s'" % profile["name"])
        return

    path = os.path.join(base, "main", "main.c")
    c = read(path)

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

    if "openrs_can_tx" not in c:
        c = replace_once(c, "void app_main(void)", CAN_TX_FN + "void app_main(void)", "openrs_can_tx function")
        c = replace_once(c, anchor, replacement, "frs_set_can_tx_fn() registration")
        write(path, c)
    else:
        print("  skipped: openrs_can_tx already present in main.c")

# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Apply openrs-fw patches to a cloned wican-fw tree."
    )
    parser.add_argument("base", help="Path to the cloned wican-fw directory")
    parser.add_argument(
        "--target", default="usb", choices=["usb", "pro"],
        help="Build target: usb (WiCAN USB-C3, default) or pro (WiCAN Pro)"
    )
    args = parser.parse_args()

    base = args.base
    if not os.path.isdir(base):
        print(f"ERROR: directory not found: {base}")
        sys.exit(1)

    profile = load_profile(args.target)

    if not profile["verified"]:
        print(f"\n  *** WARNING: target '{args.target}' is UNVERIFIED ***")
        print(f"  *** Patches may fail — verify with actual hardware ***\n")

    print(f"\nApplying openrs-fw patches to: {base}")
    print(f"Target: {profile['description']} (wican-fw {profile['wican_tag']})\n")

    # Common patches (all targets)
    print("[1/7] WiFi network...")
    patch_wifi_network(base)

    print("[2/7] Config server (REST API, password, includes)...")
    patch_config_server(base)

    print("[3/7] SLCAN probe (universal, all transports)...")
    patch_slcan_probe(base)

    print("[4/7] WebSocket probe (transport-specific)...")
    patch_ws_probe(base, profile)

    print("[5/7] Main (focusrs init, CAN RX hook)...")
    patch_main_common(base)

    print("[6/7] CAN TX shim (write support)...")
    patch_can_tx(base, profile)

    print("[7/7] CMakeLists (focusrs dependency)...")
    patch_cmake(base)

    print(f"\nAll patches applied for target '{profile['name']}'.")
    if not profile["verified"]:
        print("  NOTE: This target is unverified. Build may succeed but flash testing required.")
    print()

if __name__ == "__main__":
    main()
