#!/usr/bin/env python3
"""changelog.py — per-PR changelog fragments for android/CHANGELOG.md: one file per entry under android/changelog.d/,
so two pull requests never edit the same lines; stable folds them into the changelog.

A fragment is android/changelog.d/<section>/<N>-<slug>.md:
  - <section> is added, fixed, changed or removed: the `### Added (nightlies)` ... section it belongs to;
  - <N> is the issue the entry links (a link to it must be in the file), <slug> lower-case words joined by "-";
  - its text is one or more entry lines in the changelog's own format (.claude/rules/build-release.md, "Changelog
    Format"), each ending in a newline, with no blank line, carriage return or trailing space.
android/changelog.d/README.md is the only other file there.

  changelog.py check [--base REV]
      Every fragment is well formed and the changelog takes them. With --base (CI, on a pull request into staging):
      an entry line the working tree adds to a `### <Section> (nightlies)` section of CHANGELOG.md since REV must come
      from a fragment deleted since REV (a fold); any other is refused, with the fragment path it belongs in.
  changelog.py print
      The changelog with every fragment folded in, to stdout; the files are not changed.
  changelog.py fold
      Writes that changelog to CHANGELOG.md and deletes the fragments it took (the stable change; the caller commits).

Folding: the fragments of a section, ordered by issue number and then slug, go after the last line of that section
under the first version heading (`## [`); a section that is missing is made, in the order Added, Fixed, Changed,
Removed, after the version's other (nightlies) sections. The pointer line under the heading (it starts with POINTER)
and the blank line after it are dropped. So a fold is byte for byte what appending the same lines by hand gives.
Exit status: 0 good, 1 a refusal (each reason on its own line), 2 a usage error. --root <dir> names the android/
folder (default: this script's parent's parent). Python standard library; git only for --base."""
import os, re, subprocess, sys

SECTIONS = ["added", "fixed", "changed", "removed"]
TITLES = {s: s.capitalize() for s in SECTIONS}
POINTER = "> Entries not yet folded in"
NAME = re.compile(r"^([1-9][0-9]{0,5})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$")
ENTRY = re.compile(r"^- \*\*[^*].*?\*\* — \S")
LINK = re.compile(r"\[#([0-9]+)\]\(([^)\s]*)\)")
ISSUE_URL = "https://github.com/klexical/openRS_/issues/"
BARE = re.compile(r"\(#[0-9]+\)|\b(?:[Ff]ixes|[Cc]loses|[Rr]esolves) #[0-9]+")
NIGHTLIES = re.compile(r"^### (Added|Fixed|Changed|Removed) \(nightlies\)\s*$")


class Refused(Exception):
    pass


def entry_problems(line, where):
    """Why one entry line does not follow the changelog format (an empty list when it does)."""
    out = []
    if not ENTRY.match(line):
        out.append(f"{where}: not an entry line ('- **Short title** — Description …')")
    if line != line.rstrip():
        out.append(f"{where}: trailing space")
    for n, url in LINK.findall(line):
        if url != ISSUE_URL + n:
            out.append(f"{where}: [#{n}] links {url!r}, expected {ISSUE_URL + n}")
    if BARE.search(line):
        out.append(f"{where}: a bare (#N) or closing-keyword reference; use [#N]({ISSUE_URL}N)")
    return out


def read_fragments(root):
    """[(section, number, slug, path, lines)] for every fragment, and the problems found."""
    base = os.path.join(root, "changelog.d")
    frags, problems = [], []
    if not os.path.isdir(base):
        return frags, problems
    for name in sorted(os.listdir(base)):
        p = os.path.join(base, name)
        if name == "README.md" and os.path.isfile(p):
            continue
        if name not in SECTIONS or not os.path.isdir(p):
            problems.append(f"changelog.d/{name}: not a section folder ({', '.join(SECTIONS)}) or README.md")
            continue
        for fn in sorted(os.listdir(p)):
            rel = f"changelog.d/{name}/{fn}"
            m = NAME.match(fn)
            if not m or not os.path.isfile(os.path.join(p, fn)):
                problems.append(f"{rel}: the name is not <issue number>-<lower-case-slug>.md")
                continue
            with open(os.path.join(p, fn), "rb") as f:
                raw = f.read()
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                problems.append(f"{rel}: not UTF-8"); continue
            if not text:
                problems.append(f"{rel}: empty"); continue
            if "\r" in text:
                problems.append(f"{rel}: carriage return"); continue
            if not text.endswith("\n"):
                problems.append(f"{rel}: the last line has no newline"); continue
            lines = text[:-1].split("\n")
            for i, line in enumerate(lines, 1):
                if not line.strip():
                    problems.append(f"{rel}:{i}: blank line")
                else:
                    problems += entry_problems(line, f"{rel}:{i}")
            if not any(n == m.group(1) for line in lines for n, _ in LINK.findall(line)):
                problems.append(f"{rel}: no link to issue #{m.group(1)}, the number in its name")
            frags.append((name, int(m.group(1)), m.group(2), rel, lines))
    return frags, problems


