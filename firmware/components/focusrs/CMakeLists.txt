idf_component_register(
    SRCS "focusrs.c" "focusrs_nvs.c" "focusrs_uds.c" "focusrs_esc_plan.c"
    INCLUDE_DIRS "."
    REQUIRES nvs_flash driver log freertos
)
