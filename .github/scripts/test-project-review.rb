#!/usr/bin/env ruby
# test-project-review.rb [<workflows dir>] — pins project-review.yml: by hand on main only, the owner's app key in its
# last step only, no checkout, no `uses:`, no GITHUB_TOKEN permission; and runs each step's own text against a fake
# GitHub (a stand-in curl on PATH) with a real RSA key: the inputs' patterns, the head and base checks, a REAL-GAP that
# stands on its head, the JWT and installation token each used where they belong, and no token or key in the output.
# Exit 0 when every check passes.
require "yaml"
require "json"
require "open3"
require "tmpdir"
require "base64"
require "openssl"
dir = ARGV[0] || File.join(__dir__, "..", "workflows")
fails = 0
check = lambda do |ok, what|
  puts "#{ok ? "PASS" : "FAIL"} #{what}"
  fails += 1 unless ok
end
path = File.join(dir, "project-review.yml")
text = File.exist?(path) ? File.read(path) : ""
wf = File.exist?(path) ? YAML.safe_load(text, aliases: false) : {}
on = wf["on"] || wf[true] || {}                   # YAML 1.1 reads a bare `on:` key as true
inputs = on.dig("workflow_dispatch", "inputs") || {}
check.(on.is_a?(Hash) && on.keys == ["workflow_dispatch"], "by hand only (workflow_dispatch)")
check.(inputs.keys == %w[pr sha verdict report] && inputs.values.all? { |i| i["required"] == true } &&
       inputs.dig("pr", "type") == "number" && inputs.dig("verdict", "type") == "choice" &&
       inputs.dig("verdict", "options") == %w[clear real-gap withdrawn-gap],
       "inputs: pr (a number), sha, verdict (clear, real-gap, withdrawn-gap), report; all required")
check.(wf["run-name"] == "project-review: pull request ${{ inputs.pr }}, ${{ inputs.verdict }}",
       "the run's name carries the number and the verdict only (no free text in the public list of runs)")
check.(wf["permissions"] == {} && (wf["jobs"] || {}).keys == ["project-review"], "no workflow-level permission, one job")
job = (wf["jobs"] || {})["project-review"] || {}
steps = job["steps"] || []
check.(job["permissions"] == {} && job["environment"] == "project-review" && !job.key?("if") &&
       steps.none? { |s| s.key?("if") || s.key?("continue-on-error") || s.key?("uses") } && !text.match?(/^\s*-?\s*uses:/),
       "the job: no token permission, environment project-review, no condition, no `uses:` (no checkout)")
check.(wf.dig("concurrency", "cancel-in-progress") == false && wf.dig("concurrency", "group") == "project-review-${{ inputs.sha }}",
       "one run at a time per head (the status belongs to the commit); a running one is never cancelled (a waiting one can be replaced: the reviewer waits for its run, then reads back)")
check.(steps.length == 3 && steps[0]["env"] == { "REF" => "${{ github.ref }}" } &&
       steps[0]["run"].to_s.strip == '[ "$REF" = refs/heads/main ] || { echo "::error::project-review runs on main only: fails closed"; exit 1; }',
       "the first step fails any ref but main")
last = steps.last || {}
check.(steps.select { |s| s.to_s.include?("secrets.") || s.to_s.include?("vars.") } == [last] && text.scan("secrets.").size == 1 &&
       last.dig("env", "APP_KEY") == "${{ secrets.PROJECT_REVIEW_APP_KEY }}" && last.dig("env", "APP_ID") == "${{ vars.PROJECT_REVIEW_APP_ID }}",
       "the app's key and id reach the last step only")
check.(!steps.empty? && steps.none? { |s| s["run"].to_s.include?("${{") }, "no ${{ }} in any run (values come through env)")
lrun = last["run"].to_s
check.(lrun.include?("curl -fsS --proto '=https' -X") && !lrun.match?(/curl[^\n]*(\s-L\b|--location)/) && lrun.include?("umask 077") &&
       lrun.index("unset APP_KEY").to_i > lrun.index('printf \'%s\\n\' "$APP_KEY" > "$key"').to_i && lrun.scan("$APP_KEY").size == 1,
       "curl: https only, no redirects followed; the key file under umask 077; the key leaves the environment once written")
check.(text.include?("waits for its own run") && text.include?("to complete, and only then reads the head's statuses back") &&
       text.include?("that is the answer, final for that head; do not dispatch again"),
       "the header tells the reviewer to wait for its run, then read back, and to stop on a refusal")
