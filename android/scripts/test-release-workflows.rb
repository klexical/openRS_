# test-release-workflows.rb — checks the release workflows' shape (#363): no secret is reachable from a pull request's
# head; every job on CI's tag path has exactly its condition (an android-v tag, not a nightly) and exactly its
# permissions; the shared recipe .github/workflows/release-apk.yml is only called (workflow_call), builds the commit it
# is given through android/scripts/release-apk.sh, and is the only builder; publish-release re-checks the recipe's file
# name and SHA-256 before it attests and attaches; no run line holds an expression; CI's build job runs the release
# script tests. Reads the YAML only; GitHub's own run of the workflows is
# the evidence for what they do. Ruby's standard library only (YAML 1.1: the key `on` reads as true).
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
# A job that can see a secret: it names one, or it calls a workflow with secrets
def secret_bearing?(job)
  job.key?('secrets') || job.to_yaml.include?('secrets.')
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
     scripts/test-release-apk.sh scripts/test-release-workflows.rb].reject { |t| runs.include?(t) }
    .map { |t| "build job does not run #{t}" }
end

abort 'release workflow checks: no case ran' if $ran.zero?
if $failures.zero?
  puts "release workflow checks: all #{$ran} passed"
else
  puts "release workflow checks: #{$failures} of #{$ran} failed"
  exit 1
end
