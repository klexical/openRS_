# test-release-workflows.rb — checks the release workflows' shape (#363): no secret is reachable from a pull request's
# head; every job on CI's tag path has exactly its condition (an android-v tag, not a nightly) and exactly its
# permissions; the shared recipe .github/workflows/release-apk.yml is only called (workflow_call), builds the commit it
# is given through android/scripts/release-apk.sh, and is the only builder; publish-release re-checks the recipe's file
# name and SHA-256 before it attests and attaches; no run line holds an expression; CI's build job runs the release
# script tests; nightly.yml (T5) runs only on its schedule and by hand, in one group, in dry-run mode (PUBLISH false, no job
# able to write contents), builds only staging's tip through the recipe, and re-checks the digest before the launch check
# and the attestation. Reads the YAML only; GitHub's own run of the workflows is the evidence for what they do. Ruby's standard library only (YAML 1.1: the key `on` reads as true).
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
# The one condition of every job on CI's tag path: an android-v tag that is not a nightly — compared whole, never as a part
TAG_IF = "startsWith(github.ref, 'refs/tags/android-v') && !contains(github.ref_name, '-nightly.')"
RECIPE_IF = "${{ !startsWith(github.event_name, 'pull_request') }}"
# Each tag job's permissions, exactly (a missing block would get the default token's)
TAG_JOB_PERMISSIONS = {
  'release-name' => {},
  'release-apk' => { 'contents' => 'read' },
  'publish-release' => { 'contents' => 'write', 'id-token' => 'write', 'attestations' => 'write' }
}.freeze
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

check('no workflow runs on pull_request_target or workflow_run') do
  all.flat_map do |f|
    t = triggers(YAML.safe_load(File.read(f)))
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
  name = 'release-apk-${{ github.run_id }}-${{ github.run_attempt }}'
  why = []
  why << "the recipe uploads #{up.first.dig('with', 'name').inspect}, expected #{name}" unless up.first.dig('with', 'name') == name
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

check("ci.yml's build job runs the release script tests") do
  runs = steps(jobs(ci)['build'] || {}).map { |s| s['run'].to_s }.join("\n")
  %w[scripts/test-release-config.sh scripts/test-release-identity.sh scripts/test-verify-release.sh
     scripts/test-release-apk.sh scripts/test-release-workflows.rb scripts/test-nightly-gate.sh
     scripts/test-launch-check.sh].reject { |t| runs.include?(t) }
    .map { |t| "build job does not run #{t}" }
end

check('in every workflow, a secret-holding job outputs nothing but the digest') do
  all.flat_map do |f|
    jobs(YAML.safe_load(File.read(f))).select { |_, j| secret_bearing?(j) && j['outputs'] }
      .reject { |_, j| j['outputs'].keys == %w[sha256] }.map { |id, j| "#{File.basename(f)} job #{id} outputs #{j['outputs'].keys.inspect}" }
  end
end

# ── nightly.yml (#363 T5): the nightly in dry-run mode ────────────────────────────────────────────────────────────────
GATE_IF = "needs.gate.outputs.run == 'true'"
NIGHTLY_JOBS = {   # job => [condition, permissions], exactly; while PUBLISH is false no job may write contents
  'gate' => ['', { 'contents' => 'read' }],
  'checks' => [GATE_IF, { 'contents' => 'read' }],
  'release' => [GATE_IF, { 'contents' => 'read' }],
  'launch' => [GATE_IF, { 'contents' => 'read' }],
  'attest' => [GATE_IF, { 'contents' => 'read', 'id-token' => 'write', 'attestations' => 'write' }]
}.freeze
ARTIFACT = 'release-apk-${{ github.run_id }}-${{ github.run_attempt }}'
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

