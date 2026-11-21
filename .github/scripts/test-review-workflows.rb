#!/usr/bin/env ruby
# test-review-workflows.rb [<workflows dir>] — pins the shape of claude-review.yml and commit-identity.yml:
# pull_request only, never pull_request_target; every job skips a fork's branch; the least permissions; the
# subscription token, never an API key; the reviewer holds no write tool. Also privacy-scan.yml, the one
# pull_request_target workflow: main's code only, no checkout, the list from the environment in one step only.
# Exit 0 when every check passes.
require "yaml"
dir = ARGV[0] || File.join(__dir__, "..", "workflows")
fails = 0
check = lambda do |ok, what|
  puts "#{ok ? "PASS" : "FAIL"} #{what}"
  fails += 1 unless ok
end
load_wf = ->(name) { YAML.safe_load(File.read(File.join(dir, name)), aliases: false) }
on_of = ->(wf) { wf["on"] || wf[true] }                # YAML 1.1 reads a bare `on:` key as true
NO_FORK = "github.event.pull_request.head.repo.full_name == github.repository"

{ "claude-review.yml" => { "contents" => "read", "pull-requests" => "write" },
  "commit-identity.yml" => { "contents" => "read" } }.each do |name, perms|
  wf = load_wf.(name)
  on = wf["on"] || wf[true]                      # YAML 1.1 reads a bare `on:` key as true
  check.(on.is_a?(Hash) && on.keys == ["pull_request"], "#{name}: triggered by pull_request only (no pull_request_target)")
  check.(on.dig("pull_request", "branches")&.sort == %w[main staging], "#{name}: pull requests to staging and main")
  check.(wf["permissions"] == {}, "#{name}: no permission at the workflow level")
  wf["jobs"].each do |jn, job|
    check.(job["if"].to_s.include?(NO_FORK), "#{name} #{jn}: skips a fork's branch")
    check.(job["permissions"] == perms, "#{name} #{jn}: permissions exactly #{perms}")
  end
end

wf = load_wf.("claude-review.yml")
steps = wf["jobs"].values.flat_map { |j| j["steps"] }
act = steps.find { |s| s["uses"].to_s.start_with?("anthropics/claude-code-action@") }
with = act ? act["with"] : {}
check.(act && with["claude_code_oauth_token"] == "${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}", "the review uses the subscription token")
check.(!with.key?("anthropic_api_key") && !File.read(File.join(dir, "claude-review.yml")).include?("ANTHROPIC_API_KEY"),
       "no API key anywhere in the review workflow")
check.(with["github_token"] == "${{ secrets.GITHUB_TOKEN }}", "the review posts with the job's own token")
args = with["claude_args"].to_s
allowed = args[/--allowedTools "([^"]*)"/, 1].to_s.split(",")
check.(!allowed.empty? && allowed.none? { |t| t =~ /\A(Edit|Write|NotebookEdit|Bash\((git|gh pr review|gh pr merge|gh api))/ },
       "the reviewer is allowed no editing, git, review, merge or api tool")
# nothing a diff's text could steer into reading outside the checkout or posting a file
check.(allowed.none? { |t| %w[Read Grep Glob].include?(t) || t.start_with?("Read(", "Grep(", "Glob(") },
       "no Read / Grep / Glob rule: reads stay inside the checkout")
check.(allowed.none? { |t| t.include?("gh pr comment") } && args.include?("Bash(gh pr comment:*)"),
       "gh pr comment is not allowed and is denied outright (it could post a file)")
num = "${{ github.event.pull_request.number }}"
check.(allowed.select { |t| t.start_with?("Bash(") }.all? { |t| ["Bash(gh pr diff #{num})", "Bash(gh pr view #{num})"].include?(t) },
       "the only commands are gh pr diff / view of this pull request, with no further arguments")
denied = args[/--disallowedTools "([^"]*)"/, 1].to_s.split(",")
check.(%w[Edit Write Bash(git\ push:*) Bash(gh\ pr\ review:*) Bash(gh\ pr\ merge:*)].all? { |t| denied.include?(t) },
       "editing, push, review and merge are denied outright")
check.(args =~ /^\s*--permission-mode default\s*$/ && args.scan(/--permission-mode/).size == 1,
       "the allow list is the whole list: --permission-mode default (auto mode would let a review pass other calls)")
check.(with["prompt"].to_s.include?("CODING_STANDARDS.md"), "the prompt reviews against CODING_STANDARDS.md")
check.(act && act["continue-on-error"] == true, "a failed review step never fails the job, so it never blocks a merge")
job_t = wf["jobs"].values.first["timeout-minutes"].to_i
check.(act && act["timeout-minutes"].is_a?(Integer) && act["timeout-minutes"] < job_t,
       "the review step times out before the job does, so a hang fails the step, not the job")
