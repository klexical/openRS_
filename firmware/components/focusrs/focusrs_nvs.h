#pragma once
#include "focusrs.h"

void frs_nvs_load(frs_state_t *state);
// #628: erases the drive mode and ESC that older firmware saved and restored at start-up.
void frs_nvs_erase_restore_keys(void);
void frs_nvs_save_lc(bool enabled);
void frs_nvs_save_ass_kill(bool enabled);
void frs_nvs_save_sleep_threshold(uint16_t mv);
