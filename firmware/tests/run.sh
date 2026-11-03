#!/bin/sh
# Host-side unit tests for focusrs logic. Compiles with the system C compiler; never builds or flashes firmware.
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../components/focusrs"
out=$(mktemp -d)
cc -std=c99 -Wall -Wextra -Werror -I"$src" \
   "$here/test_esc_plan.c" "$src/focusrs_esc_plan.c" -o "$out/test_esc_plan"
"$out/test_esc_plan"
# The real focusrs.c (CAN parser → ESC task → boot ESC persistence) against host stubs for FreeRTOS / TWAI / ESP log
# (stubs/); NVS, UDS and CAN TX are stubbed in the test itself.
cc -std=c99 -Wall -Wextra -Werror -Wno-unused-parameter -I"$here/stubs" -I"$src" \
   "$here/test_esc_confirm.c" "$src/focusrs.c" "$src/focusrs_esc_plan.c" -o "$out/test_esc_confirm"
"$out/test_esc_confirm"
rm -rf "$out"
