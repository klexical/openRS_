# test-release-workflows.rb — checks the release workflows' shape (#363): no secret is reachable from a pull request's
# head; every job on CI's tag path has exactly its condition (an android-v tag, not a nightly) and exactly its
# permissions; the shared recipe .github/workflows/release-apk.yml is only called (workflow_call), builds the commit it
# is given through android/scripts/release-apk.sh, and is the only builder; publish-release re-checks the recipe's file
# name and SHA-256 before it attests and attaches; no run line holds an expression; CI's build job runs the release
# script tests; nightly.yml runs only on its schedule and by hand, in one group, builds only staging's tip through the
# recipe, and re-checks the digest before the launch check, the attestation and the publish; its launch job starts the
# emulator only through android/scripts/start-emulator.sh, and no workflow runs the SDK's emulator tools inline; only its
# publish and cleanup jobs can write contents, each only when PUBLISH is true and the run is on main (PUBLISH is true,
# owner Q127: a run on another ref is a dry run), and the scripts that write releases or tags run only there; actions.lock lists every remote action of every
# workflow under its own key (Q117). Reads the YAML only; GitHub's own run of the workflows is the evidence for what they do. Ruby's standard library only (YAML 1.1: the key `on` reads as true).
# Runs on Ruby 2.6 (macOS) and 3.x (CI's Ubuntu). Run from anywhere: ruby android/scripts/test-release-workflows.rb
require 'yaml'

ROOT = File.expand_path('../..', __dir__)
WF = File.join(ROOT, '.github', 'workflows')
$failures = 0
$ran = 0

def check(name)
  $ran += 1
  why = yield
  if why.nil? || why.empty?
    puts "PASS #{name}"
  else
    puts "FAIL #{name} — #{Array(why).join('; ')}"
    $failures += 1
  end
rescue StandardError => e
  puts "FAIL #{name} — #{e.class}: #{e.message}"
  $failures += 1
end

def load(file)
  path = File.join(WF, file)
  raise "#{file} is missing" unless File.exist?(path)
  YAML.safe_load(File.read(path))
end

def triggers(wf)
  on = wf['on'] || wf[true]
  case on
  when Hash then on.keys.map(&:to_s)
  when Array then on.map(&:to_s)
  else [on.to_s]
  end
end

def jobs(wf)
  wf.fetch('jobs')
end
def cond(job)
  job['if'].to_s
end

RECIPE = './.github/workflows/release-apk.yml'
# One artifact per run, named by the run id only: "Re-run failed jobs" keeps the run id and moves the attempt number,
# so a re-run downloader still finds the recipe's artifact; a full re-run's recipe overwrites it (#363)
ARTIFACT = 'release-apk-${{ github.run_id }}'
# The one condition of every job on CI's tag path: an android-v tag that is not a nightly — compared whole, never as a part
TAG_IF = "startsWith(github.ref, 'refs/tags/android-v') && !contains(github.ref_name, '-nightly.')"
RECIPE_IF = "${{ !startsWith(github.event_name, 'pull_request') }}"
# Each tag job's permissions, exactly (a missing block would get the default token's)
TAG_JOB_PERMISSIONS = {
  'release-name' => {},
  'release-apk' => { 'contents' => 'read' },
  'publish-release' => { 'contents' => 'write', 'id-token' => 'write', 'attestations' => 'write' }
}.freeze
START_EMULATOR_RUN = 'bash android/scripts/start-emulator.sh "system-images;android-36;google_apis;x86_64" 420'
RECIPE_RUN = 'bash scripts/release-apk.sh "$SHA" "$NAME" "$TAG" "$NIGHTLY_DATE"'
RECIPE_ENV = { 'SHA' => '${{ inputs.sha }}', 'NAME' => '${{ inputs.name }}', 'TAG' => '${{ inputs.tag }}',
               'NIGHTLY_DATE' => '${{ inputs.nightly_date }}' }.freeze
# A secret-holding job: it receives repository secrets, through `secrets: inherit` or any `secrets.NAME` other than
# GITHUB_TOKEN (the run's own token is not a repository secret). Only the APK's digest may leave such a job (#363).
def secret_bearing?(job)
  job.key?('secrets') || job.to_yaml.scan(/secrets\.([A-Za-z_][A-Za-z0-9_]*)/).flatten.any? { |n| n != 'GITHUB_TOKEN' }
end
def steps(job)
  job['steps'] || []
end
def run_lines(wf)
  jobs(wf).values.flat_map { |j| steps(j).map { |s| s['run'] }.compact }
end

ci = nil
check('ci.yml parses') { ci = load('ci.yml'); nil }
all = Dir[File.join(WF, '*.yml')].sort

# privacy-scan.yml is the one pull_request_target workflow: it runs main's code only and nothing of the pull request
# (.github/scripts/test-review-workflows.rb pins its shape)
check('no workflow runs on pull_request_target or workflow_run, but privacy-scan.yml on pull_request_target') do
  all.flat_map do |f|
    t = triggers(YAML.safe_load(File.read(f)))
    t -= ['pull_request_target'] if File.basename(f) == 'privacy-scan.yml'
    (t & %w[pull_request_target workflow_run]).map { |x| "#{File.basename(f)} runs on #{x}" }
  end
end