def assemble(text, frags):
    """The changelog text with the fragments folded in and the pointer dropped (see the module's docstring)."""
    lines = text.split("\n")
    head = next((i for i, l in enumerate(lines) if l.startswith("## [")), None)
    if head is None:
        raise Refused("CHANGELOG.md: no version heading ('## [') to fold into")
    end = next((i for i in range(head + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    while end > head + 1 and lines[end - 1] in ("", "---"):
        end -= 1                                        # the block ends at its last line of text
    if head + 2 < end and lines[head + 2].startswith(POINTER):
        drop = 2 if head + 3 < len(lines) and lines[head + 3] == "" else 1
        del lines[head + 2:head + 2 + drop]
        end -= drop
    for sec in SECTIONS:
        mine = sorted((f for f in frags if f[0] == sec), key=lambda f: (f[1], f[2]))
        if not mine:
            continue
        add = [l for f in mine for l in f[4]]
        title = f"### {TITLES[sec]} (nightlies)"
        heads = [i for i in range(head + 1, end) if lines[i].startswith("### ")]
        if title in [lines[i] for i in heads]:
            at = heads[[lines[i] for i in heads].index(title)]
            stop = next((i for i in heads if i > at), end)
            while stop > at + 1 and lines[stop - 1] == "":
                stop -= 1
            lines[stop:stop] = add
            end += len(add)
            continue
        later = [i for i in heads if NIGHTLIES.match(lines[i])
                 and SECTIONS.index(NIGHTLIES.match(lines[i]).group(1).lower()) > SECTIONS.index(sec)]
        if later:
            at = later[0]
        else:
            nightly = [i for i in heads if NIGHTLIES.match(lines[i])]
            after = [i for i in heads if nightly and i > nightly[-1]]
            at = after[0] if after else (heads[0] if heads and not nightly else end)
        if at == end:
            block = ["", title] + add
        else:
            block = [title] + add + [""]
        lines[at:at] = block
        end += len(block)
    return "\n".join(lines)


def git(root, *args):
    r = subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True)
    if r.returncode:
        raise Refused(f"git {' '.join(args[:2])} failed: {r.stderr.strip().splitlines()[-1] if r.stderr.strip() else r.returncode}")
    return r.stdout


def direct_edits(root, base):
    """Entry lines added to a (nightlies) section since base that no deleted fragment held."""
    folded = set()
    for rel in git(root, "diff", "--relative", "--no-renames", "--name-only", "--diff-filter=D", base, "--",
                   "changelog.d").split("\n"):
        if rel and NAME.match(os.path.basename(rel)):
            folded.update(git(root, "show", f"{base}:./{rel}").split("\n"))
    added = []
    for line in git(root, "diff", "--relative", "--no-renames", "-U0", base, "--", "CHANGELOG.md").split("\n"):
        m = re.match(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", line)
        if m:
            start, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
            added += range(start, start + count)
    if not added:
        return []
    with open(os.path.join(root, "CHANGELOG.md"), encoding="utf-8") as f:
        lines = f.read().split("\n")
    out, section = [], None
    wanted = set(added)
    for i, l in enumerate(lines, 1):
        if l.startswith("#"):
            m = NIGHTLIES.match(l)
            section = m.group(1).lower() if m else None
        elif i in wanted and section and l.startswith("- ") and l not in folded:
            out.append(f"CHANGELOG.md:{i}: an entry added straight into '### {TITLES[section]} (nightlies)'; "
                       f"write it as android/changelog.d/{section}/<issue>-<slug>.md instead")
    return out


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    args = list(argv)
    if "--root" in args:
        i = args.index("--root"); root = os.path.abspath(args[i + 1]); del args[i:i + 2]
    base = None
    if "--base" in args:
        i = args.index("--base")
        if i + 1 >= len(args):
            print("usage: --base needs a revision", file=sys.stderr); return 2
        base = args[i + 1]; del args[i:i + 2]
    if len(args) != 1 or args[0] not in ("check", "print", "fold") or (base and args[0] != "check"):
        print("usage: changelog.py [--root DIR] check [--base REV] | print | fold", file=sys.stderr)
        return 2
    path = os.path.join(root, "CHANGELOG.md")
    try:
        frags, problems = read_fragments(root)
        with open(path, encoding="utf-8", newline="") as f:
            text = f.read()
        out = assemble(text, frags)
        if base:
            problems += direct_edits(root, base)
    except Refused as e:
        problems = locals().get("problems", []) + [str(e)]
        out = None
    if problems:
        for p in problems:
            print(f"refused: {p}")
        return 1
    if args[0] == "check":
        print(f"changelog: {len(frags)} fragment(s), {sum(len(f[4]) for f in frags)} entr(y/ies), all well formed")
    elif args[0] == "print":
        sys.stdout.write(out)
    else:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(out)
        for f in frags:
            os.remove(os.path.join(root, f[3]))
        for sec in SECTIONS:
            d = os.path.join(root, "changelog.d", sec)
            if os.path.isdir(d) and not os.listdir(d):
                os.rmdir(d)
        print(f"changelog: folded {len(frags)} fragment(s) into CHANGELOG.md")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
