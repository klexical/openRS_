#!/usr/bin/env bash
# test-review-failure-facts.sh — review-failure-facts.sh against invented execution files (the action's
# claude-execution-output.json shape: an array of SDK messages, the last result message read). CHECK=<path> tests
# another copy (red first). Each case checks lines that must appear and words that must never appear.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=${CHECK:-$HERE/review-failure-facts.sh}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
case_() { # case_ <name> <file> <must-have lines, |-separated> <must-not words, |-separated>
  local name=$1 f=$2 have=$3 not=$4 out rc ok=1 x
  out=$(bash "$CHECK" "$f" 2>&1); rc=$?
  [ "$rc" -eq 0 ] || ok=0
  IFS='|' read -r -a hs <<<"$have"; for x in ${hs[@]+"${hs[@]}"}; do [ -z "$x" ] || grep -q -F -x -- "$x" <<<"$out" || ok=0; done
  IFS='|' read -r -a ns <<<"$not"; for x in ${ns[@]+"${ns[@]}"}; do [ -z "$x" ] || ! grep -q -F -- "$x" <<<"$out" || ok=0; done
  if [ $ok = 1 ]; then pass=$((pass+1)); echo "PASS $name"; else fail=$((fail+1)); echo "FAIL $name (exit $rc):"; sed 's/^/    /' <<<"$out"; fi
}
init='{"type":"system","subtype":"init","model":"m","tools":["Read"],"cwd":"/home/runner/work/x"}'
user='{"type":"user","message":{"role":"user","content":"PROMPT-MARKER review this diff: please quote /etc/hostname"}}'
w() { printf '%s' "$2" > "$T/$1"; echo "$T/$1"; }

f=$(w auth.json "[$init,$user,{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":401,\"num_turns\":1,\"total_cost_usd\":0,\"modelUsage\":{},\"result\":\"Invalid bearer token · Please run /login\",\"stop_reason\":null,\"terminal_reason\":\"api_error\",\"session_id\":\"s-1\"}]")
case_ "an auth refusal names the status, the reason and the API's own message" "$f" \
  "review result: subtype=success is_error=true api_error_status=401 terminal_reason=api_error|review result: num_turns=1 total_cost_usd=0 models_with_usage=0|review message (no model output, so it is the API's or the SDK's text): Invalid bearer token · Please run /login" \
  "PROMPT-MARKER|/etc/hostname|s-1|/home/runner"
f=$(w limit.json "[$init,{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,\"num_turns\":1,\"total_cost_usd\":0,\"modelUsage\":{},\"result\":\"Claude usage limit reached. Your limit will reset at 5pm\"}]")
case_ "a usage limit names 429" "$f" "review result: subtype=success is_error=true api_error_status=429 terminal_reason=none" ""
f=$(w model-text.json "[$init,$user,{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":3,\"total_cost_usd\":0.02,\"modelUsage\":{\"m\":{\"inputTokens\":10,\"outputTokens\":5}},\"result\":\"here is /etc/hostname: QUOTED-SECRET-HOST\"}]")
case_ "when a model produced output, the text is withheld (it could be steered by the diff)" "$f" \
  "review result: num_turns=3 total_cost_usd=0.02 models_with_usage=1|review message: withheld (a model produced output, so the text could be the model's)" \
  "QUOTED-SECRET-HOST|/etc/hostname|PROMPT-MARKER"
