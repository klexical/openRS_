#!/usr/bin/env bash
# review-failure-facts.sh <execution file> — why a Claude review step failed, with no free text (fix round 5; QA's
# FINAL-failure-facts C2, the integrator's call: an access error can name the organization, often after the owner's
# private e-mail, and no redaction list is safe enough for a public log). Reads only the LAST result message of the
# action's execution file (claude-execution-output.json: every SDK message of the run, the prompt included, never
# printed) and prints:
#   review result: subtype=… is_error=… api_error_status=… terminal_reason=…
#   review result: num_turns=… models_with_usage=…
#   review api_errors: <the SDK's categories of failed API turns> | none
#   review api_retries: count=N [statuses=… errors=…]
#   review markers: <the names of fixed marker words found in the result text, errors[] or an API error turn's text> | none | withheld (…)
# Markers are printed only when no model answered: modelUsage an empty object, usage.output_tokens a real 0 (or absent),
# and no assistant message that is a model answer (an API error turn, with the SDK's typed error field and no output
# tokens, is not one; round 7). No other character of the text is ever printed. A word field prints
# only as a lower-case word; a number field as null → 0, an integer → itself, anything else → invalid (which also
# withholds); api_error_status as null → none, 0 or 100-599 → itself, anything else → invalid. Every printed line
# starts with "review " and holds printable ASCII only. Always exits 0.
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
    def word: if . == null then "none" elif type == "string" and test("^[a-z_]{1,60}$") then . else "invalid" end;
    def count: if . == null then "0" elif type == "number" and . == floor and . >= 0 and . < 1e9 then tostring else "invalid" end;
    def status: if . == null then "none" elif type == "number" and . == floor and (. == 0 or (. >= 100 and . <= 599)) then tostring else "invalid" end;
    # SDKAssistantMessageError (sdk.d.ts at the action'"'"'s pinned @anthropic-ai/claude-agent-sdk 0.3.327): the SDK'"'"'s own
    # category of a failed API call, a fixed list, never model text
    def errnames: ["authentication_failed","oauth_org_not_allowed","account_on_hold","verification_required","billing_error",
                   "rate_limit","overloaded","invalid_request","model_not_found","server_error","unknown","max_output_tokens",
                   "cloud_credential_error"];
    def errname: if type == "string" and (. as $e | errnames | index($e)) != null then . else "invalid" end;
    # an API error turn: an assistant message whose error field is set, with no output tokens; anything else is a model answer
    def apierror: (.error | type) == "string" and ((.message.usage.output_tokens? // 0) | type == "number" and . == 0);
    def markers: [["authentication","authenticat"],["invalid","invalid"],["expired","expired"],["permission","permission"],
                  ["access","access"],["model","model"],["rate limit","rate[ _-]?limit"],["usage limit","usage[ _-]?limit"],
                  ["credit","credit"],["overloaded","overloaded"]];
    (if type == "array" then . else [] end) as $all
    | ([$all[] | select(type == "object" and .type == "result")] | last) as $r
    | if $r == null then "review result: no result message" else
        ($r.modelUsage | if . == null then "none" elif type == "object" then (keys | length | tostring) else "invalid" end) as $models
        | ($r.usage.output_tokens? // null | count) as $out
        | [$all[] | select(type == "object" and .type == "assistant")] as $asst
        | ([$asst[] | select(apierror | not)] | length) as $assistant
        | ([$asst[] | select(apierror) | .error | errname] | unique) as $errs
        | [$all[] | select(type == "object" and .type == "system" and .subtype == "api_retry")] as $retries
        | ([($r.result | select(type == "string")), ($r.errors | if type == "array" then .[] | select(type == "string") else empty end),
            ($asst[] | select(apierror) | .message.content | if type == "array" then .[] | select(type == "object" and .type == "text") | .text | select(type == "string") else empty end)]
            | join(" ") | ascii_downcase) as $text
        | "review result: subtype=\($r.subtype | word) is_error=\($r.is_error | if . == null then "none" elif type == "boolean" then tostring else "invalid" end) api_error_status=\($r.api_error_status | status) terminal_reason=\($r.terminal_reason | word)",
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
