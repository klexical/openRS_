#pragma once
#include "freertos/FreeRTOS.h"
// The test harness advances a fake clock (and delivers scheduled CAN frames) on every delay, and runs created tasks
// itself, synchronously.
void host_task_delay(TickType_t ticks);
BaseType_t host_task_create(TaskFunction_t fn, const char *name, uint32_t stack, void *arg, int prio, TaskHandle_t *h);
#define vTaskDelay(t) host_task_delay(t)
#define xTaskCreate(fn, name, stack, arg, prio, h) host_task_create((fn), (name), (stack), (arg), (prio), (h))
#define vTaskDelete(h) ((void)(h))