# a line on the PR records that the review ran, on which head and with what outcome (the model stays inline-only)
ai = steps.index(act)
rec = ai && steps[(ai + 1)..].find { |s| s["run"].to_s.include?("gh pr comment") }
renv = rec ? (rec["env"] || {}) : {}
check.(rec && rec["if"] == "always()", "a step after the review, with if: always(), records that it ran")
check.(rec && !rec["run"].include?("${{"), "the recording step's run has no ${{ }} in it (values come through env)")
check.(rec && rec["continue-on-error"] == true && rec["timeout-minutes"].is_a?(Integer) && rec["timeout-minutes"] <= 2,
       "a failed or hung recording never fails the job (continue-on-error, timeout-minutes 2)")
check.(act && act["id"] && renv["OUTCOME"] == "${{ steps.#{act["id"]}.outcome }}", "the recorded outcome is the review step's (its id)")
check.(renv["HEAD_SHA"] == "${{ github.event.pull_request.head.sha }}" && renv["PR"] == "${{ github.event.pull_request.number }}" &&
       renv["GH_TOKEN"] == "${{ secrets.GITHUB_TOKEN }}", "the PR number, the head SHA and the job's own token come through env")
check.(rec && rec["run"].strip == 'gh pr comment "$PR" --body "Claude review ran on $HEAD_SHA: $OUTCOME."',
       "the recorded line is the head SHA and the outcome, nothing else")
check.(with["prompt"].to_s.include?("If nothing is wrong, post nothing."), "the reviewer itself still posts nothing when nothing is wrong")
# a failed review says why, without its content
why = ai && steps[(ai + 1)..].find { |s| s["run"].to_s.include?("review-failure-facts.sh") }
wenv = why ? (why["env"] || {}) : {}
check.(why && why["if"] == "steps.#{act["id"]}.outcome == 'failure'", "a step after the review runs only when the review failed")
check.(why && why["continue-on-error"] == true && why["timeout-minutes"].is_a?(Integer) && why["timeout-minutes"] <= 2,
       "the failure step can never fail or hold the job (continue-on-error, a short timeout)")
check.(wenv.keys == ["EXECUTION_FILE"] && wenv["EXECUTION_FILE"] == "${{ steps.#{act["id"]}.outputs.execution_file }}",
       "the failure step gets the execution file's path through env, and nothing else")
check.(why && why["run"].strip == 'bash .github/scripts/review-failure-facts.sh "$EXECUTION_FILE"', "the failure step runs only the facts script")
check.(!with.key?("show_full_output") && !File.read(File.join(dir, "claude-review.yml")).include?("show_full_output:"),
       "show_full_output is never set (the log is public)")
check.(ai && why && rec && steps.index(why) > ai && steps.index(rec) > steps.index(why), "order: the review, then why it failed, then the record")
idruns = load_wf.("commit-identity.yml")["jobs"].values.first["steps"].map { |s| s["run"].to_s }.join("\n")
check.(idruns.include?("bash .github/scripts/test-review-failure-facts.sh"), "the identity job runs the failure facts' own tests")
check.(on_of.(wf).dig("pull_request", "types") == %w[opened synchronize reopened ready_for_review],
       "the review runs on opened, synchronize, reopened and ready_for_review (not edited)")
check.(on_of.(load_wf.("commit-identity.yml")).dig("pull_request", "types")&.include?("edited"),
       "the identity check also runs when a PR is retargeted (edited)")
check.(!with.key?("allowed_bots") && !with.key?("allowed_non_write_users"), "no bot and no user without write access can start it")
idj = load_wf.("commit-identity.yml")["jobs"].values.first
idsteps = idj["steps"]
check.(idj["continue-on-error"].nil? && idsteps.none? { |s| s.key?("continue-on-error") }, "the identity check is a hard check")
co = idsteps.find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
check.(co && !(co["with"] || {}).key?("ref"),
       "the identity job checks out the default merge ref, so its scripts match its workflow")
check.(idsteps.map { |s| s["run"].to_s }.join("\n").include?('[ "$(git rev-parse HEAD^2)" = "$HEAD" ] ||'),
       "the identity check walks the PR head, the merge commit's second parent, never the merge commit itself")
check.(co && co.dig("with", "fetch-depth") == 0, "the identity job fetches the whole history and the branches")
runs = idsteps.map { |s| s["run"].to_s }.join("\n")
check.(runs.include?("origin/staging") && runs.include?("origin/main") &&
       runs =~ /check-commit-identity\.sh "\$BASE" "\$HEAD" "\$STAGING" "\$MAIN"/,
       "the check excludes staging's and main's tips")