check('nightly.yml is in dry-run mode: PUBLISH is false and no job can write contents') do
  why = []
  why << "PUBLISH is #{nightly.dig('env', 'PUBLISH').inspect}, expected 'false'" unless nightly.dig('env', 'PUBLISH') == 'false'
  why << "top-level permissions #{nightly['permissions'].inspect}, expected none" unless nightly['permissions'] == {}
  why << "jobs #{jobs(nightly).keys.inspect}" unless jobs(nightly).keys == NIGHTLY_JOBS.keys
  NIGHTLY_JOBS.each do |id, (c, perms)|
    job = jobs(nightly)[id] or next
    why << "job #{id} runs under #{cond(job).inspect}, expected #{c.inspect}" unless cond(job) == c
    why << "job #{id} has permissions #{job['permissions'].inspect}, expected #{perms.inspect}" unless job['permissions'] == perms
  end
  all.each do |f|
    w = YAML.safe_load(File.read(f))
    jobs(w).each { |id, j| why << "#{File.basename(f)} job #{id} writes contents" if j['permissions'].is_a?(Hash) && j['permissions']['contents'] == 'write' && f.end_with?('nightly.yml') }
  end
  st = jobs(nightly).values.flat_map { |j| steps(j) }
  st.each do |x|
    u = x['uses'].to_s; r = x['run'].to_s
    why << "a step publishes: #{u}" if u.start_with?('softprops/')
    why << "a step publishes: #{r.lines.first.strip}" if r =~ /\bgh release\b|\bgit (push|tag)\b|\bgh api\b/
  end
  why
end

check("the gate's publish output is the PUBLISH constant and nothing else") do
  gate = jobs(nightly)['gate'] || {}
  st = steps(gate).find { |x| x['id'] == 'mode' }
  why = []
  why << "gate outputs.publish is #{gate.dig('outputs', 'publish').inspect}" unless gate.dig('outputs', 'publish') == '${{ steps.mode.outputs.publish }}'
  next why << 'no step with id mode' if st.nil?
  why << "the mode step runs #{st['run'].inspect}" unless st['run'].to_s.strip == 'echo "publish=$PUBLISH" >> "$GITHUB_OUTPUT"'
  why << 'the mode step sets its own env' if st.key?('env')
  why << 'the gate job sets its own env' if gate.key?('env')
  why
end

check("the gate takes today's UTC date once, first, and checks out staging's tip only") do
  gate = jobs(nightly)['gate'] || {}
  st = steps(gate)
  why = []
  why << "the first step runs #{st.first && st.first['run'].inspect}" unless st.first && st.first['id'] == 'today' && st.first['run'].to_s.strip == 'echo "date=$(date -u +%Y%m%d)" >> "$GITHUB_OUTPUT"'
  co = st.select { |x| x['uses'].to_s.start_with?('actions/checkout@') }
  why << "gate checkouts #{co.map { |x| x['with'] }.inspect}" unless co.size == 1 && co.first['with'] == { 'ref' => 'staging', 'fetch-depth' => 0, 'persist-credentials' => false }
  g = st.find { |x| x['id'] == 'gate' }
  why << "the gate step runs #{g && g['run'].inspect}" unless g && g['run'].to_s.strip == 'bash android/scripts/nightly-gate.sh "$SHA" "$TODAY"'
  want = { 'GH_TOKEN' => '${{ github.token }}', 'SHA' => '${{ steps.head.outputs.sha }}', 'TODAY' => '${{ steps.today.outputs.date }}' }
  why << "the gate step's env #{g && g['env'].inspect}" unless g && g['env'] == want
  outs = %w[run reason sha date name tag base_tag].map { |k| [k, "${{ steps.gate.outputs.#{k} }}"] }.to_h.merge('publish' => '${{ steps.mode.outputs.publish }}')
  why << "gate outputs #{gate['outputs'].inspect}" unless gate['outputs'] == outs
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
  why << 'nightly.yml reads github.sha or github.ref' if File.read(File.join(WF, 'nightly.yml'), encoding: 'UTF-8') =~ /github\.(sha|ref)\b/
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

