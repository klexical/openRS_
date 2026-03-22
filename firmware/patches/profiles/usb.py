"""Device profile: WiCAN USB-C3 (ESP32-C3) — wican-fw v4.20u.

Verified and tested. This is the primary build target.
"""

PROFILE = {
    "name": "usb",
    "description": "WiCAN USB-C3 (ESP32-C3)",
    "wican_tag": "v4.20u_beta-01",
    "soc": "esp32c3",
    "idf_target": "esp32c3",
    "sdkconfig": "sdkconfig.defaults.usb",
    "partitions": "partitions_openrs_usb.csv",
    "output_prefix": "openrs-fw-usb",

    # HARDWARE_VER is set in wican-fw's CMakeLists.txt per tag.
    # USB tag already has: set(HARDWARE_VER ${WICAN_USB_V100})  → 3

    "anchors": {
        # WebSocket OPENRS? probe — intercept before xQueueSend in config_server.c ws_handler
        "ws_probe_queue": "xQueueSend( *xRX_Queue,",

        # CAN TX shim registration — inserted after mDNS init in app_main
        "can_tx_register": "wc_mdns_init((char*)uid, hardware_version, firmware_version);",
        "can_tx_register_replacement": (
            "wc_mdns_init((char*)uid, hardware_version, firmware_version);\n\n"
            "    // openrs-fw: register CAN TX callback and apply boot settings\n"
            "    frs_set_can_tx_fn(openrs_can_tx);\n"
            "    frs_boot_apply();"
        ),
    },

    "has_ws_probe": True,
    "has_can_tx": True,
    "verified": True,
}
