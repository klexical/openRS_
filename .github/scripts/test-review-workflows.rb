#!/usr/bin/env ruby
# test-review-workflows.rb [<workflows dir>] — pins the shape of claude-review.yml and commit-identity.yml (owner Q137,
# Q141): pull_request only, never pull_request_target; every job skips a fork's branch; the least permissions; the
# subscription token, never an API key; the reviewer holds no write tool. Exit 0 when every check passes.
require "yaml"
dir = ARGV[0] || File.join(__dir__, "..", "workflows")
fails = 0
check = lambda do |ok, what|
  puts "#{ok ? "PASS" : "FAIL"} #{what}"
  fails += 1 unless ok
end
load_wf = ->(name) { YAML.safe_load(File.read(File.join(dir, name)), aliases: false) }
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
denied = args[/--disallowedTools "([^"]*)"/, 1].to_s.split(",")
check.(%w[Edit Write Bash(git\ push:*) Bash(gh\ pr\ review:*) Bash(gh\ pr\ merge:*)].all? { |t| denied.include?(t) },
       "editing, push, review and merge are denied outright")
check.(args =~ /^\s*--permission-mode default\s*$/ && args.scan(/--permission-mode/).size == 1,
       "the allow list is the whole list: --permission-mode default (auto mode would let a review pass other calls)")
check.(with["prompt"].to_s.include?("CODING_STANDARDS.md"), "the prompt reviews against CODING_STANDARDS.md")
check.(act && act["continue-on-error"] == true, "a failed review step never fails the job (Q125: no Light merge waits on it)")
check.(!with.key?("allowed_bots") && !with.key?("allowed_non_write_users"), "no bot and no user without write access can start it")
idj = load_wf.("commit-identity.yml")["jobs"].values.first
idsteps = idj["steps"]
check.(idj["continue-on-error"].nil? && idsteps.none? { |s| s.key?("continue-on-error") }, "the identity check is a hard check")
co = idsteps.find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
check.(co && co.dig("with", "fetch-depth") == 0, "the identity job fetches the whole history and the branches")
runs = idsteps.map { |s| s["run"].to_s }.join("\n")
check.(runs.include?("origin/staging") && runs.include?("origin/main") &&
       runs =~ /check-commit-identity\.sh "\$BASE" "\$HEAD" "\$STAGING" "\$MAIN"/,
       "the check excludes staging's and main's tips")
check.(steps.none? { |s| s.dig("with", "ref").to_s.include?("head") }, "the review checks out the base's merge ref, never the head ref")

puts "review workflow checks: #{fails.zero? ? "all passed" : "#{fails} failed"}"
exit(fails.zero? ? 0 : 1)
