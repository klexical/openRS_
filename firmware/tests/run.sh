#!/bin/sh
# Host-side unit tests for pure focusrs logic. Compiles with the system C compiler; never builds or flashes firmware.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
cc -std=c99 -Wall -Wextra -Werror -I"$here/../components/focusrs" \
   "$here/test_esc_plan.c" "$here/../components/focusrs/focusrs_esc_plan.c" -o "$out/test_esc_plan"
"$out/test_esc_plan"
rm -rf "$out"
