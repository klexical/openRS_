# ble_transport is a header-only interface in v1.0.
# BLE is handled by wican-fw's existing ble.c (main component).
# This component exists for future standalone BLE GATT implementation.
idf_component_register(
    INCLUDE_DIRS "."
)