check.(text.include?("a mismatch counts as no status"), "the header says a status that names another pull request or base counts as none")
check.(text.scan("${{ inputs.").size == text.scan(/^\s+(PR|SHA|VERDICT|REPORT): \$\{\{ inputs\.\w+ \}\}$/).size + 3,
       "inputs appear only in env, the run's name and the concurrency group")

# --- the steps' own text, against a fake GitHub -------------------------------------------------------------------------
FAKE_CURL = <<~'SH'
  #!/usr/bin/env bash
  # a stand-in curl: answers from $FAKE/<name>.json, logs each call to $FAKE/calls; a missing answer fails like curl -f
  method=GET auth="" data="" url=""
  while [ $# -gt 0 ]; do
    case "$1" in
      -X) method=$2; shift 2 ;;
      -H) case "$2" in "Authorization: Bearer "*) auth=${2#Authorization: Bearer } ;; esac; shift 2 ;;
      --data) data=$2; shift 2 ;;
      --proto) shift 2 ;;
      -*) shift ;;
      *) url=$1; shift ;;
    esac
  done
  path=${url#https://api.example.invalid/}
  printf '%s\t%s\t%s\t%s\n' "$method" "$path" "$auth" "$data" >> "$FAKE/calls"
  case "$method $path" in
    "GET app") name=app ;;
    "GET repos/klexical/openRS_/installation") name=installation ;;
    "POST app/installations/77/access_tokens") name=token ;;
    "GET repos/klexical/openRS_/pulls/"*) name=pr ;;
    "GET repos/klexical/openRS_/commits/"*"/statuses?per_page=100&page=1") name=statuses ;;
    "GET repos/klexical/openRS_/commits/"*"/statuses?per_page=100&page="*) name=statuses-p${path##*page=}
      [ -f "$FAKE/$name.json" ] || { echo '[]'; exit 0; } ;;
    "POST repos/klexical/openRS_/statuses/"*) name=posted ;;
    *) name=unknown ;;
  esac
  [ -f "$FAKE/$name.json" ] || { echo "curl: (22) The requested URL returned error: 404" >&2; exit 22; }
  cat "$FAKE/$name.json"
SH
SHA = "0123456789abcdef0123456789abcdef01234567"
TOKEN = "ghs_installation_token_for_the_test"
KEY = OpenSSL::PKey::RSA.new(2048)
GOOD = { "PR" => "473", "SHA" => SHA, "VERDICT" => "clear", "REPORT" => "review-report-473-opus.md", "GITHUB_RUN_ATTEMPT" => "1",
         "GITHUB_RUN_ID" => "4242", "GITHUB_SERVER_URL" => "https://github.com" }
RUN_URL = "https://github.com/klexical/openRS_/actions/runs/4242/attempts/1"
WHERE = "(PR 473 to main)"

run_step = lambda do |step, env, answers = {}|
  Dir.mktmpdir do |d|
    bin = File.join(d, "bin"); fake = File.join(d, "fake"); tmp = File.join(d, "tmp")
    [bin, fake, tmp].each { |p| Dir.mkdir(p) }
    File.write(File.join(bin, "curl"), FAKE_CURL); File.chmod(0o755, File.join(bin, "curl"))
    { "app" => { "slug" => "openrs-project-review" }, "installation" => { "id" => 77 }, "token" => { "token" => TOKEN },
      "pr" => { "state" => "open", "base" => { "ref" => "main" }, "head" => { "sha" => SHA, "repo" => { "full_name" => "klexical/openRS_" } } },
      "statuses" => [],
      "posted" => {} }.merge(answers).each { |k, v| File.write(File.join(fake, "#{k}.json"), JSON.generate(v)) unless v.nil? }
    base = { "PATH" => "#{bin}:#{ENV["PATH"]}", "FAKE" => fake, "RUNNER_TEMP" => tmp, "REPO" => "klexical/openRS_",
             "API" => "https://api.example.invalid", "APP_ID" => "12345", "APP_KEY" => KEY.to_pem }
    out, st = Open3.capture2e(base.merge(env), "bash", "-e", "-c", step["run"].to_s, chdir: d)
    calls = File.exist?(File.join(fake, "calls")) ? File.read(File.join(fake, "calls")).lines.map { |l| l.chomp.split("\t", 4) } : []
    [st.success?, out, calls, Dir.children(tmp)]
  end