check.(steps.none? { |s| s.dig("with", "ref").to_s.include?("head") }, "the review checks out the base's merge ref, never the head ref")
# The token's shape before the review, never its value: a stored token with a line break, a space or an invisible
# character fails every review with an authentication error that the public log cannot show. Exactly one of four
# lines; no part of the value, its length or a hash. A byte outside printable ASCII (a no-break or zero-width space, or
# a control byte that is not whitespace) has its own line, since stripping spaces and line breaks does not remove it;
# it wins over whitespace.
SECRET = "${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}"
tok = steps.find { |s| (s["env"] || {}).values.include?(SECRET) }
tenv = tok ? (tok["env"] || {}) : {}
check.(tok && ai && steps.index(tok) < ai && !tok.key?("if"), "a step before the review, with no condition, classifies the token's shape")
check.(tok && tenv.size == 1 && tok["shell"] == "bash" && !tok["run"].to_s.include?("${{"),
       "the token reaches the shape step through its one env key only, never ${{ }} in run")
check.(tok && tok["continue-on-error"] == true && tok["timeout-minutes"] == 1, "the shape step never fails or holds the job (continue-on-error, timeout-minutes 1)")
check.(tok && tok["run"].to_s.lines.first.to_s.strip == "export LC_ALL=C", "the shape step reads bytes, not a locale's characters (LC_ALL=C first)")
check.(File.read(File.join(dir, "claude-review.yml")).downcase.scan("secrets.claude_code_oauth_token").size == 2,
       "the token appears twice only: the shape step's env and the review's input")
require "open3"
require "tmpdir"
LINES = { ok: "review token: ok", empty: "review token: empty", ws: "review token: has whitespace or a line break",
          ascii: "review token: has a character outside printable ASCII" }
probe = "SHAPEPROBE-x_1"
{ probe => :ok, "" => :empty, "#{probe} x" => :ws, "\t#{probe}" => :ws, "#{probe}\r" => :ws, "#{probe}\n" => :ws,
  "SHAPEPROBE-x\n_1" => :ws, " " => :ws, "#{probe}\v" => :ws, "#{probe}\u00a0" => :ascii, "#{probe}\u200b" => :ascii,
  "#{probe}\x01" => :ascii, "#{probe}\x7f" => :ascii, "#{probe}\u00a0\n" => :ascii }.each do |val, want|
  out = nil; st = nil
  if tok
    Dir.mktmpdir do |d|
      sc = File.join(d, "step.sh"); File.write(sc, tok["run"].to_s)
      out, st = Open3.capture2e({ tenv.keys.first.to_s => val }, "bash", "--noprofile", "--norc", "-eo", "pipefail", sc)
    end
  end
  check.(tok && st&.success? && out == LINES[want] + "\n" && !out.include?("SHAPEPROBE"),
         "the shape step prints exactly \"#{LINES[want]}\" for a token #{val.inspect.sub("SHAPEPROBE", "…")}, and exits 0")
end

# The privacy scan. Its tests: a pull_request job in commit-identity.yml with no secret. The scan:
# privacy-scan.yml on pull_request_target, so the workflow and the script come from main (the default branch); it runs nothing of
# the PR (no `uses:`, no checkout, the head fetched as objects); the list comes from the environment privacy-scan
# (deployment branch main only) and reaches one step only, through env.
tj = load_wf.("commit-identity.yml")["jobs"]["privacy-scan-tests"] || {}
tss = tj["steps"] || []
tst = tss.find { |s| s["run"].to_s.strip == "bash .github/scripts/test-privacy-scan.sh" }
check.(tst && tst.dig("env", "GITLEAKS_REAL") == "${{ runner.temp }}/gitleaks/gitleaks",
       "privacy-scan-tests: the scan's own tests run on pull_request with the real gitleaks")
check.(!File.read(File.join(dir, "commit-identity.yml")).include?("secrets."), "commit-identity.yml reads no secret at all")
psw = load_wf.("privacy-scan.yml")
pon = on_of.(psw)
check.(pon.is_a?(Hash) && pon.keys == ["pull_request_target"] && pon.dig("pull_request_target", "branches")&.sort == %w[main staging] &&
       pon.dig("pull_request_target", "types") == %w[opened synchronize reopened edited ready_for_review labeled unlabeled],
       "privacy-scan.yml: pull_request_target only, PRs to staging and main, the label events included")
