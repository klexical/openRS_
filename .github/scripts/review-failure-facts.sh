#!/usr/bin/env bash
# review-failure-facts.sh <execution file> — why a Claude review step failed, with no free text: an access error can
# name the organization, often after the owner's private e-mail, and no redaction list is safe enough for a public log.
# The execution file (claude-execution-output.json) holds every SDK message of the run, the prompt included. The script
# reads only the LAST result message (its fields below, and its result text and errors[] for the markers), each
# assistant message's error field, usage and, for an API error turn, its text (for the markers), and each api_retry
# message's status and category. It prints:
#   review result: subtype=… is_error=… api_error_status=… terminal_reason=…
#   review result: num_turns=… models_with_usage=…
#   review api_errors: <the SDK's categories of API error turns> | none
#   review api_retries: count=N [statuses=… errors=…]
#   review markers: <the names of fixed marker words found in the result text, errors[] or an API error turn's text> | none | withheld (…)
# Markers are printed only when no model answered: modelUsage an empty object, the result's usage absent or an object
# whose output_tokens is absent or a real 0, and no assistant message that is a model answer. An API error turn is not
# one: an assistant message whose error field is a string (any but max_output_tokens, which means the model wrote
# output) on a message object whose usage is absent or an object showing no output tokens; any other assistant message
# is one. An API error turn whose category is not on the SDK's list prints as invalid and also withholds the markers.
# No other character of the text is ever printed. subtype and terminal_reason print only as one of the SDK's own values
# (null → none, anything else → invalid); a number field as null → 0, an integer from 0 to 999999999 → that integer,
# anything else → invalid (output tokens that cannot be read also withhold the markers); api_error_status as null →
# none, 0 or 100-599 → itself, anything else → invalid. A number prints as its value, never its literal (1E2 → 100).
# Every printed line starts with "review " and holds printable ASCII only. Always exits 0.
set -uo pipefail
f=${1:-}
# the action's output may be unset after a failed composite step; the file's place is fixed (execution-file.ts)
[ -n "$f" ] || f=${RUNNER_TEMP:+$RUNNER_TEMP/claude-execution-output.json}
{
say() { printf 'review %s\n' "$1"; }
if [ -z "$f" ] || [ ! -f "$f" ]; then say "result: no execution file"
elif ! jq -e . "$f" >/dev/null 2>&1; then say "result: the execution file is not readable JSON"
else
  jq -r '
    def oneof($names): if . == null then "none" elif type == "string" and (. as $v | $names | index($v)) != null then . else "invalid" end;
    # a number prints as the value jq reads (+ 0 | floor), never as its literal: jq 1.7 keeps literals (0.0, -0, 1E2)
    def count: if . == null then "0" elif type == "number" and . == floor and . >= 0 and . < 1e9 then (. + 0 | floor | tostring) else "invalid" end;
    def status: if . == null then "none" elif type == "number" and . == floor and (. == 0 or (. >= 100 and . <= 599)) then (. + 0 | floor | tostring) else "invalid" end;
    # SDKAssistantMessageError (sdk.d.ts at the action'"'"'s pinned @anthropic-ai/claude-agent-sdk 0.3.327): the SDK'"'"'s own
    # category of a failed API call, a fixed list, never model text
    def errnames: ["authentication_failed","oauth_org_not_allowed","account_on_hold","verification_required","billing_error",
                   "rate_limit","overloaded","invalid_request","model_not_found","server_error","unknown","max_output_tokens",
                   "cloud_credential_error"];
    def errname: if type == "string" and (. as $e | errnames | index($e)) != null then . else "invalid" end;
    # SDKResultMessage'"'"'s subtype and TerminalReason, from the same sdk.d.ts
    def subtypes: ["success","error_during_execution","error_max_turns","error_max_budget_usd","error_max_structured_output_retries"];
    def reasons: ["blocking_limit","rapid_refill_breaker","prompt_too_long","image_error","model_error","api_error",
                  "malformed_tool_use_exhausted","aborted_streaming","aborted_tools","stop_hook_prevented","hook_stopped",
                  "tool_deferred","max_turns","background_requested","completed","budget_exhausted",
                  "structured_output_retry_exhausted","tool_deferred_unavailable","turn_setup_failed"];
    # output tokens of a usage value: absent → 0; an object → its output_tokens as a count; else → invalid
    def outtokens: if . == null then "0" elif type == "object" then (.output_tokens | count) else "invalid" end;
    # an API error turn: an assistant message whose error field is set (but not max_output_tokens), on a message object
    # whose usage shows no output tokens; anything else is a model answer
    def apierror: (.error | type) == "string" and .error != "max_output_tokens" and (.message | type) == "object"
                  and (.message.usage | outtokens) == "0";
    def markers: [["authentication","authenticat"],["invalid","invalid"],["expired","expired"],["permission","permission"],
                  ["access","access"],["model","model"],["rate limit","rate[ _-]?limit"],["usage limit","usage[ _-]?limit"],
                  ["credit","credit"],["overloaded","overloaded"]];
    (if type == "array" then . else [] end) as $all
    | ([$all[] | select(type == "object" and .type == "result")] | last) as $r
    | if $r == null then "review result: no result message" else
        ($r.modelUsage | if . == null then "none" elif type == "object" then (keys | length | tostring) else "invalid" end) as $models
        | ($r.usage | outtokens) as $out
        | [$all[] | select(type == "object" and .type == "assistant")] as $asst
        | ([$asst[] | select(apierror | not)] | length) as $assistant
        | ([$asst[] | select(apierror) | .error | errname] | unique) as $errs
        | [$all[] | select(type == "object" and .type == "system" and .subtype == "api_retry")] as $retries
        | ([($r.result | select(type == "string")), ($r.errors | if type == "array" then .[] | select(type == "string") else empty end),
            ($asst[] | select(apierror) | .message.content | if type == "array" then .[] | select(type == "object" and .type == "text") | .text | select(type == "string") else empty end)]
            | join(" ") | ascii_downcase) as $text
        | "review result: subtype=\($r.subtype | oneof(subtypes)) is_error=\($r.is_error | if . == null then "none" elif type == "boolean" then tostring else "invalid" end) api_error_status=\($r.api_error_status | status) terminal_reason=\($r.terminal_reason | oneof(reasons))",
          "review result: num_turns=\($r.num_turns | count) models_with_usage=\($models)",
          "review api_errors: " + (if ($errs | length) == 0 then "none" else ($errs | join(",")) end),
          "review api_retries: count=\($retries | length)" + (if ($retries | length) == 0 then "" else
            " statuses=" + ([$retries[] | .error_status | status] | unique | join(",")) + " errors=" + ([$retries[] | .error | errname] | unique | join(",")) end),
          (if $models == "0" and $out == "0" and $assistant == 0 and ($errs | index("invalid")) == null
           then "review markers: " + ([markers[] | select(.[1] as $rx | $text | test($rx)) | .[0]] | if length == 0 then "none" else join(",") end)
           else "review markers: withheld (a model answered, or its usage cannot be read)" end)
      end' "$f" 2>/dev/null || say "result: the execution file could not be read"
fi
} | LC_ALL=C tr -cd '\040-\176\n' | grep -E '^review ' || true
exit 0
