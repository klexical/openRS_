idf_component_register(
    SRCS "focusrs.c" "focusrs_nvs.c"
    INCLUDE_DIRS "."
    REQUIRES nvs_flash driver esp_log freertos
)