check('every ci.yml job that can see a secret has exactly the tag-path condition') do
  bearing = jobs(ci).select { |_, j| secret_bearing?(j) }
  next 'no job in ci.yml sees a secret (the tag path is gone?)' if bearing.empty?
  bearing.reject { |_, j| cond(j) == TAG_IF }.map { |id, j| "job #{id} sees a secret under #{cond(j).inspect}, expected #{TAG_IF}" }
end

check('the tag jobs have exactly the tag-path condition and their permissions') do
  TAG_JOB_PERMISSIONS.flat_map do |id, perms|
    job = jobs(ci)[id] or next ["no job #{id}"]
    why = []
    why << "job #{id} runs under #{cond(job).inspect}, expected #{TAG_IF}" unless cond(job) == TAG_IF
    why << "job #{id} has permissions #{job['permissions'].inspect}, expected #{perms.inspect}" unless job['permissions'] == perms
    why
  end
end

check('ci.yml calls release-apk.yml once, from release-apk, with no nightly date') do
  callers = jobs(ci).select { |_, j| j['uses'] == RECIPE }
  next "#{callers.keys.inspect} call #{RECIPE}, expected only release-apk" unless callers.keys == ['release-apk']
  job = callers['release-apk']
  why = []
  why << 'release-apk passes no nightly_date input' unless job.dig('with')&.key?('nightly_date')
  why << "release-apk passes a nightly date (#{job.dig('with', 'nightly_date').inspect})" unless job.dig('with', 'nightly_date').to_s.empty?
  why << "release-apk builds #{job.dig('with', 'sha').inspect}, expected ${{ github.sha }}" unless job.dig('with', 'sha') == '${{ github.sha }}'
  why
end

check('publish-release attaches only after the recipe') do
  job = jobs(ci)['publish-release'] or next 'no job publish-release'
  why = []
  why << "publish-release needs #{job['needs'].inspect}, expected [release-apk]" unless Array(job['needs']) == ['release-apk']
  why << 'publish-release runs whatever the recipe did (always())' if cond(job).include?('always()')
  why
end

check("publish-release re-checks the recipe's file name and SHA-256, then only attests and attaches") do
  job = jobs(ci)['publish-release'] or next 'no job publish-release'
  st = steps(job)
  chk = st.index { |s| s['run'].to_s.include?('sha256sum -c') }
  next 'publish-release has no step running sha256sum -c' if chk.nil?
  c = st[chk]
  why = []
  why << "the check step's SHA256 is #{c.dig('env', 'SHA256').inspect}" unless c.dig('env', 'SHA256') == '${{ needs.release-apk.outputs.sha256 }}'
  why << "the check step's env #{c['env'].keys.sort.inspect}, expected SHA256 and TAG" unless c['env'].keys.sort == %w[SHA256 TAG]
  why << "the check step's TAG is #{c.dig('env', 'TAG').inspect}" unless c.dig('env', 'TAG') == '${{ github.ref_name }}'
  why << "the check step's id is #{c['id'].inspect}, expected apk" unless c['id'] == 'apk'
  why << 'the check step does not name the file from the tag' unless c['run'].include?('FILE="openRS_v${TAG#android-v}.apk"')
  why << 'the check step does not require a 64-hex digest' unless c['run'].include?('[[ "$SHA256" =~ ^[0-9a-f]{64}$ ]]')
  why << 'the check step does not require the artifact to hold that one file' unless c['run'].include?('[ "$(ls release-apk)" = "$FILE" ]')
  why << 'the check step does not stop at the first failure (set -euo pipefail)' unless c['run'].lines.first.strip == 'set -euo pipefail'
  after = st[(chk + 1)..-1].map { |s| s['uses'].to_s.sub(/@.*/, '') }
  why << "the steps after the check are #{after.inspect}, expected exactly attest, then attach" unless
    after == %w[actions/attest-build-provenance softprops/action-gh-release]
  { 'actions/attest-build-provenance@' => 'subject-path', 'softprops/action-gh-release@' => 'files' }.each do |action, key|
    i = st.index { |s| s['uses'].to_s.start_with?(action) }
    next why << "publish-release has no #{action} step" if i.nil?
    why << "#{action} runs before the check" unless i > chk
    why << "#{action} takes #{st[i].dig('with', key).inspect}, not the checked file" unless st[i].dig('with', key) == '${{ steps.apk.outputs.path }}'
  end
  why
end

check('release-apk.yml is only called, with inputs sha, name, tag, nightly_date and the one output sha256') do
  r = load('release-apk.yml')
  why = []
  why << "triggers #{triggers(r).inspect}, expected only workflow_call" unless triggers(r) == ['workflow_call']
  call = (r['on'] || r[true])['workflow_call'] || {}
  inputs = (call['inputs'] || {}).keys.sort
  why << "inputs #{inputs.inspect}" unless inputs == %w[name nightly_date sha tag]
  outputs = (call['outputs'] || {}).keys.sort
  why << "outputs #{outputs.inspect}, expected only sha256 (GitHub drops an output holding a secret)" unless outputs == %w[sha256]
  jobs(r).each { |id, j| why << "job #{id} outputs #{(j['outputs'] || {}).keys.inspect}" unless (j['outputs'] || {}).keys == %w[sha256] }
  why
end