check.(psw["permissions"] == {} && psw["jobs"].keys == ["privacy-scan"], "privacy-scan.yml: no workflow-level permission, one job")
ps = psw["jobs"]["privacy-scan"]
pss = ps["steps"] || []
check.(ps["permissions"] == { "contents" => "read" } && ps["environment"] == "privacy-scan" && !ps.key?("if") &&
       ps["continue-on-error"].nil? && pss.none? { |s| s.key?("continue-on-error") || s.key?("if") },
       "privacy-scan: contents read, environment privacy-scan, no condition, a hard check")
check.(pss.none? { |s| s.key?("uses") } && !File.read(File.join(dir, "privacy-scan.yml")).match?(/^\s*-?\s*uses:/),
       "privacy-scan: no `uses:` at all (no checkout of the PR, no third-party action with the secret)")
first = pss.first || {}
check.(first["run"].to_s.include?('[ "$HEAD_REPO" = "$REPO" ] ||') && first["run"].to_s.include?("exit 1") &&
       first["env"] == { "HEAD_REPO" => "${{ github.event.pull_request.head.repo.full_name }}", "REPO" => "${{ github.repository }}" },
       "privacy-scan: the first step fails a fork's PR")
fetch = pss.find { |s| s["run"].to_s.include?("git fetch") } || {}
fr = fetch["run"].to_s
check.(fr.include?('"+refs/pull/$PR/head:refs/remotes/pr/head"') && fr.include?('[ "$(git rev-parse refs/remotes/pr/head)" = "$HEAD" ]') &&
       fr.include?('git show "$GITHUB_SHA:.github/scripts/privacy-scan.sh" > "$RUNNER_TEMP/privacy-scan.sh"') &&
       !fr.match?(/git (checkout|switch|worktree|reset)|read-tree/),
       "privacy-scan: the PR head is fetched as objects and checked, never checked out; the script comes from the run's own commit (main's)")
PSEC = "${{ secrets.PRIVACY_IDENTIFIERS }}"
holders = pss.select { |s| s.to_s.include?("secrets.") }
check.(holders.size == 1 && holders.first.equal?(pss.last) && holders.first["env"]&.dig("PRIVACY_IDENTIFIERS") == PSEC &&
       File.read(File.join(dir, "privacy-scan.yml")).scan("secrets.").size == 1,
       "privacy-scan: the list reaches the last step only, through env, and no other secret is read")
check.(pss.none? { |s| s["run"].to_s.include?("${{") }, "privacy-scan: no ${{ }} in any run (values come through env)")
lastrun = (pss.last || {})["run"].to_s.lines.map(&:strip).reject(&:empty?)
check.(lastrun.size == 3 && lastrun[0] == 'cd "$RUNNER_TEMP/objects"' && lastrun[1] == 'read -r STAGING MAIN < "$RUNNER_TEMP/tips"' &&
       lastrun[2].start_with?('bash "$RUNNER_TEMP/privacy-scan.sh"'),
       "privacy-scan: the step that holds the list runs builtins and the script only (no git, no fetch)")
glstep = pss.find { |s| s["run"].to_s.include?("sha256sum -c") }
check.(glstep && glstep.dig("env", "GL_SHA256").to_s =~ /\A[0-9a-f]{64}\z/ && glstep.dig("env", "GL_VERSION").to_s =~ /\A\d+\.\d+\.\d+\z/ &&
       glstep["run"].index("sha256sum -c") < glstep["run"].index("tar -xzf"), "privacy-scan: gitleaks is checked against its pinned digest before it is unpacked")
check.(glstep && tss.find { |s| s["run"].to_s.include?("sha256sum -c") }&.dig("env") == glstep["env"],
       "privacy-scan: the tests job and the scan pin the same gitleaks")
last = pss.last || {}
check.(last["run"].to_s =~ /bash "\$RUNNER_TEMP\/privacy-scan\.sh" "\$BASE" "\$HEAD" "\$STAGING" "\$MAIN"/ &&
       !last["run"].to_s.include?("bash .github/") && last["env"]&.keys&.sort == %w[BASE GITLEAKS HEAD PRIVACY_IDENTIFIERS],
       "privacy-scan: runs the run's own commit's script on the new commits; env holds the list, gitleaks and the two SHAs only")
check.(%w[pull_request.title pull_request.body pull_request.head.ref label.name].none? { |k| ps.to_s.include?(k) },
       "privacy-scan: the PR's title, body, branch name and labels never pass through env or ${{ }} (the script reads the event file)")

puts "review workflow checks: #{fails.zero? ? "all passed" : "#{fails} failed"}"
exit(fails.zero? ? 0 : 1)