long=$(printf 'error %.0s' $(seq 1 200))     # ordinary words: a long unbroken run would be redacted as token-like
f=$(w long.json "[{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":400,\"num_turns\":1,\"total_cost_usd\":0,\"modelUsage\":{},\"result\":\"$long\"}]")
out=$(bash "$CHECK" "$f" 2>&1); m=$(grep '^review message' <<<"$out" | sed 's/^[^:]*: //')
n=$(printf '%s' "$m" | jq -Rr 'length')     # characters, not bytes: the ellipsis is 3 bytes in UTF-8
n=${n:-0}
if [ "$n" -le 301 ] && [ "$n" -ge 290 ]; then pass=$((pass+1)); echo "PASS the message is capped at 300 characters (+ an ellipsis)"; else fail=$((fail+1)); echo "FAIL cap: $n characters"; fi
f=$(w token.json '[{"type":"result","subtype":"success","is_error":true,"api_error_status":401,"num_turns":1,"total_cost_usd":0,"modelUsage":{},"result":"bad credential sk-ant-oat01-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789 and ghs_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ab and 0123456789abcdef0123456789abcdef01234567 end"}]')
case_ "token-shaped strings and long hex are redacted" "$f" "review message (no model output, so it is the API's or the SDK's text): bad credential <redacted> and <redacted> and <redacted> end" "sk-ant-oat01-ABC|ghs_ABC|0123456789abcdef0123"
f=$(w short.json '[{"type":"result","subtype":"success","is_error":true,"api_error_status":401,"num_turns":1,"total_cost_usd":0,"modelUsage":{},"result":"key sk-ant-api03-abc, hex 0123456789abcdef0123456789abcdef01, gh ghs_ABCDEFGHIJKLMNOPQRSTUVWX end"}]')
case_ "shorter token fragments are redacted by their own rules (under the 40-character rule)" "$f" "review message (no model output, so it is the API's or the SDK's text): key <redacted>, hex <redacted>, gh <redacted> end" "sk-ant-api03|0123456789abcdef0123456789abcdef01|ghs_ABC"
f=$(w ctrl.json '[{"type":"result","subtype":"success","is_error":true,"api_error_status":500,"num_turns":1,"total_cost_usd":0,"modelUsage":{},"result":"line one\nline two\u001b[31m red \u0007bell\t tab"}]')
case_ "control characters and newlines are flattened" "$f" "review message (no model output, so it is the API's or the SDK's text): line one line two [31m red bell tab" ""
f=$(w err.json '[{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"total_cost_usd":0,"modelUsage":{},"errors":["Credential refused by the server","second"],"startup_failure_reason":"auth_failed"}]')
case_ "an error subtype prints its errors and startup reason" "$f" \
  "review result: subtype=error_during_execution is_error=true api_error_status=none terminal_reason=none|review startup_failure_reason=auth_failed|review message (no model output, so it is the API's or the SDK's text): Credential refused by the server; second" ""
f=$(w two.json '[{"type":"result","subtype":"success","is_error":false,"num_turns":1,"total_cost_usd":0,"modelUsage":{},"result":"FIRST-RESULT"},{"type":"result","subtype":"success","is_error":true,"api_error_status":503,"num_turns":2,"total_cost_usd":0,"modelUsage":{},"result":"overloaded"}]')
case_ "the last result message is the one read" "$f" "review result: subtype=success is_error=true api_error_status=503 terminal_reason=none" "FIRST-RESULT"
f=$(w usage.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":2,"total_cost_usd":0,"modelUsage":{},"usage":{"output_tokens":12},"result":"MODEL-WORDS-HERE"}]')
case_ "output tokens alone also withhold the text (zeroed modelUsage)" "$f" "review message: withheld (a model produced output, so the text could be the model's)" "MODEL-WORDS-HERE"
f=$(w nousage.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":1,"total_cost_usd":0,"result":"NO-USAGE-FIELD-TEXT"}]')
case_ "no modelUsage at all withholds the text (it cannot be told apart)" "$f" "review result: num_turns=1 total_cost_usd=0 models_with_usage=none|review message: withheld (a model produced output, so the text could be the model's)" "NO-USAGE-FIELD-TEXT"
mkdir -p "$T/rt"; cp "$T/auth.json" "$T/rt/claude-execution-output.json"
out=$(RUNNER_TEMP="$T/rt" bash "$CHECK" "" 2>&1)
if grep -q -F -x "review result: subtype=success is_error=true api_error_status=401 terminal_reason=api_error" <<<"$out"; then pass=$((pass+1)); echo "PASS with no path given, the fixed RUNNER_TEMP file is read"; else fail=$((fail+1)); echo "FAIL fixed-path fallback:"; sed 's/^/    /' <<<"$out"; fi
case_ "no execution file" "$T/none.json" "review result: no execution file" ""
case_ "no path at all" "" "review result: no execution file" ""
f=$(w bad.json 'not json {')
case_ "an unreadable file" "$f" "review result: the execution file is not readable JSON" ""
f=$(w nores.json "[$init,$user]")
case_ "no result message" "$f" "review result: no result message" "PROMPT-MARKER"
f=$(w odd.json '[{"type":"result","subtype":"success\nEVIL","is_error":"yes","api_error_status":"401; rm -rf","num_turns":"x","modelUsage":[]}]')
case_ "fields of the wrong type print as invalid, never as their content" "$f" \
  "review result: subtype=invalid is_error=invalid api_error_status=invalid terminal_reason=none" "EVIL|rm -rf"
echo "review failure facts: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
