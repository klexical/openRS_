#pragma once
#include "freertos/FreeRTOS.h"
#define xSemaphoreCreateMutex() ((SemaphoreHandle_t)1)
#define xSemaphoreTake(m, t) ((void)(m), (void)(t), pdTRUE)
#define xSemaphoreGive(m) ((void)(m), pdTRUE)
