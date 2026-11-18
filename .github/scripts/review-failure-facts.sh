#!/usr/bin/env bash
# review-failure-facts.sh <execution file> — why a Claude review step failed, without its content. Reads only the LAST
# result message of the action's execution file (claude-execution-output.json: every SDK message of the run, the prompt
# included, which is never printed) and prints its subtype, is_error, api_error_status, terminal_reason, turns, cost and
# how many models used tokens. The result's message text (`result`, or `errors` on an error subtype) is printed only
# when no model produced output (modelUsage empty), because then it is the API's or the SDK's text, not the model's: text
# in a diff could steer a model's words, never an API refusal. It is flattened to one line of printable characters,
# token-shaped strings and long hex / base64 runs are redacted, and it is capped at 300 characters. Fields of the wrong
# type print as `invalid`, never as their content. Always exits 0: it explains a failure, it is not a check.
set -uo pipefail
f=${1:-}
say() { printf 'review %s\n' "$1"; }
[ -n "$f" ] && [ -f "$f" ] || { say "result: no execution file"; exit 0; }
jq -e . "$f" >/dev/null 2>&1 || { say "result: the execution file is not readable JSON"; exit 0; }
r=$(jq -c 'if type == "array" then [.[] | select(type == "object" and .type == "result")] | last // empty else empty end' "$f" 2>/dev/null)
[ -n "$r" ] || { say "result: no result message"; exit 0; }
q() { jq -r "$1" <<<"$r" 2>/dev/null || echo invalid; }
word='if . == null then "none" elif type == "string" and test("^[a-z_]{1,60}$") then . else "invalid" end'
subtype=$(q ".subtype | $word")
is_error=$(q '.is_error | if . == null then "none" elif type == "boolean" then tostring else "invalid" end')
status=$(q '.api_error_status | if . == null then "none" elif type == "number" and . == floor and . >= 100 and . <= 599 then tostring else "invalid" end')
treason=$(q ".terminal_reason | $word")
turns=$(q '.num_turns | if . == null then "none" elif type == "number" and . == floor and . >= 0 then tostring else "invalid" end')
cost=$(q '.total_cost_usd | if . == null then "none" elif type == "number" and . >= 0 then tostring else "invalid" end')
models=$(q '.modelUsage | if . == null then "none" elif type == "object" then (keys | length | tostring) else "invalid" end')
say "result: subtype=$subtype is_error=$is_error api_error_status=$status terminal_reason=$treason"
say "result: num_turns=$turns total_cost_usd=$cost models_with_usage=$models"
if [ "$(q 'has("startup_failure_reason")')" = true ]; then
  say "startup_failure_reason=$(q ".startup_failure_reason | $word")"
fi
if [ "$models" != 0 ]; then
  say "message: withheld (a model produced output, so the text could be the model's)"
  exit 0
fi
text=$(q 'if (.errors | type) == "array" and (.errors | length) > 0 then [.errors[] | select(type == "string")] | join("; ")
          elif (.result | type) == "string" then .result else "" end')
text=$(printf '%s' "$text" | LC_ALL=C tr '\000-\037\177' ' ' \
  | LC_ALL=C sed -E 's/sk-ant-[A-Za-z0-9_-]+/<redacted>/g; s/gh[pousr]_[A-Za-z0-9]{20,}/<redacted>/g;
                     s/[0-9A-Fa-f]{32,}/<redacted>/g; s/[A-Za-z0-9+\/=_-]{40,}/<redacted>/g' | tr -s ' ' | sed -E 's/^ +| +$//g')
text=$(printf '%s' "$text" | jq -Rr 'if length > 300 then .[0:300] + "…" else . end')
say "message (no model output, so it is the API's or the SDK's text): ${text:-(empty)}"
exit 0
