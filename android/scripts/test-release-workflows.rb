# test-release-workflows.rb — checks the release workflows' shape (#363): no secret is reachable from a pull request's
# head, the shared recipe .github/workflows/release-apk.yml is only called (workflow_call) and builds the commit it is
# given, CI's tag path skips nightly tags (the nightly workflow builds those), inputs never reach a shell line through an
# expression, and CI's build job runs the release script tests. Reads the YAML only; GitHub's own run of the workflows is
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
TAG_GATE = "startsWith(github.ref, 'refs/tags/android-v')"
NIGHTLY_SKIP = "!contains(github.ref_name, '-nightly.')"
# A job that can see a secret: it names one, or it calls a workflow with secrets
def secret_bearing?(job)
  job.key?('secrets') || job.to_yaml.include?('secrets.')
end
def run_lines(wf)
  jobs(wf).values.flat_map { |j| (j['steps'] || []).map { |s| s['run'] }.compact }
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

check('every ci.yml job that can see a secret runs only for an android-v tag') do
  bearing = jobs(ci).select { |_, j| secret_bearing?(j) }
  next 'no job in ci.yml sees a secret (the tag path is gone?)' if bearing.empty?
  bearing.reject { |_, j| cond(j).include?(TAG_GATE) }.map { |id, _| "job #{id} sees a secret without #{TAG_GATE}" }
end

check('ci.yml calls release-apk.yml once, for android-v tags that are not nightlies') do
  callers = jobs(ci).select { |_, j| j['uses'] == RECIPE }
  next "#{callers.size} jobs call #{RECIPE}, expected 1" unless callers.size == 1
  id, job = callers.first
  why = []
  why << "job #{id} lacks #{TAG_GATE}" unless cond(job).include?(TAG_GATE)
  why << "job #{id} lacks #{NIGHTLY_SKIP}" unless cond(job).include?(NIGHTLY_SKIP)
  why << "job #{id} passes no nightly_date input" unless job.dig('with')&.key?('nightly_date')
  why << "job #{id} passes a nightly date (#{job.dig('with', 'nightly_date').inspect})" unless job.dig('with', 'nightly_date').to_s.empty?
  why << "job #{id} gets write permissions #{job['permissions'].inspect}" if job['permissions'].to_s.include?('write')
  why << "job #{id} has no permissions block (it would get the default token's)" unless job['permissions'].is_a?(Hash)
  why
end

check('publish-release attaches only after the recipe, and skips nightly tags') do
  job = jobs(ci)['publish-release'] or next 'no job publish-release'
  caller = jobs(ci).find { |_, j| j['uses'] == RECIPE }&.first or next 'no job calls the recipe'
  why = []
  why << "publish-release does not need #{caller}" unless Array(job['needs']).include?(caller)
  why << "publish-release lacks #{TAG_GATE}" unless cond(job).include?(TAG_GATE)
  why << "publish-release lacks #{NIGHTLY_SKIP}" unless cond(job).include?(NIGHTLY_SKIP)
  why << 'publish-release runs whatever the recipe did (always())' if cond(job).include?('always()')
  why
end

check('release-apk.yml is only called, with inputs sha, name, tag, nightly_date and outputs artifact, file, sha256') do
  r = load('release-apk.yml')
  why = []
  why << "triggers #{triggers(r).inspect}, expected only workflow_call" unless triggers(r) == ['workflow_call']
  call = (r['on'] || r[true])['workflow_call'] || {}
  inputs = (call['inputs'] || {}).keys.sort
  why << "inputs #{inputs.inspect}" unless inputs == %w[name nightly_date sha tag]
  outputs = (call['outputs'] || {}).keys.sort
  why << "outputs #{outputs.inspect}" unless outputs == %w[artifact file sha256]
  why
end

check('release-apk.yml builds the given commit, never for a pull request, with read-only permissions') do
  r = load('release-apk.yml')
  why = []
  why << "top-level permissions #{r['permissions'].inspect}, expected contents: read" unless r['permissions'] == { 'contents' => 'read' }
  jobs(r).each do |id, job|
    why << "job #{id} has no guard against pull_request events" unless cond(job).include?("!startsWith(github.event_name, 'pull_request')")
    why << "job #{id} sets its own permissions" if job.key?('permissions')
    co = (job['steps'] || []).select { |s| s['uses'].to_s.start_with?('actions/checkout@') }
    next why << "job #{id} has #{co.size} checkouts, expected 1" unless co.size == 1
    w = co.first['with'] || {}
    why << "job #{id} checks out #{w['ref'].inspect}, expected ${{ inputs.sha }}" unless w['ref'] == '${{ inputs.sha }}'
    why << "job #{id} checks out without the tag history (fetch-depth 0)" unless w['fetch-depth'].to_s == '0'
    why << "job #{id} keeps the token in the checkout (persist-credentials)" unless w['persist-credentials'] == false
  end
  why
end

check('no input or event text reaches a shell line through an expression') do
  %w[ci.yml release-apk.yml].flat_map do |f|
    run_lines(load(f)).grep(/\$\{\{\s*(inputs\.|github\.event\.|github\.head_ref)/).map { |l| "#{f}: #{l.lines.first.strip}" }
  end
end

check("ci.yml's build job runs the release script tests") do
  runs = (jobs(ci).dig('build', 'steps') || []).map { |s| s['run'].to_s }.join("\n")
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