check('release-apk.yml builds the given commit, never for a pull request, with read-only permissions') do
  r = load('release-apk.yml')
  why = []
  why << "top-level permissions #{r['permissions'].inspect}, expected contents: read" unless r['permissions'] == { 'contents' => 'read' }
  jobs(r).each do |id, job|
    why << "job #{id} runs under #{cond(job).inspect}, expected #{RECIPE_IF}" unless cond(job) == RECIPE_IF
    why << "job #{id} sets its own permissions" if job.key?('permissions')
    co = steps(job).select { |s| s['uses'].to_s.start_with?('actions/checkout@') }
    next why << "job #{id} has #{co.size} checkouts, expected 1" unless co.size == 1
    w = co.first['with'] || {}
    why << "job #{id} checks out #{w['ref'].inspect}, expected ${{ inputs.sha }}" unless w['ref'] == '${{ inputs.sha }}'
    why << "job #{id} checks out without the tag history (fetch-depth 0)" unless w['fetch-depth'].to_s == '0'
    why << "job #{id} keeps the token in the checkout (persist-credentials)" unless w['persist-credentials'] == false
  end
  why
end

check("publish-release downloads the artifact under the recipe's own upload name") do
  up = jobs(load('release-apk.yml')).values.flat_map { |j| steps(j) }.select { |s| s['uses'].to_s.start_with?('actions/upload-artifact@') }
  down = steps(jobs(ci)['publish-release'] || {}).select { |s| s['uses'].to_s.start_with?('actions/download-artifact@') }
  next "#{up.size} uploads in the recipe and #{down.size} downloads in publish-release, expected 1 each" unless up.size == 1 && down.size == 1
  name = ARTIFACT
  why = []
  why << "the recipe uploads #{up.first.dig('with', 'name').inspect}, expected #{name}" unless up.first.dig('with', 'name') == name
  why << 'the recipe does not overwrite an earlier attempt\'s artifact (overwrite: true)' unless up.first.dig('with', 'overwrite') == true
  why << "publish-release downloads #{down.first.dig('with', 'name').inspect}, expected #{name}" unless down.first.dig('with', 'name') == name
  why << "the artifact is kept #{up.first.dig('with', 'retention-days').inspect} days, expected 1" unless up.first.dig('with', 'retention-days') == 1
  why
end

check('release-apk.yml builds through release-apk.sh, its inputs by env, and runs Gradle nowhere else') do
  r = load('release-apk.yml')
  st = jobs(r).values.flat_map { |j| steps(j) }
  b = st.select { |s| s['id'] == 'recipe' }
  next "#{b.size} steps with id recipe, expected 1" unless b.size == 1
  why = []
  why << "the recipe step runs #{b.first['run'].inspect}, expected #{RECIPE_RUN}" unless b.first['run'].to_s.strip == RECIPE_RUN
  RECIPE_ENV.each { |k, v| why << "the recipe step's #{k} is #{b.first.dig('env', k).inspect}, expected #{v}" unless b.first.dig('env', k) == v }
  st.each { |s| why << "a step runs Gradle itself: #{s['run'].lines.first.strip}" if s['run'].to_s.include?('gradlew') }
  why
end

check('no workflow puts an expression inside a run line (values reach the shell through env)') do
  all.flat_map do |f|
    run_lines(YAML.safe_load(File.read(f))).select { |l| l.include?('${{') }.map { |l| "#{File.basename(f)}: #{l.lines.first.strip}" }
  end
end

SCRIPT_TESTS = %w[scripts/test-release-config.sh scripts/test-release-identity.sh scripts/test-verify-release.sh
                  scripts/test-release-apk.sh scripts/test-release-workflows.rb scripts/test-nightly-gate.sh
                  scripts/test-launch-check.sh scripts/test-start-emulator.sh].freeze

check("ci.yml's build job runs the release script tests") do
  runs = steps(jobs(ci)['build'] || {}).map { |s| s['run'].to_s }.join("\n")
  SCRIPT_TESTS.reject { |t| runs.include?(t) }.map { |t| "build job does not run #{t}" }
end

check('in every workflow, a secret-holding job outputs nothing but the digest') do
  all.flat_map do |f|
    jobs(YAML.safe_load(File.read(f))).select { |_, j| secret_bearing?(j) && j['outputs'] }
      .reject { |_, j| j['outputs'].keys == %w[sha256] }.map { |id, j| "#{File.basename(f)} job #{id} outputs #{j['outputs'].keys.inspect}" }
  end
end