end
posts = ->(calls) { calls.select { |c| c[0] == "POST" && c[1].start_with?("repos/klexical/openRS_/statuses/") } }

ok, = run_step.(steps[0] || {}, { "REF" => "refs/heads/main" })
check.(ok, "main only: refs/heads/main passes")
ok, = run_step.(steps[0] || {}, { "REF" => "refs/heads/feature/x" })
check.(!ok, "main only: another branch fails")

ok, = run_step.(steps[1] || {}, GOOD)
check.(ok, "inputs: a number, a full SHA, a verdict and a report name pass")
{ "pr 0" => { "PR" => "0" }, "pr with a letter" => { "PR" => "47a" }, "an empty pr" => { "PR" => "" },
  "an upper-case SHA" => { "SHA" => SHA.upcase }, "a short SHA" => { "SHA" => SHA[0, 39] },
  "a report with a space" => { "REPORT" => "my report.md" }, "a report with a slash" => { "REPORT" => "../x.md" },
  "an upper-case report" => { "REPORT" => "Report.md" }, "a report of 61 characters" => { "REPORT" => "a" * 61 },
  "another verdict" => { "VERDICT" => "approve" }, "a re-run (attempt 2)" => { "GITHUB_RUN_ATTEMPT" => "2" },
  "no run attempt" => { "GITHUB_RUN_ATTEMPT" => "" } }.each do |what, bad|
  ok, = run_step.(steps[1] || {}, GOOD.merge(bad))
  check.(!ok, "inputs: #{what} fails")
end

post = last
ok, out, calls, left = run_step.(post, GOOD)
p1 = posts.(calls)
body = p1.first ? JSON.parse(p1.first[3]) : {}
check.(ok && p1.size == 1 && p1.first[1] == "repos/klexical/openRS_/statuses/#{SHA}" &&
       body == { "state" => "success", "context" => "project-review", "description" => "Review clear: review-report-473-opus.md #{WHERE}",
                 "target_url" => RUN_URL },
       "clear at the reviewed head: success, context project-review, the report's name, the pull request and base, the run's attempt")
jwt_calls = calls.select { |c| ["GET app", "GET repos/klexical/openRS_/installation", "POST app/installations/77/access_tokens"].include?("#{c[0]} #{c[1]}") }
jwt = jwt_calls.first&.at(2).to_s
h, pl, sg = jwt.split(".")
b64 = ->(s) { Base64.urlsafe_decode64(s + "=" * ((4 - s.length % 4) % 4)) }
claims = pl ? JSON.parse(b64.(pl)) : {}
check.(jwt_calls.size == 3 && jwt_calls.all? { |c| c[2] == jwt } && sg &&
       KEY.public_key.verify("SHA256", b64.(sg), "#{h}.#{pl}") && JSON.parse(b64.(h)) == { "alg" => "RS256", "typ" => "JWT" } &&
       claims["iss"] == "12345" && claims["exp"] - claims["iat"] <= 600,
       "the app's JWT: RS256 signed with the key, issued by the app id, ten minutes at most")
tok = calls.find { |c| c[1] == "app/installations/77/access_tokens" }
check.(tok && JSON.parse(tok[3]) == { "repositories" => ["openRS_"], "permissions" => { "statuses" => "write", "pull_requests" => "read" } },
       "the installation token: this repository only, statuses write and pull requests read")
check.(calls.reject { |c| jwt_calls.include?(c) }.all? { |c| c[2] == TOKEN }, "every other call uses the installation token, never the JWT")
shown = out.lines.reject { |l| l.start_with?("::add-mask::") }.join
check.(!shown.include?(TOKEN) && !shown.include?(jwt) && !out.include?("PRIVATE KEY") && left.empty?,
       "no token, JWT or key in the output, and the key file is gone")
check.(out.lines.count { |l| l.start_with?("::add-mask::") } == 2, "both the JWT and the installation token are masked")

ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "real-gap"))
check.(ok && posts.(calls).size == 1 && JSON.parse(posts.(calls).first[3])["state"] == "failure" &&
       JSON.parse(posts.(calls).first[3])["description"] == "Review found a REAL-GAP: review-report-473-opus.md #{WHERE}",
       "real-gap: failure, with the report's name")
BOT = { "login" => "openrs-project-review[bot]" }
mine = { "context" => "project-review", "state" => "failure", "creator" => BOT,
         "description" => "Review found a REAL-GAP: review-report-473-opus.md #{WHERE}" }
