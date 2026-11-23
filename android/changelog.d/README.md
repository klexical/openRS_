# Changelog fragments

Each changelog entry for `android/CHANGELOG.md` is written here as its own file, so two pull requests never edit the same
lines of the changelog. Stable folds them into `CHANGELOG.md`. The rules are in `.claude/rules/build-release.md`,
"Changelog Format".

- **Where:** `android/changelog.d/<section>/<issue>-<slug>.md`. `<section>` is `added`, `fixed`, `changed` or `removed`,
  the `### … (nightlies)` section the entry belongs to. `<issue>` is the issue the entry links, with no leading zero.
  `<slug>` is a few lower-case words joined by `-`, for example `fixed/551-spliced-frames.md`.
- **What:** one or more entry lines in the changelog's own format, each ending in a newline, with no blank line:

  ```
  - **Short title** — What changed and why. ([#551](https://github.com/klexical/openRS_/issues/551))
  ```

  One file may hold several entries for the same issue and section. An entry for another section goes in that
  section's folder.
- **Check:** `python3 android/scripts/changelog.py check` checks every fragment. `python3 android/scripts/changelog.py print`
  prints the changelog as stable will fold it, with no file changed.
- **Fold:** `python3 android/scripts/changelog.py fold` writes every fragment into `CHANGELOG.md` and deletes it. Only the
  stable change on staging runs it.

Within a section, fragments are folded in order of issue number, then slug, after the entries already in the changelog.
A fragment that has shipped in a nightly is not edited, the same as a shipped entry.
