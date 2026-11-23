# Vendored skills

The skill folders here are unmodified copies from
[mattpocock/skills](https://github.com/mattpocock/skills) at commit
`abeeaf95660ba9e8b7c753eb38f441ca707a8823` (2026-11-19), MIT licensed. The
licence travels with them in `LICENSE-mattpocock-skills`.

Taken: the 27 skills that the upstream plugin manifest (`.claude-plugin/plugin.json`)
ships, plus `chief-of-staff` from upstream `skills/in-progress/` (beta). Left out:
the other beta skills and the `misc/` bucket, which do not fit this repository.
Also left out: each skill's `agents/openai.yaml`, which only Codex reads.

## The project's rules win

These skills were written for a generic repository. When a skill's steps
disagree with `CLAUDE.md`, `.claude/rules/`, `CODING_STANDARDS.md` or the
project instructions, follow the project. In particular:

- **Commits.** Commits keep the project's commit identity and carry no
  attribution lines. History is never amended, rebased or force-pushed after a
  review has read it. This applies to `implement`, `implement-spec` and
  `prototype`.
- **GitHub writes.** `triage`, `to-spec`, `to-tickets`, `wayfinder` and
  `setup-matt-pocock-skills` create, comment on, label and close issues. In this
  project only the coordinator does issue writes, merges and comments. A thread
  that runs one of these skills writes the issue text as a draft in its report
  and does not post it.
- **`setup-matt-pocock-skills`** has not been run for this repository. Its
  output edits `CLAUDE.md` and writes under `docs/agents/`. Running it is a
  separate change, with its own review.
- **Privacy.** Anything a skill writes, such as specs, tickets, handoff docs,
  wiki pages, research notes or HTML reports, follows the privacy section of the
  project instructions.
- **`code-review`** has the same name as a built-in skill. Name the one you
  mean.

## Updating

To update, copy the same folders from a newer upstream commit and change the
commit above. Do not edit the skill files here: keep local rules in this README
or in the project's own rule files.