cleared = { "context" => "project-review", "state" => "success", "creator" => BOT,
            "description" => "Review clear (REAL-GAP withdrawn): review-report-473-opus.md #{WHERE}" }
# two reviewers' REAL-GAPs on one head (the model split puts two reviewers on many pull requests), newest first
gap = ->(r) { mine.merge("description" => "Review found a REAL-GAP: #{r} #{WHERE}") }
part = ->(r) { mine.merge("description" => "Review REAL-GAP withdrawn: #{r} #{WHERE}") }
A = "review-a-fable.md"
B = "review-b-opus.md"
other = { "context" => "Build & Test", "state" => "success", "creator" => { "login" => "github-actions[bot]" } }
ok, _, calls = run_step.(post, GOOD, { "statuses" => [mine] })
check.(!ok && posts.(calls).empty?, "clear on a head with this app's REAL-GAP: refused, nothing posted")
ok, _, calls = run_step.(post, GOOD, { "statuses" => [mine.merge("creator" => { "login" => "github-actions[bot]" })] })
check.(ok && posts.(calls).size == 1, "a failure posted by another source does not count as this app's REAL-GAP")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap"), { "statuses" => [mine] })
check.(ok && posts.(calls).size == 1 && JSON.parse(posts.(calls).first[3]) ==
       { "state" => "success", "context" => "project-review", "description" => "Review clear (REAL-GAP withdrawn): review-report-473-opus.md #{WHERE}",
         "target_url" => RUN_URL },
       "withdrawn-gap on a head with this app's REAL-GAP: success, saying so")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap"))
check.(!ok && posts.(calls).empty?, "withdrawn-gap with no REAL-GAP to withdraw: refused")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap", "REPORT" => "review-report-473-fable.md"), { "statuses" => [mine] })
check.(!ok && posts.(calls).empty?, "withdrawn-gap naming another report than the standing REAL-GAP's: refused")
ok, _, calls = run_step.(post, GOOD, { "statuses" => [cleared, mine] })
check.(ok && posts.(calls).size == 1, "clear after a withdrawn REAL-GAP (the app's newest status is the withdrawal): posted")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap"), { "statuses" => [cleared, mine] })
check.(!ok && posts.(calls).empty?, "withdrawn-gap after the REAL-GAP was withdrawn: refused (nothing stands)")
posted = ->(calls) { posts.(calls).first ? JSON.parse(posts.(calls).first[3]).values_at("state", "description") : [] }
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap", "REPORT" => B), { "statuses" => [gap.(B), gap.(A)] })
check.(ok && posted.(calls) == ["failure", "Review REAL-GAP withdrawn: #{B} #{WHERE}"],
       "two REAL-GAPs (A, then B), B withdrawn: a failure that records the withdrawal (A still stands)")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap", "REPORT" => A), { "statuses" => [gap.(B), gap.(A)] })
check.(ok && posted.(calls) == ["failure", "Review REAL-GAP withdrawn: #{A} #{WHERE}"],
       "two REAL-GAPs (A, then B), A withdrawn: the same, whichever was posted first (B still stands)")
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [part.(B), gap.(B), gap.(A)] })
check.(!ok && posts.(calls).empty?, "two REAL-GAPs, B withdrawn, then a third report's clear: refused (A stands)")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap", "REPORT" => A), { "statuses" => [part.(B), gap.(B), gap.(A)] })
check.(ok && posted.(calls) == ["success", "Review clear (REAL-GAP withdrawn): #{A} #{WHERE}"],
       "two REAL-GAPs, B withdrawn, then A withdrawn: success (none is left)")
