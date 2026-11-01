#!/usr/bin/env bash
# test-release-config.sh — checks check-release-config.sh (#235): missing / malformed Maps key fails, weather is optional,
# and no key value ever reaches the output. Placeholder keys only. Run from anywhere: bash android/scripts/test-release-config.sh
set -uo pipefail
CHECK="$(cd "$(dirname "$0")" && pwd)/check-release-config.sh"
MAPS="AIzaTESTplaceholderMapsKey_0123456789-x"
WEATHER="0123456789abcdef0123456789abcdef"
failures=0

# run <name> <expected exit> <output must contain> <env assignments...>
run() {
  local name="$1" want="$2" needle="$3"; shift 3
  local out code
  out=$(env -u MAPS_API_KEY -u OPENWEATHER_API_KEY "$@" bash "$CHECK" 2>&1); code=$?
  local why=""
  [ "$code" -eq "$want" ] || why="exit $code, expected $want"
  [[ "$out" == *"$needle"* ]] || why="${why:+$why; }output lacks '$needle'"
  if [[ "$out" == *"$MAPS"* || "$out" == *"$WEATHER"* || "$out" == *"bad key"* ]]; then why="${why:+$why; }a key value was printed"; fi
  if [ -z "$why" ]; then echo "PASS $name"; else echo "FAIL $name — $why"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); fi
}

run "missing Maps fails"                1 "::error::MAPS_API_KEY is not set"
run "empty Maps fails"                  1 "::error::MAPS_API_KEY is not set"            MAPS_API_KEY=
run "missing Maps fails with weather"   1 "::error::MAPS_API_KEY is not set"            OPENWEATHER_API_KEY="$WEATHER"
run "Maps with a space fails"           1 "::error::MAPS_API_KEY is not a single key"   MAPS_API_KEY="bad key"
run "Maps with a line break fails"      1 "::error::MAPS_API_KEY is not a single key"   MAPS_API_KEY="$MAPS"$'\n'
run "Maps only: weather optional"       0 "::notice::OPENWEATHER_API_KEY is not set"    MAPS_API_KEY="$MAPS"
run "Maps + weather"                    0 "Weather key: set (32 chars)"                 MAPS_API_KEY="$MAPS" OPENWEATHER_API_KEY="$WEATHER"
run "malformed weather fails"           1 "::error::OPENWEATHER_API_KEY is not a single" MAPS_API_KEY="$MAPS" OPENWEATHER_API_KEY="bad key"

[ "$failures" -eq 0 ] && echo "release config checks: all passed" || { echo "release config checks: $failures failed"; exit 1; }