check("the checks job runs every Gradle and script line of ci.yml's build and lint jobs") do
  ci_runs = %w[build lint].flat_map { |id| steps(jobs(ci)[id] || {}).map { |x| x['run'].to_s } }
             .flat_map(&:lines).map(&:strip).select { |l| l =~ %r{gradlew|scripts/} }
             .map { |l| l.sub(/^chmod \+x gradlew && /, '') }
  mine = steps(jobs(nightly)['checks'] || {}).map { |x| x['run'].to_s }.flat_map(&:lines).map(&:strip)
  ci_runs.reject { |l| mine.include?(l) }.map { |l| "checks does not run: #{l}" }
end

check('the release job calls the recipe for the gate\'s commit, name, tag and date') do
  job = jobs(nightly)['release'] || {}
  want = { 'sha' => '${{ needs.gate.outputs.sha }}', 'name' => '${{ needs.gate.outputs.name }}',
           'tag' => '${{ needs.gate.outputs.tag }}', 'nightly_date' => '${{ needs.gate.outputs.date }}' }
  why = []
  why << "uses #{job['uses'].inspect}" unless job['uses'] == RECIPE
  why << "with #{job['with'].inspect}" unless job['with'] == want
  why << "secrets #{job['secrets'].inspect}" unless job['secrets'] == 'inherit'
  why << "needs #{job['needs'].inspect}" unless Array(job['needs']) == %w[gate checks]
  others = jobs(nightly).reject { |id, _| id == 'release' }.select { |_, j| secret_bearing?(j) }.keys
  why << "other jobs hold repository secrets: #{others.inspect}" unless others.empty?
  why
end

check('launch and attest take the recipe\'s artifact, re-check its digest first, then only check or attest') do
  why = []
  { 'launch' => %w[bash], 'attest' => %w[actions/attest-build-provenance gh] }.each do |id, _|
    st = steps(jobs(nightly)[id] || {})
    d = st.index { |x| x['uses'].to_s.start_with?('actions/download-artifact@') }
    c = st.index { |x| x['run'].to_s.include?('sha256sum -c') }
    next why << "job #{id}: download #{d.inspect}, digest check #{c.inspect}" if d.nil? || c.nil?
    why << "job #{id} downloads #{st[d].dig('with', 'name').inspect}" unless st[d].dig('with', 'name') == ARTIFACT
    why << "job #{id}: the digest check comes before the download" unless c > d
    env = st[c]['env'] || {}
    why << "job #{id}: digest check env #{env.inspect}" unless env == { 'SHA256' => '${{ needs.release.outputs.sha256 }}', 'NAME' => '${{ needs.gate.outputs.name }}' }
    r = st[c]['run'].to_s
    ['set -euo pipefail', 'FILE="openRS_v${NAME}.apk"', '[[ "$SHA256" =~ ^[0-9a-f]{64}$ ]]', '[ "$(ls release-apk)" = "$FILE" ]'].each do |must|
      why << "job #{id}: the digest check lacks #{must}" unless r.include?(must)
    end
    rest = st[(c + 1)..-1]
    if id == 'launch'
      why << "launch after the check runs #{rest.map { |x| x['name'] }.inspect}" unless rest.map { |x| x['run'].to_s.lines.last.to_s.strip }.last == 'bash android/scripts/launch-check.sh "release-apk/openRS_v${NAME}.apk" 10'
    else
      a = rest.index { |x| x['uses'].to_s.start_with?('actions/attest-build-provenance@') }
      v = rest.index { |x| x['run'].to_s.include?('gh attestation verify') }
      why << "attest: attestation #{a.inspect}, verification #{v.inspect}" unless a && v && v > a
      why << "attest attests #{rest[a].dig('with', 'subject-path').inspect}" if a && rest[a].dig('with', 'subject-path') != 'release-apk/${{ needs.gate.outputs.name }}'.sub('${{ needs.gate.outputs.name }}', 'openRS_v${{ needs.gate.outputs.name }}.apk')
    end
  end
  why
end

abort 'release workflow checks: no case ran' if $ran.zero?
if $failures.zero?
  puts "release workflow checks: all #{$ran} passed"
else
  puts "release workflow checks: #{$failures} of #{$ran} failed"
  exit 1
end