ok, _, calls = run_step.(post, GOOD.merge("VERDICT" => "withdrawn-gap", "REPORT" => B), { "statuses" => [part.(B), gap.(B), gap.(A)] })
check.(!ok && posts.(calls).empty?, "a report withdrawn twice: refused (its REAL-GAP no longer stands)")
both_gone = [cleared.merge("description" => "Review clear (REAL-GAP withdrawn): #{A} #{WHERE}"), part.(B), gap.(B), gap.(A)]
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => both_gone })
check.(ok && posts.(calls).size == 1, "both REAL-GAPs withdrawn, then a clear: posted")
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [gap.(A), cleared.merge("description" => "Review clear (REAL-GAP withdrawn): #{A} #{WHERE}"), gap.(A)] })
check.(!ok && posts.(calls).empty?, "a REAL-GAP posted again after its withdrawal stands again: a clear is refused")
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [mine.merge("state" => "success", "description" => "Review clear: #{B} #{WHERE}"), gap.(A)] })
check.(!ok && posts.(calls).empty?, "a REAL-GAP under a newer clear of another report's still stands: a clear is refused")
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [other] * 99 + [gap.(A)], "statuses-p2" => [] })
check.(!ok && posts.(calls).empty?, "a REAL-GAP among 99 other statuses on a short first page: refused")
ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [gap.(A)] * 100, "statuses-p2" => [gap.(A), cleared.merge("state" => "success", "description" => "Review clear (REAL-GAP withdrawn): #{A} #{WHERE}")] })
check.(!ok && posts.(calls).empty?, "pages read oldest last: a REAL-GAP newer than its withdrawal (on page 1) stands")
{ "a description the run cannot read" => mine.merge("description" => "something else"),
  "a REAL-GAP posted as success" => mine.merge("state" => "success"),
  "a clear posted as failure" => mine.merge("description" => "Review clear: #{A} #{WHERE}"),
  "a pending status of the app's" => mine.merge("state" => "pending") }.each do |what, odd|
  ok, _, calls = run_step.(post, GOOD.merge("REPORT" => "review-c.md"), { "statuses" => [odd] })
  check.(!ok && posts.(calls).empty?, "#{what}: fails closed, nothing posted")
end
ok, _, calls = run_step.(post, GOOD, { "statuses" => [other] * 100, "statuses-p2" => [mine] })
check.(!ok && posts.(calls).empty?, "a REAL-GAP on the second page of statuses still stands")
ok, _, calls = run_step.(post, GOOD, { "statuses" => [other] * 100, **(2..10).to_h { |i| ["statuses-p#{i}", [other] * 100] } })
check.(!ok && posts.(calls).empty?, "more statuses than ten pages: fails closed")
pr = ->(state, base, head, from = "klexical/openRS_") { { "pr" => { "state" => state, "base" => { "ref" => base }, "head" => { "sha" => head, "repo" => { "full_name" => from } } } } }
{ "the head has moved on" => pr.("open", "main", SHA.reverse), "the pull request is closed" => pr.("closed", "main", SHA),
  "the base is another branch" => pr.("open", "feature/x", SHA), "the pull request cannot be read" => { "pr" => nil },
  "the head is a fork's" => pr.("open", "main", SHA, "someone/openRS_"),
  "the app is not installed" => { "installation" => nil }, "the app gets no token" => { "token" => nil },
  "the statuses cannot be read" => { "statuses" => nil }, "the status cannot be posted" => { "posted" => nil } }.each do |what, answers|
  ok, _, calls = run_step.(post, GOOD, answers)
  check.(!ok && (answers.key?("posted") || posts.(calls).empty?), "#{what}: fails#{answers.key?("posted") ? "" : ", nothing posted"}")
end
ok, _, calls = run_step.(post, GOOD, pr.("open", "staging", SHA))
check.(ok && posts.(calls).size == 1, "a pull request to staging is posted")
ok, _, calls = run_step.(post, GOOD, pr.("open", "scratch/project-review-proof", SHA))
check.(ok && posts.(calls).size == 1, "a pull request to the owner's proof base is posted")
long = GOOD.merge("PR" => "999999", "REPORT" => "r" * 60, "VERDICT" => "withdrawn-gap")
ok, _, calls = run_step.(post, long, pr.("open", "scratch/project-review-proof", SHA).merge(
  "statuses" => [mine.merge("description" => "Review found a REAL-GAP: #{"r" * 60} (PR 999999 to scratch/project-review-proof)")]))
d = posts.(calls).first ? JSON.parse(posts.(calls).first[3])["description"] : ""
check.(ok && d.length <= 140 && d.end_with?("(PR 999999 to scratch/project-review-proof)"), "the longest description fits GitHub's 140 characters (#{d.length})")
ok, _, calls = run_step.(post, GOOD.merge("APP_ID" => ""))
check.(!ok && calls.empty?, "no app id: fails before any call")
ok, _, calls = run_step.(post, GOOD.merge("APP_KEY" => ""))
check.(!ok && calls.empty?, "no key: fails before any call")
ok, _, calls, left = run_step.(post, GOOD.merge("APP_KEY" => "not a key"))
check.(!ok && calls.empty? && left.empty?, "a key that cannot sign: fails before any call, and the key file is gone")

puts "project-review checks: #{fails.zero? ? "all passed" : "#{fails} failed"}"
exit(fails.zero? ? 0 : 1)
