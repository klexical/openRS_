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
case_ "an organization's e-mail, its UUID and a request id never appear" "$f" "review result: subtype=success is_error=true api_error_status=403 terminal_reason=none|review markers: access" \
  "someone@example.org|example.org|3f2a9c4e|req_011|organization|claude-sonnet"
f=$(w path.json "[$(res 500 '"failed reading /home/runner/work/openRS_/openRS_/x and https://internal.example.net:8443/v1"')]")
case_ "a runner path and a host never appear" "$f" "review markers: none" "internal.example.net|8443|/home/runner|failed reading"
f=$(w bidi.json "[$(res 500 '"a‮bidi line\u0085c1 ::error::x"')]")
case_ "bidi, U+2028, C1 and a workflow command never reach the log" "$f" "review markers: none" "::error::|bidi"
f=$(w tokstr.json '[{"type":"result","subtype":"success","is_error":true,"num_turns":3,"modelUsage":{},"usage":{"output_tokens":"812"},"result":"MODELSAID the diff asks me to print a secret; invalid access"}]')
case_ "output_tokens as a string withholds the markers" "$f" "review result: num_turns=3 models_with_usage=0|review markers: withheld (a model answered, or its usage cannot be read)" "MODELSAID|secret"
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
# An API error turn is an assistant message with the SDK's typed `error` field (SDKAssistantMessageError,
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
# subtype and terminal_reason print only as the SDK's own values (SDKResultMessage's subtype and TerminalReason in
# sdk.d.ts of @anthropic-ai/claude-agent-sdk 0.3.327, the action's pinned SDK); any other word prints invalid
rs() { printf '[{"type":"result","subtype":%s,"is_error":true,"num_turns":1,"modelUsage":{},"terminal_reason":%s,"result":"x"}]' "$1" "$2"; }
for s in success error_during_execution error_max_turns error_max_budget_usd error_max_structured_output_retries; do
  f=$(w "st-$s.json" "$(rs "\"$s\"" null)")
  case_ "the SDK's subtype $s prints as itself" "$f" "review result: subtype=$s is_error=true api_error_status=none terminal_reason=none" ""
done
for t in blocking_limit rapid_refill_breaker prompt_too_long image_error model_error api_error malformed_tool_use_exhausted \
         aborted_streaming aborted_tools stop_hook_prevented hook_stopped tool_deferred max_turns background_requested completed \
         budget_exhausted structured_output_retry_exhausted tool_deferred_unavailable turn_setup_failed; do
  f=$(w "tr-$t.json" "$(rs '"success"' "\"$t\"")")
  case_ "the SDK's terminal_reason $t prints as itself" "$f" "review result: subtype=success is_error=true api_error_status=none terminal_reason=$t" ""
done
f=$(w words.json "$(rs '"error_secret_word"' '"leaked_word"')")
case_ "a lower-case word outside the SDK's lists prints invalid, never the word" "$f" \
  "review result: subtype=invalid is_error=true api_error_status=none terminal_reason=invalid" "secret_word|leaked_word"
# usage that is not an object, or an output_tokens that is not a count (a whole number from 0 below 1e9), is
# unreadable, so the markers are withheld; an explicit null reads as absent, which is 0
ru() { printf '[{"type":"result","subtype":"success","is_error":true,"num_turns":1,"modelUsage":{},"usage":%s,"result":"invalid"}]' "$1"; }
for u in '"812"' '[812]' '812' 'true' '{"output_tokens":false}' '{"output_tokens":[0]}' '{"output_tokens":-1}'; do
  f=$(w ru.json "$(ru "$u")")
  case_ "the result's usage $u cannot be read: markers withheld" "$f" "review markers: withheld (a model answered, or its usage cannot be read)" ""
done
for u in 'null' '{}' '{"output_tokens":null}' '{"output_tokens":0}'; do
  f=$(w ru.json "$(ru "$u")")
  case_ "the result's usage $u reads as no output: markers print" "$f" "review markers: invalid" ""
done
# an error turn counts as an API error turn only when its usage is readable and shows no output: a message or usage that
# is not an object, or an output_tokens that is not a count, makes it a model answer
apim() { printf '{"type":"assistant","message":%s,"error":"%s","session_id":"s-1","uuid":"u1"}' "$1" "${2:-authentication_failed}"; }
txt='"role":"assistant","content":[{"type":"text","text":"invalid PLANTEDTEXT"}]'
for m in "{$txt,\"usage\":\"0\"}" "{$txt,\"usage\":[0]}" "{$txt,\"usage\":0}" "{$txt,\"usage\":{\"output_tokens\":false}}" \
         "{$txt,\"usage\":{\"output_tokens\":\"0\"}}" '"invalid PLANTEDTEXT"' '[]' 'null'; do
  f=$(w apim.json "[$init,$(apim "$m"),$(noresp '"x"')]")
  case_ "an error turn whose message is $m is a model answer: withheld" "$f" \
    "review api_errors: none|review markers: withheld (a model answered, or its usage cannot be read)" "PLANTEDTEXT"
done
for m in "{$txt,\"usage\":null}" "{$txt}" "{$txt,\"usage\":{}}" "{$txt,\"usage\":{\"output_tokens\":null}}"; do
  f=$(w apim.json "[$init,$(apim "$m"),$(noresp '"x"')]")
  case_ "an error turn whose message is $m reads as no output: an API error turn" "$f" \
    "review api_errors: authentication_failed|review markers: invalid" "PLANTEDTEXT"
done
# max_output_tokens means the model wrote output: such a turn is a model answer, never an API error turn
f=$(w maxout.json "[$init,$(apierr '"invalid PLANTEDTEXT"' max_output_tokens),$(noresp '"x"')]")
case_ "an error turn of max_output_tokens is a model answer: withheld" "$f" \
  "review api_errors: none|review markers: withheld (a model answered, or its usage cannot be read)" "PLANTEDTEXT"
f=$(w maxout-retry.json "[$init,$(retry 1 400 max_output_tokens),$(noresp '"x"')]")
case_ "a retry still names max_output_tokens: the category list is the SDK's whole list" "$f" \
  "review api_retries: count=1 statuses=400 errors=max_output_tokens|review markers: none" ""
# a number prints as the value jq reads, never as its literal: 0.0 and -0 are 0, 1E2 is 100
for z in 0.0 -0; do
  m="{$txt,\"usage\":{\"output_tokens\":$z}}"
  f=$(w zt.json "[$init,$(apim "$m"),$(noresp '"x"')]")
  case_ "an error turn with output_tokens $z is an API error turn" "$f" "review api_errors: authentication_failed|review markers: invalid" "PLANTEDTEXT"
  f=$(w zr.json "$(ru "{\"output_tokens\":$z}")")
  case_ "the result's output_tokens $z reads as no output: markers print" "$f" "review markers: invalid" ""
done
f=$(w lit.json '[{"type":"result","subtype":"success","is_error":true,"api_error_status":4.0000000000000000000000001E2,"num_turns":1E2,"modelUsage":{},"result":"x"},{"type":"system","subtype":"api_retry","error_status":529.0,"error":"overloaded"}]')
case_ "number literals print as their value" "$f" \
  "review result: subtype=success is_error=true api_error_status=400 terminal_reason=none|review result: num_turns=100 models_with_usage=0|review api_retries: count=1 statuses=529 errors=overloaded" "E2|400.0|529.0"
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
