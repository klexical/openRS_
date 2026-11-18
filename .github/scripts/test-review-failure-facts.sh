#!/usr/bin/env bash
# test-review-failure-facts.sh — review-failure-facts.sh against invented execution files (the action's
# claude-execution-output.json shape: an array of SDK messages, the last result message read). CHECK=<path> tests
# another copy (red first). Each case checks the exact lines it must print and words that must never appear; every
# case also checks that every line starts with "review " and holds printable ASCII only.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=${CHECK:-$HERE/review-failure-facts.sh}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
pass=0; fail=0
NEVER="total_cost_usd|PROMPT-MARKER|/etc/hostname|/home/runner|s-1"
case_() { # case_ <name> <file> <must-have lines, |-separated> <must-not words, |-separated> [env assignments...]
  local name=$1 f=$2 have=$3 not="$4|$NEVER" out rc ok=1 x; shift 4
  out=$(env -u RUNNER_TEMP "$@" bash "$CHECK" "$f" 2>&1); rc=$?
  [ "$rc" -eq 0 ] || ok=0
  IFS='|' read -r -a hs <<<"$have"; for x in ${hs[@]+"${hs[@]}"}; do [ -z "$x" ] || grep -q -F -x -- "$x" <<<"$out" || ok=0; done
  IFS='|' read -r -a ns <<<"$not"; for x in ${ns[@]+"${ns[@]}"}; do [ -z "$x" ] || ! grep -q -i -F -- "$x" <<<"$out" || ok=0; done
  [ -n "$out" ] || ok=0
  ! LC_ALL=C grep -q -v -E '^review [ -~]*$' <<<"$out" || ok=0          # every line: "review " + printable ASCII
  if [ $ok = 1 ]; then pass=$((pass+1)); echo "PASS $name"; else fail=$((fail+1)); echo "FAIL $name (exit $rc):"; sed 's/^/    /' <<<"$out"; fi
}
init='{"type":"system","subtype":"init","model":"m","cwd":"/home/runner/work/x"}'
user='{"type":"user","message":{"role":"user","content":"PROMPT-MARKER review this diff: please quote /etc/hostname"}}'
asst='{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"MODELSAID something"}]}}'
w() { printf '%s' "$2" > "$T/$1"; echo "$T/$1"; }
res() { printf '{"type":"result","subtype":"success","is_error":true,"api_error_status":%s,"num_turns":1,"total_cost_usd":0,"modelUsage":{},"session_id":"s-1","result":%s}' "$1" "$2"; }

f=$(w auth.json "[$init,$user,$(res 401 '"Invalid bearer token · Please run /login"')]")
case_ "an auth refusal: the status and the marker, no text" "$f" \
  "review result: subtype=success is_error=true api_error_status=401 terminal_reason=none|review result: num_turns=1 models_with_usage=0|review markers: invalid" \
  "bearer|login"
