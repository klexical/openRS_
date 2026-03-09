#pragma once
#include "focusrs.h"

void frs_nvs_load(frs_state_t *state);
void frs_nvs_save_boot_mode(uint8_t mode);
void frs_nvs_save_esc(uint8_t mode);
void frs_nvs_save_lc(bool enabled);
void frs_nvs_save_ass_kill(bool enabled);
void frs_nvs_save_sleep_threshold(uint16_t mv);
