#!/usr/bin/env bash
# check-release-config.sh — run by CI's publish-release before the release build (#235). Reads the API keys from the
# environment (the job maps its repository secrets onto these names) and never prints a value.
#   MAPS_API_KEY         required — a published APK without it shows a blank map ("Authorization failure", rc.6)
#   OPENWEATHER_API_KEY  optional — without it the app sends no weather request (WeatherRepository); noted, not failed
# A key must be a single token of letters, digits, '_' and '-' (a space or line break pasted into the secret is refused):
# it goes into AndroidManifest / BuildConfig verbatim. Contributor and debug builds don't run this — keys stay optional.
set -euo pipefail
fail() { echo "::error::$*"; exit 1; }
token() { [[ "$1" =~ ^[A-Za-z0-9_-]+$ ]]; }

MAPS="${MAPS_API_KEY:-}"
[ -n "$MAPS" ] || fail "MAPS_API_KEY is not set — a published release needs the repository secret MAPS_API_KEY (Google Maps SDK for Android key); without it TRIP shows no map tiles (#235)"
token "$MAPS" || fail "MAPS_API_KEY is not a single key token (only letters, digits, '_' and '-') — check the secret for spaces or line breaks"
echo "Maps key: set (${#MAPS} chars)"

WEATHER="${OPENWEATHER_API_KEY:-}"
if [ -z "$WEATHER" ]; then
  echo "::notice::OPENWEATHER_API_KEY is not set — this release sends no weather requests (optional)"
else
  token "$WEATHER" || fail "OPENWEATHER_API_KEY is not a single key token (only letters, digits, '_' and '-') — check the secret for spaces or line breaks"
  echo "Weather key: set (${#WEATHER} chars)"
fi