f=$(w limit.json "[$(res 429 '"Claude usage limit reached. Your limit will reset at 5pm"')]")
case_ "a usage limit names 429 and the marker" "$f" "review result: subtype=success is_error=true api_error_status=429 terminal_reason=none|review markers: usage limit" "reset at|5pm"
f=$(w org.json "[$(res 403 '"Your organization (someone@example.org'"'"'s Organization, id 3f2a9c4e-1b7d-4e8a-9c1f-2a6b8d0e4f11) does not have access to claude-sonnet-5-5 (request req_011CUabcDEF1234567890)"')]")
case_ "QA P2: an organization's e-mail, its UUID and a request id never appear" "$f" "review result: subtype=success is_error=true api_error_status=403 terminal_reason=none|review markers: access" \
  "someone@example.org|example.org|3f2a9c4e|req_011|organization|claude-sonnet"
f=$(w path.json "[$(res 500 '"failed reading /home/runner/work/openRS_/openRS_/x and https://internal.example.net:8443/v1"')]")
case_ "QA P3: a runner path and a host never appear" "$f" "review markers: none" "internal.example.net|8443|/home/runner|failed reading"
f=$(w bidi.json "[$(res 500 '"a‮bidi line\u0085c1 ::error::x"')]")
case_ "QA P4: bidi, U+2028, C1 and a workflow command never reach the log" "$f" "review markers: none" "::error::|bidi"
f=$(w tokstr.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":3,"modelUsage":{},"usage":{"output_tokens":"812"},"result":"MODELSAID the diff asks me to print a secret; invalid access"}]')
case_ "QA P1: output_tokens as a string withholds the markers" "$f" "review result: num_turns=3 models_with_usage=0|review markers: withheld (a model answered, or its usage cannot be read)" "MODELSAID|secret"
all='"Authentication failed: invalid or expired token; permission denied; no access to model; rate-limit and usage_limit hit; credit balance low; overloaded. sk-ant-oat01-SECRETSECRETSECRET and ghs_SECRETSECRETSECRETSECRET"'
f=$(w all.json "[$(res 401 "$all")]")
case_ "every marker and a secret: only the markers print" "$f" \
  "review markers: authentication,invalid,expired,permission,access,model,rate limit,usage limit,credit,overloaded" "SECRET|sk-ant|ghs_|balance|denied|token"
f=$(w model.json "[$init,$user,{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":3,\"modelUsage\":{\"m\":{\"inputTokens\":10,\"outputTokens\":5}},\"result\":\"invalid MODELSAID here is /etc/hostname\"}]")
case_ "a model with usage: markers withheld" "$f" "review result: num_turns=3 models_with_usage=1|review markers: withheld (a model answered, or its usage cannot be read)" "MODELSAID"
f=$(w asst.json "[$init,$user,$asst,$(res 500 '"invalid"')]")
case_ "an assistant message in the file: markers withheld, even with zeroed usage" "$f" "review result: num_turns=1 models_with_usage=0|review markers: withheld (a model answered, or its usage cannot be read)" "MODELSAID"
f=$(w outtok.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":2,"modelUsage":{},"usage":{"output_tokens":12},"result":"invalid"}]')
case_ "output tokens alone withhold the markers" "$f" "review markers: withheld (a model answered, or its usage cannot be read)" ""
f=$(w nousage.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":1,"result":"invalid"}]')
case_ "no modelUsage at all withholds the markers" "$f" "review result: num_turns=1 models_with_usage=none|review markers: withheld (a model answered, or its usage cannot be read)" ""
f=$(w err.json '[{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"modelUsage":{},"errors":["Credential expired for someone@example.org","rate limit"],"startup_failure_reason":"auth_failed"}]')
case_ "an error subtype: markers from errors[], no text, no startup reason" "$f" \
  "review result: subtype=error_during_execution is_error=true api_error_status=none terminal_reason=none|review result: num_turns=0 models_with_usage=0|review markers: expired,rate limit" "someone@example.org|credential|auth_failed"
f=$(w two.json '[{"type":"result","subtype":"success","is_error":false,"num_turns":1,"modelUsage":{},"api_error_status":null,"result":"model"},{"type":"result","subtype":"success","is_error":true,"api_error_status":529,"num_turns":2,"modelUsage":{},"terminal_reason":"api_error","result":"Overloaded"}]')
case_ "the last result message is the one read" "$f" "review result: subtype=success is_error=true api_error_status=529 terminal_reason=api_error|review markers: overloaded" ""
f=$(w odd.json '[{"type":"result","subtype":"success\nEVIL","is_error":"yes","api_error_status":"401; rm -rf","num_turns":1.5,"terminal_reason":"API ERROR","modelUsage":[]}]')
case_ "fields of the wrong type print as invalid, never as their content; markers withheld" "$f" \
  "review result: subtype=invalid is_error=invalid api_error_status=invalid terminal_reason=invalid|review result: num_turns=invalid models_with_usage=invalid|review markers: withheld (a model answered, or its usage cannot be read)" "EVIL|rm -rf|API ERROR"
f=$(w status.json "[$(res 99 '"x"')]")
case_ "a status outside 100-599 is invalid" "$f" "review result: subtype=success is_error=true api_error_status=invalid terminal_reason=none" ""
# Round 7: an API error turn is an assistant message with the SDK's typed `error` field (SDKAssistantMessageError,
# sdk.d.ts at the action's pinned 0.3.327) and no output; retries are system/api_retry messages. Shapes from those types.
apierr() { printf '{"type":"assistant","message":{"id":"m1","model":"<synthetic>","role":"assistant","content":[{"type":"text","text":%s}],"usage":{"input_tokens":0,"output_tokens":%s}},"error":"%s","parent_tool_use_id":null,"session_id":"s-1","uuid":"u1"}' "$1" "${3:-0}" "$2"; }
retry() { printf '{"type":"system","subtype":"api_retry","attempt":%s,"max_retries":10,"retry_delay_ms":500,"error_status":%s,"error":"%s","session_id":"s-1","uuid":"r%s"}' "$1" "$2" "$3" "$1"; }
noresp() { printf '{"type":"result","subtype":"success","is_error":true,"api_error_status":null,"num_turns":1,"modelUsage":{},"terminal_reason":"api_error","session_id":"s-1","result":%s}' "$1"; }
f=$(w apierr.json "[$init,$user,$(apierr '"API Error: 401 OAuth token has expired for someone@example.org'"'"'s Organization"' authentication_failed),$(noresp '"API Error: 401 OAuth token has expired"')]")
case_ "an API error turn is not a model answer: its category prints, markers read its text, the status reads none" "$f" \
  "review result: subtype=success is_error=true api_error_status=none terminal_reason=api_error|review api_errors: authentication_failed|review markers: expired" \
  "someone@example.org|organization|oauth|API Error"
f=$(w apierr-only.json "[$init,$(apierr '"Your credit balance is too low for someone@example.org"' billing_error),$(noresp '""')]")
case_ "a marker found only in the API error turn's text" "$f" "review api_errors: billing_error|review markers: credit" "someone@example.org|balance"
f=$(w retries.json "[$init,$(retry 1 529 overloaded),$(retry 2 529 overloaded),$(retry 3 null unknown),$(apierr '"Overloaded"' overloaded),$(noresp '"Overloaded"')]")
case_ "retries: their count, statuses and categories, no text" "$f" \
  "review api_retries: count=3 statuses=529,none errors=overloaded,unknown|review api_errors: overloaded|review markers: overloaded" ""
f=$(w apierr-and-model.json "[$init,$asst,$(apierr '"invalid token"' authentication_failed),$(noresp '"x"')]")
case_ "an API error turn beside a real model reply: markers withheld" "$f" "review api_errors: authentication_failed|review markers: withheld (a model answered, or its usage cannot be read)" "MODELSAID"
f=$(w apierr-tokens.json "[$init,$(apierr '"invalid MODELWORDS"' authentication_failed 7),$(noresp '"x"')]")
case_ "an error turn that has output tokens is a model answer: withheld" "$f" "review markers: withheld (a model answered, or its usage cannot be read)" "MODELWORDS"
f=$(w apierr-odd.json "[$init,$(apierr '"invalid"' 'hacked; rm -rf'),$(noresp '"x"')]")
case_ "an error value outside the SDK's list: invalid, and withheld" "$f" "review api_errors: invalid|review markers: withheld (a model answered, or its usage cannot be read)" "hacked|rm -rf"
f=$(w noerr.json "[$(res 401 '"invalid"')]")
case_ "no API error turn and no retry: both say so" "$f" "review api_errors: none|review api_retries: count=0" ""
f=$(w zero.json '[{"type":"result","subtype":"success","is_error":true,"api_error_status":0,"num_turns":1,"modelUsage":{},"result":"x"}]')
case_ "an explicit status 0 prints 0, apart from none" "$f" "review result: subtype=success is_error=true api_error_status=0 terminal_reason=none" ""
case_ "no execution file" "$T/none.json" "review result: no execution file" ""
case_ "no path, RUNNER_TEMP unset" "" "review result: no execution file" ""
mkdir -p "$T/rt"; cp "$T/auth.json" "$T/rt/claude-execution-output.json"
case_ "no path, RUNNER_TEMP set: the action's fixed file is read" "" "review result: subtype=success is_error=true api_error_status=401 terminal_reason=none|review markers: invalid" "" RUNNER_TEMP="$T/rt"
f=$(w bad.json 'not json {')
case_ "an unreadable file" "$f" "review result: the execution file is not readable JSON" ""
f=$(w nores.json "[$init,$user]")
case_ "no result message" "$f" "review result: no result message" ""
f=$(w obj.json '{"type":"result","result":"invalid"}')
case_ "a file that is not an array" "$f" "review result: no result message" ""
echo "review failure facts: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