# ── nightly.yml (#363): the nightly, publishing on main (Q127) ────────────────────────────────────────────────────────
GATE_IF = "needs.gate.outputs.run == 'true'"
# The publishing jobs' condition (#363): PUBLISH true (from the mode job) AND the run on main, whose copy of this file is
# the reviewed one (a manual run on another ref runs that ref's copy), AND the gate said build
PUBLISH_IF = "needs.mode.outputs.publish == 'true' && github.ref == 'refs/heads/main' && needs.gate.outputs.run == 'true'"
# The clean-up's: the same, after publish failed, was cancelled or timed out (always() lets it run then)
CLEANUP_IF = "always() && #{PUBLISH_IF} && (needs.publish.result == 'failure' || needs.publish.result == 'cancelled')"
WRITE_JOBS = %w[publish cleanup].freeze
NIGHTLY_JOBS = {   # job => [condition, permissions, needs, timeout-minutes], exactly; only WRITE_JOBS write contents
  'mode' => ['', {}, [], 5],
  'gate' => ['', { 'contents' => 'read' }, [], 10],
  'checks' => [GATE_IF, { 'contents' => 'read' }, %w[gate], 45],
  'release' => [GATE_IF, { 'contents' => 'read' }, %w[gate checks], nil],   # a workflow call: the recipe's own job has 45
  'launch' => [GATE_IF, { 'contents' => 'read' }, %w[gate release], 15],
  'attest' => [GATE_IF, { 'contents' => 'read', 'id-token' => 'write', 'attestations' => 'write' }, %w[gate release launch], 10],
  'publish' => [PUBLISH_IF, { 'contents' => 'write' }, %w[mode gate release attest], 10],
  'cleanup' => [CLEANUP_IF, { 'contents' => 'write' }, %w[mode gate publish], 5]
}.freeze
# The only actions a release workflow may use: nothing else can reach the releases API by an action (#363)
ALLOWED_USES = {
  'nightly.yml' => %w[actions/checkout@v4 actions/setup-java@v4 actions/download-artifact@v4 actions/attest-build-provenance@v2] + [RECIPE],
  'release-apk.yml' => %w[actions/checkout@v4 actions/setup-java@v4 actions/upload-artifact@v4]
}.freeze
DIGEST_RUN = <<~'SH'
  set -euo pipefail
  FILE="openRS_v${NAME}.apk"
  [[ "$SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "::error::the recipe reported no SHA-256 ('$SHA256')"; exit 1; }
  [ "$(ls release-apk)" = "$FILE" ] || { echo "::error::the artifact does not hold exactly $FILE"; exit 1; }
  echo "$SHA256  release-apk/$FILE" | sha256sum -c -
SH
VERIFY_RUN = <<~'SH'
  gh attestation verify "release-apk/openRS_v${NAME}.apk" --repo "$GITHUB_REPOSITORY" \
    --signer-workflow "$GITHUB_REPOSITORY/.github/workflows/nightly.yml"
  echo "Attested. PUBLISH is $PUBLISH: the draft and publish run only when it is true and the run is on main." >> "$GITHUB_STEP_SUMMARY"
SH
nightly = nil
check('nightly.yml parses') { nightly = load('nightly.yml'); nil }

check('nightly.yml runs at 08:17 UTC and by hand, nothing else, in one never-cancelled group') do
  on = nightly['on'] || nightly[true]
  why = []
  why << "triggers #{triggers(nightly).inspect}" unless triggers(nightly).sort == %w[schedule workflow_dispatch]
  why << "schedule #{on['schedule'].inspect}" unless on['schedule'] == [{ 'cron' => '17 8 * * *' }]
  why << 'workflow_dispatch takes inputs (a ref or a commit must never be given)' unless on['workflow_dispatch'].nil? || on['workflow_dispatch'] == {}
  why << "concurrency #{nightly['concurrency'].inspect}" unless nightly['concurrency'] == { 'group' => 'nightly', 'cancel-in-progress' => false }
  why
end

check('nightly.yml publishes: PUBLISH is true (Q127), and only publish and cleanup can write contents, under the publishing condition') do
  why = []
  why << "PUBLISH is #{nightly.dig('env', 'PUBLISH').inspect}, expected 'true'" unless nightly.dig('env', 'PUBLISH') == 'true'
  why << "top-level permissions #{nightly['permissions'].inspect}, expected none" unless nightly['permissions'] == {}
  why << "jobs #{jobs(nightly).keys.inspect}" unless jobs(nightly).keys == NIGHTLY_JOBS.keys
  NIGHTLY_JOBS.each do |id, (c, perms)|
    job = jobs(nightly)[id] or next
    why << "job #{id} runs under #{cond(job).inspect}, expected #{c.inspect}" unless cond(job) == c
    why << "job #{id} has permissions #{job['permissions'].inspect}, expected #{perms.inspect}" unless job['permissions'] == perms
    writes = job['permissions'].is_a?(Hash) && job['permissions']['contents'] == 'write'
    why << "job #{id} writes contents" if writes && !WRITE_JOBS.include?(id)
    why << "job #{id} writes contents without the publishing condition" if writes && !cond(job).include?(PUBLISH_IF)
  end
  why
end

check('no step can publish: only allowed actions, and no call to the releases API by any route') do
  why = []
  ALLOWED_USES.each do |f, allowed|
    jobs(load(f)).each do |id, job|
      used = steps(job).map { |x| x['uses'] }.compact + [job['uses']].compact
      (used - allowed).each { |u| why << "#{f} job #{id} uses #{u}" }
      steps(job).each do |x|
        r = x['run'].to_s
        why << "#{f} job #{id} reaches the releases API: #{r.lines.first.strip}" if r =~ /\b(curl|wget|gh\s+api|gh\s+release)\b|\/releases\b|\bgit\s+push\b|\bgit\s+tag\b(?!\s+(-l|--list)\b)/
      end
    end
  end
  why
end

check('jobs run in order, fail on any failure, and every job has its timeout') do
  why = []
  NIGHTLY_JOBS.each do |id, (_, _, needs, timeout)|
    job = jobs(nightly)[id] or next
    why << "job #{id} needs #{job['needs'].inspect}, expected #{needs.inspect}" unless Array(job['needs']) == needs
    why << "job #{id} has timeout-minutes #{job['timeout-minutes'].inspect}, expected #{timeout.inspect}" unless job['timeout-minutes'] == timeout
    why << "job #{id} continues on error" if job.key?('continue-on-error')
    steps(job).each do |x|
      why << "job #{id} step #{x['name'].inspect} continues on error" if x.key?('continue-on-error')
      why << "job #{id} step #{x['name'].inspect} has its own condition" if x.key?('if')
    end
  end
  rjob = jobs(load('release-apk.yml'))['build'] || {}
  why << "the recipe's job has timeout-minutes #{rjob['timeout-minutes'].inspect}, expected 45" unless rjob['timeout-minutes'] == 45
  why
end

check('the publish output comes from the mode job alone, which checks out nothing and runs no repository code') do
  mode = jobs(nightly)['mode'] || {}
  want_steps = [{ 'name' => 'Mode', 'id' => 'mode', 'run' => 'echo "publish=$PUBLISH" >> "$GITHUB_OUTPUT"' }]
  why = []
  why << "mode's steps are #{steps(mode).inspect}" unless steps(mode).map { |x| x.merge('run' => x['run'].to_s.strip) } == want_steps
  why << "mode's outputs are #{mode['outputs'].inspect}" unless mode['outputs'] == { 'publish' => '${{ steps.mode.outputs.publish }}' }
  why << 'mode sets env' if mode.key?('env')
  others = jobs(nightly).reject { |id, _| id == 'mode' }.select { |_, j| (j['outputs'] || {}).key?('publish') }.keys
  why << "other jobs output publish: #{others.inspect}" unless others.empty?
  why
end

check("the gate takes today's UTC date once, first, records staging's tip, and outputs only its decision") do
  gate = jobs(nightly)['gate'] || {}
  st = steps(gate)
  why = []
  why << "the first step runs #{st.first && st.first['run'].inspect}" unless st.first && st.first['id'] == 'today' && st.first['run'].to_s.strip == 'echo "date=$(date -u +%Y%m%d)" >> "$GITHUB_OUTPUT"'
  co = st.index { |x| x['uses'].to_s.start_with?('actions/checkout@') }
  why << "gate checkouts #{st.select { |x| x['uses'].to_s.start_with?('actions/checkout@') }.map { |x| x['with'] }.inspect}" unless co && st.count { |x| x['uses'].to_s.start_with?('actions/checkout@') } == 1 && st[co]['with'] == { 'ref' => 'staging', 'fetch-depth' => 0, 'persist-credentials' => false }
  h = co && st[co + 1]
  why << "the step after the checkout is #{h.inspect}, expected the commit recorded" unless h && h['id'] == 'head' && h['run'].to_s.strip == 'echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"'
  g = st.find { |x| x['id'] == 'gate' }
  why << "the gate step runs #{g && g['run'].inspect}" unless g && g['run'].to_s.strip == 'bash android/scripts/nightly-gate.sh "$SHA" "$TODAY"'
  want = { 'GH_TOKEN' => '${{ github.token }}', 'SHA' => '${{ steps.head.outputs.sha }}', 'TODAY' => '${{ steps.today.outputs.date }}' }
  why << "the gate step's env #{g && g['env'].inspect}" unless g && g['env'] == want
  outs = %w[run reason sha date name tag base_tag].map { |k| [k, "${{ steps.gate.outputs.#{k} }}"] }.to_h
  why << "gate outputs #{gate['outputs'].inspect}" unless gate['outputs'] == outs
  why << "the gate has #{st.size} steps, expected 4" unless st.size == 4
  why
end

check("every other checkout is the gate's commit; no build reads the run's own ref") do
  why = []
  jobs(nightly).each do |id, job|
    next if id == 'gate'
    steps(job).select { |x| x['uses'].to_s.start_with?('actions/checkout@') }.each do |x|
      why << "job #{id} checks out #{x['with'].inspect}" unless x['with'] == { 'ref' => '${{ needs.gate.outputs.sha }}', 'persist-credentials' => false }
    end
  end
  # github.ref only inside the publishing jobs' exact conditions; github.sha nowhere
  text = File.read(File.join(WF, 'nightly.yml'), encoding: 'UTF-8').gsub("if: #{CLEANUP_IF}\n", '').gsub("if: #{PUBLISH_IF}\n", '')
  why << 'nightly.yml reads github.sha, or github.ref outside the publishing condition' if text =~ /github\.(sha|ref)\b/
  why
end

check('every step that runs a repository script or Gradle comes after a checkout in its job') do
  jobs(nightly).flat_map do |id, job|
    seen = false
    steps(job).map do |x|
      seen ||= x['uses'].to_s.start_with?('actions/checkout@')
      "job #{id}: #{x['run'].lines.first.strip}" if !seen && x['run'].to_s =~ %r{scripts/|gradlew}
    end.compact
  end
end

# The commands of some jobs' run lines, one per entry: lines split at `&&`, blank lines dropped
def commands(job_list)
  job_list.flat_map { |j| steps(j || {}).map { |x| x['run'].to_s } }.flat_map(&:lines)
          .flat_map { |l| l.split('&&') }.map(&:strip).reject(&:empty?).uniq.sort
end

check("the checks job runs exactly the commands of ci.yml's build and lint jobs") do
  ci_cmds = commands([jobs(ci)['build'], jobs(ci)['lint']])
  mine = commands([jobs(nightly)['checks']])
  (ci_cmds - mine).map { |c| "checks does not run: #{c}" } + (mine - ci_cmds).map { |c| "ci.yml's build and lint do not run: #{c}" }
end

check('the release job calls the recipe for the gate\'s commit, name, tag and date') do
  job = jobs(nightly)['release'] || {}
  want = { 'sha' => '${{ needs.gate.outputs.sha }}', 'name' => '${{ needs.gate.outputs.name }}',
           'tag' => '${{ needs.gate.outputs.tag }}', 'nightly_date' => '${{ needs.gate.outputs.date }}' }
  why = []
  why << "uses #{job['uses'].inspect}" unless job['uses'] == RECIPE
  why << "with #{job['with'].inspect}" unless job['with'] == want
  why << "secrets #{job['secrets'].inspect}" unless job['secrets'] == 'inherit'
  others = jobs(nightly).reject { |id, _| id == 'release' }.select { |_, j| secret_bearing?(j) }.keys
  why << "other jobs hold repository secrets: #{others.inspect}" unless others.empty?
  why
end

check('launch and attest take the run\'s one artifact, re-check its digest exactly, then only check or attest') do
  why = []
  %w[launch attest].each do |id|
    st = steps(jobs(nightly)[id] || {})
    d = st.index { |x| x['uses'].to_s.start_with?('actions/download-artifact@') }
    c = st.index { |x| x['run'].to_s.include?('sha256sum -c') }
    next why << "job #{id}: download #{d.inspect}, digest check #{c.inspect}" if d.nil? || c.nil?
    why << "job #{id} downloads #{st[d].dig('with', 'name').inspect}, expected #{ARTIFACT}" unless st[d].dig('with', 'name') == ARTIFACT
    why << "job #{id}: the digest check comes before the download" unless c > d
    why << "job #{id}: the digest check is not exactly the agreed one" unless st[c]['run'] == DIGEST_RUN
    env = st[c]['env'] || {}
    why << "job #{id}: digest check env #{env.inspect}" unless env == { 'SHA256' => '${{ needs.release.outputs.sha256 }}', 'NAME' => '${{ needs.gate.outputs.name }}' }
    rest = st[(c + 1)..-1]
    if id == 'launch'
      why << "launch after the check runs #{rest.map { |x| x['name'] }.inspect}" unless rest.map { |x| x['run'].to_s.lines.last.to_s.strip }.last == 'bash android/scripts/launch-check.sh "release-apk/openRS_v${NAME}.apk" 10'
      kinds = rest.map { |x| x['run'] == START_EMULATOR_RUN ? 'start' : (x['run'].to_s.include?('udevadm') ? 'kvm' : (x['run'].to_s.include?('launch-check.sh') ? 'check' : x['name'].to_s)) }
      why << "launch after the digest check: #{kinds.inspect}, expected KVM, then exactly the agreed emulator start, then the launch check" unless kinds == %w[kvm start check]
      s = rest.find { |x| x['run'] == START_EMULATOR_RUN }
      why << "the emulator start step has env #{s['env'].inspect}: start-emulator.sh takes the defaults on CI" if s && s['env']
    else
      want = %w[actions/attest-build-provenance@v2 verify]
      got = rest.map { |x| x['uses'] || (x['run'] == VERIFY_RUN ? 'verify' : x['run'].to_s.lines.first.to_s.strip) }
      why << "attest after the check: #{got.inspect}, expected the attestation, then exactly the agreed verification" unless got == want
      why << "attest attests #{rest[0].dig('with', 'subject-path').inspect}" unless rest[0] && rest[0].dig('with', 'subject-path') == 'release-apk/openRS_v${{ needs.gate.outputs.name }}.apk'
    end
  end
  why
end

check("nightly.yml's checks job runs the release script tests") do
  runs = steps(jobs(nightly)['checks'] || {}).map { |s| s['run'].to_s }.join("\n")
  SCRIPT_TESTS.reject { |t| runs.include?(t) }.map { |t| "checks job does not run #{t}" }
end

# The emulator start lives in start-emulator.sh, which its test covers (#363): no workflow drives the SDK's emulator tools
# in a run line of its own
check('no workflow runs sdkmanager, avdmanager or the emulator inline') do
  all.flat_map do |f|
    run_lines(YAML.safe_load(File.read(f, encoding: 'UTF-8')))
      .select { |l| l =~ %r{sdkmanager|avdmanager|emulator/emulator|\bemulator\s+-avd} }
      .map { |l| "#{File.basename(f)}: #{l.lines.first.strip}" }
  end
end

check('no artifact name carries the attempt number, and every upload overwrites within its run') do
  why = []
  all.each do |f|
    text = File.read(f, encoding: 'UTF-8')
    why << "#{File.basename(f)} names an artifact by the attempt" if text =~ /release-apk-[^\n]*run_attempt/
    jobs(YAML.safe_load(text)).each do |id, job|
      steps(job).select { |x| x['uses'].to_s.start_with?('actions/upload-artifact@') && x.dig('with', 'name').to_s.start_with?('release-apk') }.each do |x|
        why << "#{File.basename(f)} job #{id} uploads #{x.dig('with', 'name').inspect} without overwrite" unless x.dig('with', 'overwrite') == true
        why << "#{File.basename(f)} job #{id} keeps the APK #{x.dig('with', 'retention-days').inspect} days, expected 1" unless x.dig('with', 'retention-days') == 1
      end
    end
  end
  why
end

# ── nightly.yml's publishing jobs (#363) ────────────────────────────────────────────────────────────────────────────
PUBLISH_RUN = 'bash android/scripts/nightly-publish.sh "$TAG" "openRS_ v$NAME" "$SHA" "$BASE_TAG" "release-apk/openRS_v${NAME}.apk" "$SHA256"'
PUBLISH_ENV = { 'GH_TOKEN' => '${{ github.token }}', 'TAG' => '${{ needs.gate.outputs.tag }}', 'NAME' => '${{ needs.gate.outputs.name }}',
                'SHA' => '${{ needs.gate.outputs.sha }}', 'BASE_TAG' => '${{ needs.gate.outputs.base_tag }}',
                'SHA256' => '${{ needs.release.outputs.sha256 }}' }.freeze
CLEANUP_RUN = 'bash android/scripts/nightly-cleanup.sh "$TAG" "$SHA"'
CLEANUP_ENV = { 'GH_TOKEN' => '${{ github.token }}', 'TAG' => '${{ needs.gate.outputs.tag }}', 'SHA' => '${{ needs.gate.outputs.sha }}' }.freeze
GATE_CHECKOUT = { 'ref' => '${{ needs.gate.outputs.sha }}', 'persist-credentials' => false }.freeze
# The scripts that write a release or a tag, and the calls that do it
WRITE_SCRIPTS = %w[nightly-publish.sh nightly-cleanup.sh].freeze
WRITE_CALL = /\bgh\s+release\s+(create|edit|upload|delete)\b|\bgh\s+api\b[^\n]*(--method|-X)\s*(POST|PATCH|PUT|DELETE)\b|\bgit\s+push\b|\bgit\s+tag\b(?!\s+(-l|--list)\b)/

check('publish checks out the gate\'s commit, takes the one artifact, re-checks its digest, then runs only nightly-publish.sh, base_tag by env') do
  st = steps(jobs(nightly)['publish'] || {})
  got = st.map { |x| x['uses'] || x['run'] }
  want = ['actions/checkout@v4', 'actions/download-artifact@v4', DIGEST_RUN, PUBLISH_RUN]
  next "publish's steps are #{got.inspect}" unless got.size == 4 && got[0..2] == want[0..2] && got[3].to_s.strip == PUBLISH_RUN
  why = []
  why << "publish checks out #{st[0]['with'].inspect}" unless st[0]['with'] == GATE_CHECKOUT
  why << "publish downloads #{st[1].dig('with', 'name').inspect}" unless st[1]['with'] == { 'name' => ARTIFACT, 'path' => 'release-apk' }
  why << "publish's digest check env #{st[2]['env'].inspect}" unless st[2]['env'] == { 'SHA256' => '${{ needs.release.outputs.sha256 }}', 'NAME' => '${{ needs.gate.outputs.name }}' }
  why << "publish's step env #{st[3]['env'].inspect}, expected #{PUBLISH_ENV.inspect} (base_tag through env only)" unless st[3]['env'] == PUBLISH_ENV
  why << 'a run line in nightly.yml names base_tag (it reaches the shell through env only)' if run_lines(nightly).any? { |l| l.include?('base_tag') }
  why
end

check('cleanup checks out the gate\'s commit and runs only nightly-cleanup.sh for the gate\'s tag and commit') do
  st = steps(jobs(nightly)['cleanup'] || {})
  next "cleanup's steps are #{st.map { |x| x['uses'] || x['run'] }.inspect}" unless st.size == 2 && st[0]['uses'] == 'actions/checkout@v4' && st[1]['run'].to_s.strip == CLEANUP_RUN
  why = []
  why << "cleanup checks out #{st[0]['with'].inspect}" unless st[0]['with'] == GATE_CHECKOUT
  why << "cleanup's step env #{st[1]['env'].inspect}" unless st[1]['env'] == CLEANUP_ENV
  why
end

check('only nightly-publish.sh and nightly-cleanup.sh write releases or tags, and only the publishing jobs run them') do
  why = []
  Dir[File.join(ROOT, 'android', 'scripts', '*.sh')].reject { |f| File.basename(f).start_with?('test-') }.sort.each do |f|
    next if WRITE_SCRIPTS.include?(File.basename(f))
    File.read(f, encoding: 'UTF-8').lines.each_with_index do |l, i|
      why << "#{File.basename(f)}:#{i + 1} writes a release or tag: #{l.strip}" if l.sub(/(^|\s)#.*$/, '') =~ WRITE_CALL
    end
  end
  WRITE_SCRIPTS.each do |s|
    why << "#{s} writes no release (the check reads nothing)" unless File.read(File.join(ROOT, 'android', 'scripts', s), encoding: 'UTF-8') =~ WRITE_CALL
  end
  all.each do |f|
    jobs(YAML.safe_load(File.read(f, encoding: 'UTF-8'))).each do |id, job|
      steps(job).each do |x|
        WRITE_SCRIPTS.each do |s|
          next unless x['run'].to_s =~ %r{(^|[\s/])#{Regexp.escape(s)}\b}   # the script itself, not test-<script>
          ok = File.basename(f) == 'nightly.yml' && id == (s == 'nightly-publish.sh' ? 'publish' : 'cleanup')
          why << "#{File.basename(f)} job #{id} runs #{s}" unless ok
        end
      end
    end
  end
  why
end

# Under `set -o pipefail` a pipeline fails when its producer is killed by SIGPIPE: `yes` always is once its reader exits,
# and anything feeding `head` can be when the reader stops early. Workflow run lines and the release scripts that set
# pipefail may pipe `yes` only as `(yes || true) |` and may not end a pipeline in `head` (#363).
def sigpipe_risks(text, where)
  return [] unless text.include?('pipefail')
  text.lines.each_with_index.flat_map do |l, i|
    code = l.sub(/(^|\s)#.*$/, '')       # a comment, at the start of a line or after a space (not $#)
    r = []
    r << "#{where}:#{i + 1} pipes yes without || true: #{l.strip}" if code =~ /(^|[^(|-])\byes\s*\|(?!\|)/ && code !~ /\(yes \|\| true\) \|/
    r << "#{where}:#{i + 1} ends a pipeline in head: #{l.strip}" if code =~ /\|\s*head\b/ && code !~ /\|\|\s*true/
    r
  end
end

check('no pipeline under pipefail can fail on SIGPIPE (yes, head)') do
  wf = all.flat_map do |f|
    jobs(YAML.safe_load(File.read(f, encoding: 'UTF-8'))).flat_map do |id, j|
      steps(j).flat_map { |x| sigpipe_risks(x['run'].to_s, "#{File.basename(f)} job #{id}") }
    end
  end
  sh = Dir[File.join(ROOT, 'android', 'scripts', '*.sh')].reject { |f| File.basename(f).start_with?('test-') }.sort
                                                         .flat_map { |f| sigpipe_risks(File.read(f, encoding: 'UTF-8'), File.basename(f)) }
  wf + sh
end

check("launch-check.sh's job limit is the launch job's timeout-minutes, for its retry's time budget (#363)") do
  text = File.read(File.join(ROOT, 'android', 'scripts', 'launch-check.sh'), encoding: 'UTF-8')
  limit = text[/^JOB_LIMIT_S=(\d+)$/, 1]
  minutes = jobs(nightly)['launch']['timeout-minutes']
  if limit.nil? then ["launch-check.sh has no JOB_LIMIT_S=<seconds> line"]
  elsif limit.to_i != minutes.to_i * 60 then ["JOB_LIMIT_S is #{limit}, the launch job's timeout-minutes is #{minutes}"]
  else [] end
end

# Q117: GitHub runs each action of a workflow at the commit .github/workflows/actions.lock records for it (generated by
# `gh actions-lock --no-narrow`; never edited by hand), and a job whose action has no entry fails at run time. So every
# remote action each workflow uses must be listed under that workflow's own key, release-apk.yml's included (a called
# workflow's steps are locked under its own key), each at the ref its `uses:` line names, with a full commit; and every
# action a dependency entry itself uses (a composite action's pins) has its own entry with a full commit.
check("actions.lock lists every remote action of every workflow under that workflow's own key, at the ref it names (Q117)") do
  path = File.join(WF, 'actions.lock')
  next 'actions.lock is missing (run gh actions-lock --no-narrow)' unless File.exist?(path)
  lock = YAML.safe_load(File.read(path))
  locked = lock['workflows'] || {}
  deps = lock['dependencies'] || {}
  files = Dir[File.join(WF, '*.yml')].sort
  keys = files.map { |f| ".github/workflows/#{File.basename(f)}" }
  why = []
  why << "lockfile keys #{(locked.keys - keys).inspect} name no workflow" unless (locked.keys - keys).empty?
  files.zip(keys).each do |f, key|
    used = jobs(YAML.safe_load(File.read(f))).values.flat_map { |j| steps(j).map { |st| st['uses'] } }.compact
    used = used.reject { |u| u.start_with?('./', 'docker://') }.uniq.sort
    have = Array(locked[key]).sort
    why << "#{key}: uses #{used.inspect}, locked #{have.inspect}" unless used == have
    used.each do |u|
      d = deps[u]
      next why << "#{u} has no dependency entry" if d.nil?
      why << "#{u} is locked at ref #{d['ref'].inspect}, not #{u.split('@', 2)[1]}" unless d['ref'] == u.split('@', 2)[1]
      why << "#{u} has no full commit (#{d['commit'].inspect})" unless d['commit'].to_s =~ /\Asha1-[0-9a-f]{40}\z/
    end
  end
  # The composite actions' own pins (QA R1): every action a dependency entry uses has its own entry and a full commit
  deps.each do |name, d|
    Array(d && d['uses']).each do |inner|
      e = deps[inner]
      next why << "#{name} uses #{inner}, which has no dependency entry" if e.nil?
      why << "#{inner} (used by #{name}) has no full commit (#{e['commit'].inspect})" unless e['commit'].to_s =~ /\Asha1-[0-9a-f]{40}\z/
    end
  end
  why.uniq
end

abort 'release workflow checks: no case ran' if $ran.zero?
if $failures.zero?
  puts "release workflow checks: all #{$ran} passed"
else
  puts "release workflow checks: #{$failures} of #{$ran} failed"
  exit 1
end
