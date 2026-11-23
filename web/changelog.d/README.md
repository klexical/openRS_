# Changelog fragments (Sapphire)

Each entry for `web/CHANGELOG.md` is written here as its own file, so two pull requests never edit the same lines of the
changelog. The build folds them into the slice section at the top of the changelog, so the live site's What's new shows
them at once; the slice's last delivery writes them into `CHANGELOG.md` for good.

- **Where:** `web/changelog.d/<section>/<issue>-<slug>.md`. `<section>` is `added`, `fixed`, `changed` or `removed`,
  the `### …` list of the newest slice section the entry belongs to. `<issue>` is the issue the entry links, with no
  leading zero. `<slug>` is a few lower-case words joined by `-`, for example `fixed/416-range-reset.md`.
- **What:** one or more entry lines in the changelog's own format, each ending in a newline, with no blank line:

  ```
  - **Short title** — What changed and why. ([#416](https://github.com/klexical/openRS_/issues/416))
  ```

- **Check:** `npm test` checks every fragment (`src/lib/changelogFragments.test.ts`). From `web/`,
  `node scripts/changelog/changelog.mjs print` prints the changelog as the site folds it, with no file changed.
- **Fold:** `node scripts/changelog/changelog.mjs fold` writes every fragment into `CHANGELOG.md` and deletes it. A
  slice's last delivery runs it, before the next slice's section opens; a fragment left over then would land in the new
  section.

Within a list, fragments are folded in order of issue number, then slug, after the entries already in the changelog.
A fragment that has shipped on the live site is not edited, the same as a shipped entry.
