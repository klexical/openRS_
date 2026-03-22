"""Device profile: WiCAN Pro (ESP32-S3) — wican-fw v4.48p.

Anchors verified against v4.48p source (main.c line 1109, config_server.c).
The Pro's main.c is ~2x larger than USB but shares the same wc_mdns_init()
anchor for CAN TX registration and the same process_led(1) anchor for CAN RX.
"""

PROFILE = {
    "name": "pro",
    "description": "WiCAN Pro (ESP32-S3)",
    "wican_tag": "v4.48p",
    "soc": "esp32s3",
    "idf_target": "esp32s3",
    "sdkconfig": "sdkconfig.defaults.pro",
    "partitions": "partitions_openrs_pro.csv",
    "output_prefix": "openrs-fw-pro",

    # HARDWARE_VER is set in wican-fw's CMakeLists.txt per tag.
    # Pro tag already has: set(HARDWARE_VER ${WICAN_PRO})  → 4

    "anchors": {
        # The Pro's config_server.c ws_handler does NOT use xQueueSend(*xRX_Queue).
        # The WebSocket path was refactored in v4.4x. The universal SLCAN probe in
        # slcan.c handles firmware detection for all transports (TCP and WS).
        "ws_probe_queue": None,

        # wc_mdns_init() IS present in the Pro's main.c (line 1109 in v4.48p) —
        # same anchor string as USB. CAN TX registration hooks after it.
        "can_tx_register": "wc_mdns_init((char*)uid, hardware_version, firmware_version);",
        "can_tx_register_replacement": (
            "wc_mdns_init((char*)uid, hardware_version, firmware_version);\n\n"
            "    // openrs-fw: register CAN TX callback and apply boot settings\n"
            "    frs_set_can_tx_fn(openrs_can_tx);\n"
            "    frs_boot_apply();"
        ),
    },

    # Pro uses TCP SLCAN — the universal slcan.c probe covers firmware detection.
    "has_ws_probe": False,

    # CAN TX supported — same wc_mdns_init anchor as USB, verified in v4.48p source.
    "has_can_tx": True,

    "verified": False,
}
