# ADR 0001: Unattended nightlies replace release candidates

**Status:** accepted. Built under [#362](https://github.com/klexical/openRS_/issues/362) (version rule and release
check), [#363](https://github.com/klexical/openRS_/issues/363) (the scheduled workflow) and
[#364](https://github.com/klexical/openRS_/issues/364) (the updater), with the rc.8 updater bridge from
[#322](https://github.com/klexical/openRS_/issues/322).

The words Stable, Release candidate, Nightly, Stable channel and Beta channel are used as the Releases section of
[GLOSSARY.md on staging](https://github.com/klexical/openRS_/blob/staging/GLOSSARY.md) defines them.

## Context

Through the 2.2.7 cycle, every test build for the Beta channel was a release candidate (rc.1 to rc.8). Each one was cut
by hand: a version bump with a literal versionCode, release notes written for it, a tag, and the maintainer's approval
of that one release. A fix merged into `staging` reached Beta users only when the next candidate was cut, so testers
ran builds that were behind `staging`, and every candidate cost a round of release work apart from the changes it
carried. These are the costs the change removes.

## Decision

1. **rc.8 (versionCode 39) is the last release candidate.** It carries a small updater change so that it can find the
   nightlies that follow it (#322). After it, Beta gets nightlies.
2. **A nightly is built by itself, at most once a day.** A scheduled workflow builds `staging`'s tip, and only when
   `android/` has changed since the last published nightly. It runs the build, tests and lint, signs the APK, attests it
   and passes a launch check on an emulator. A nightly is never published without a passed launch check, and the check
   has no advisory mode.
3. **It is published without an approval of its own.** The approval moves from the release to the merge: a change a
   user would notice needs the maintainer's approval before it merges into `staging`, and so does any change to the
   pipeline itself. While publishing is on, an edit to the publish, clean-up or gate script is approved like a release.
   Stable releases keep their approval per release.
4. **It goes to the existing Beta channel.** There is no third channel. A nightly is a pre-release, which GitHub never
   marks Latest, and the Stable channel only reads Latest, so it never offers a nightly.
5. **Its name and versionCode come from the run's date in UTC.** The name is `<base>-nightly.YYYYMMDD` (for example
   `2.2.7-nightly.20261201`, tag `android-v2.2.7-nightly.20261201`). Within one base it sorts after every release
   candidate and before the stable. The versionCode is `(YYYYMMDD − 20000000) × 10` (20261201 gives 2612010), so every
   nightly is above rc.8's 39 and the slots 1 to 9 of each date are free for other builds.
6. **Stable is cut as before, with a hand-typed versionCode in slot 9.** The cut keeps the maintainer's approval,
   merges `staging` into `main` and tags `main`. At the cut the stable's versionCode is entered by hand as a literal in
   `android/app/build.gradle.kts`: the code of that date's nightly plus 9. That puts it above its own date's nightly and
   below the next date's. `android/scripts/verify-release.sh` refuses a stable outside its date's slot and any release
   whose code is not above every earlier tag. Right after the stable, the base moves on (2.2.8), so the next nightlies are
   named on the new base and the Beta channel keeps offering them.
7. **Notes and the changelog change shape.** A nightly's release notes are generated from the pull requests merged
   since the previous nightly, with the APK's SHA-256 and a link to `android/CHANGELOG.md`. From rc.8 on, the changelog
   collects entries in sections marked "(nightlies)", with no per-night dates.
8. **Nightlies are kept, and a bad one is taken back by hand.** No nightly is ever deleted automatically. A faulty
   nightly goes back to a draft on the maintainer's word, with its APK removed and its tag and notes kept for the record;
   the fix travels through `staging`, and the next night's build replaces it. What the workflow does delete is a failed
   run's own draft and its tag, which were never published.

## Consequences

What was traded away:

- **An approval per release.** Nobody looks at a given nightly before Beta users are offered it. Its content is what
  was approved and merged into `staging`, and its checks are the pipeline's: tests, lint, signature, attestation and the
  launch check. A passed launch check shows only that the app installs, starts and keeps running; it says nothing about
  any screen or reading.
- **A car check per build.** A nightly is not checked at the car before it is published. Checks at the car now run on
  their own schedule, and a stable release still waits for them.
- **Hand-written notes per test build.** Nightly notes are a generated list of pull requests; the changelog stays the
  authoritative record.

What was gained:

- Beta users get what `staging` holds by the next nightly, and a fix reaches them without anyone cutting a release.
- A test build no longer costs a round of release work: no version bump, no notes and no tag by hand.

What has to be kept in mind:

- **Two orderings have to rise together.** Android installs by versionCode; the in-app updater offers by release name.
  If the base were not moved on after a stable, later nightlies would carry a higher versionCode but a lower name than the
  stable: they would install, yet Beta users on the stable would never be offered one.
- **The pipeline is now part of every release.** A defect in the publish, clean-up or gate script can publish a bad
  build or leave a stray draft or tag, which is why those scripts are tested against a fake GitHub client and why their
  changes carry the maintainer's approval.
- **versionCodes jump** from 39 to about 2.6 million. Android accepts this, and the codes stay far below the largest
  value Google Play allows.

## Options not taken

- **Keep cutting release candidates.** Not taken: it would keep the two costs described in Context.
- **A separate Nightly channel.** Rejected: the Beta channel already offers the newest release of any kind.
- **Removing old nightlies automatically.** Rejected: published nightlies are kept, and even a taken-back one keeps its
  tag and notes as the record.
- **An advisory launch check** that reports but does not block. Rejected: a nightly that cannot start is never
  published. The check is repeated once in the same run only when the emulator itself disappears, never after the app
  failed to start.

## Where it is enforced

On `staging`, where the app is built:

- [`android/app/build.gradle.kts`](https://github.com/klexical/openRS_/blob/staging/android/app/build.gradle.kts):
  `-PnightlyDate=YYYYMMDD` makes a nightly; an empty or invalid date, or one after the build's UTC date, is refused with
  the reason; without it the build is the literal release in the file.
- [`android/scripts/verify-release.sh`](https://github.com/klexical/openRS_/blob/staging/android/scripts/verify-release.sh)
  and its test script: tag, name and file name agree; a nightly's code follows from its date; a stable sits in slot 9
  of its date.
- [`.github/workflows/nightly.yml`](https://github.com/klexical/openRS_/blob/staging/.github/workflows/nightly.yml)
  (identical on `main` and `staging`): the gate, checks, release recipe, launch check, attestation, publish and clean-up.
