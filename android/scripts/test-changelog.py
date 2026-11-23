#!/usr/bin/env python3
"""test-changelog.py — checks changelog.py: a fold of fragments taken from the real android/CHANGELOG.md gives it back
byte for byte; fragments are ordered by issue number, then slug; a missing (nightlies) section is made in the order
Added, Fixed, Changed, Removed; a malformed fragment is refused with its reason; on a pull request (--base) an entry
added straight into a (nightlies) section is refused unless a deleted fragment held it, and only warned about
(--pr-head) when the pull request began before the switch to fragments.
Run: python3 -I android/scripts/test-changelog.py (CI's changelog job runs it)."""
import os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "changelog.py")
REAL = os.path.join(os.path.dirname(HERE), "CHANGELOG.md")
URL = "https://github.com/klexical/openRS_/issues/"
POINTER = ("> Entries not yet folded in are one file each under [changelog.d/](changelog.d/), at the same commit; "
           "stable folds them in (`android/scripts/changelog.py fold`).")
failures = 0; ran = 0


def run(root, *args):
    r = subprocess.run([sys.executable, "-I", TOOL, "--root", root] + list(args), capture_output=True, text=True)
    return r.returncode, r.stdout, r.stderr


def check(name, ok, why=""):
    global failures, ran
    ran += 1
    if ok:
        print(f"ok   {name}")
    else:
        failures += 1
        print(f"FAIL {name}{': ' + why if why else ''}")


def entry(n, title, text="What changed, and why."):
    return f"- **{title}** — {text} ([#{n}]({URL}{n}))"


def tree(changelog, frags=None):
    d = tempfile.mkdtemp(prefix="changelog-test-")
    with open(os.path.join(d, "CHANGELOG.md"), "w", encoding="utf-8", newline="") as f:
        f.write(changelog)
    os.makedirs(os.path.join(d, "changelog.d"))
    with open(os.path.join(d, "changelog.d", "README.md"), "w") as f:
        f.write("# Changelog fragments\n")
    for rel, text in (frags or {}).items():
        p = os.path.join(d, "changelog.d", rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return d


def read(d, rel="CHANGELOG.md"):
    with open(os.path.join(d, rel), encoding="utf-8", newline="") as f:
        return f.read()


# 1. The real changelog (its pointer line dropped, as a fold drops it): the last entry of each (nightlies) section becomes
#    a fragment, and the pointer goes back under the heading; print and fold give that file back byte for byte, and fold
#    deletes the fragments it took.
real = open(REAL, encoding="utf-8", newline="").read()
lines = real.split("\n")
head = next(i for i, l in enumerate(lines) if l.startswith("## ["))
if lines[head + 2].startswith("> Entries not yet folded in"):
    del lines[head + 2:head + 4]                       # the file as stable folds it: no pointer
real = "\n".join(lines)
cut, frags = list(lines), {}
for title in ("Changed", "Fixed", "Added"):            # bottom-up, so earlier indexes stay valid
    at = cut.index(f"### {title} (nightlies)")
    last = at + 1
    while last + 1 < len(cut) and cut[last + 1].startswith("- "):
        last += 1
    n = re.search(r"\[#([0-9]+)\]", cut[last]).group(1)
    frags[f"{title.lower()}/{n}-taken-from-the-real-file.md"] = cut[last] + "\n"
    del cut[last]
assert cut[head + 1] == "", "the real file has a blank line under its version heading"
cut[head + 2:head + 2] = [POINTER, ""]
d = tree("\n".join(cut), frags)
rc, out, err = run(d, "check")
check("the real changelog with three fragments and the pointer passes check", rc == 0, out + err)
rc, out, _ = run(d, "print")
check("print gives the real changelog back byte for byte", rc == 0 and out == real,
      f"rc {rc}, {len(out)} vs {len(real)} characters")
check("print changes no file", read(d) == "\n".join(cut) and len(os.listdir(os.path.join(d, "changelog.d"))) == 4)
rc, out, _ = run(d, "fold")
check("fold writes the real changelog byte for byte", rc == 0 and read(d) == real)
check("fold deletes the fragments and their empty folders, and keeps the README",
      sorted(os.listdir(os.path.join(d, "changelog.d"))) == ["README.md"], str(os.listdir(os.path.join(d, "changelog.d"))))
rc, out, _ = run(d, "print")
check("with no fragment left, print is the changelog unchanged", rc == 0 and out == real)
shutil.rmtree(d)

# 2. Order inside a section, and sections that are missing.
BASE = "\n".join([
    "# Changelog", "", "Intro.", "", "---", "", "## [v9.9.9] — unreleased", "", POINTER, "",
    "### Fixed (nightlies)", entry(300, "Old fix"), "",
    "### Fixed (rc.8 — something)", entry(5, "Older fix"), "",
    "---", "", "## [v9.9.8] — 2026-01-01", "", "### Added", entry(1, "Ancient"), ""])
d = tree(BASE, {
    "fixed/42-b.md": entry(42, "Fix 42 b") + "\n",
    "fixed/42-a.md": entry(42, "Fix 42 a") + "\n" + entry(42, "Fix 42 a, second line") + "\n",
    "fixed/7-z.md": entry(7, "Fix 7") + "\n",
    "removed/9-gone.md": entry(9, "Gone") + "\n",
    "added/11-new.md": entry(11, "New") + "\n",
    "changed/10-other.md": entry(10, "Other", "Also [#3](" + URL + "3).") + "\n",
})
rc, out, err = run(d, "print")
want = "\n".join([
    "# Changelog", "", "Intro.", "", "---", "", "## [v9.9.9] — unreleased", "",
    "### Added (nightlies)", entry(11, "New"), "",
    "### Fixed (nightlies)", entry(300, "Old fix"), entry(7, "Fix 7"), entry(42, "Fix 42 a"),
    entry(42, "Fix 42 a, second line"), entry(42, "Fix 42 b"), "",
    "### Changed (nightlies)", entry(10, "Other", "Also [#3](" + URL + "3)."), "",
    "### Removed (nightlies)", entry(9, "Gone"), "",
    "### Fixed (rc.8 — something)", entry(5, "Older fix"), "",
    "---", "", "## [v9.9.8] — 2026-01-01", "", "### Added", entry(1, "Ancient"), ""])
check("fragments after the section's entries, by issue number then slug; missing sections made in order",
      rc == 0 and out == want, err + "\n" + out)
shutil.rmtree(d)

EMPTY = "# Changelog\n\n---\n\n## [v9.9.9] — unreleased\n\n---\n\n## [v9.9.8] — 2026-01-01\n\n### Added\n" + entry(1, "A") + "\n"
d = tree(EMPTY, {"fixed/2-x.md": entry(2, "X") + "\n"})
rc, out, err = run(d, "print")
check("a version heading with no section yet gets its first (nightlies) section",
      rc == 0 and out == EMPTY.replace("unreleased\n\n---", "unreleased\n\n### Fixed (nightlies)\n" + entry(2, "X") + "\n\n---"),
      err + out)
shutil.rmtree(d)

d = tree("# Changelog\n\nNo versions yet.\n", {"fixed/2-x.md": entry(2, "X") + "\n"})
rc, out, _ = run(d, "check")
check("a changelog with no version heading is refused", rc == 1 and "no version heading" in out, out)
shutil.rmtree(d)

# 3. Malformed fragments: each refused, with its reason.
BAD = {
    "fixed/0042-x.md": (entry(42, "X") + "\n", "the name is not"),
    "fixed/42_x.md": (entry(42, "X") + "\n", "the name is not"),
    "fixed/42-Upper.md": (entry(42, "X") + "\n", "the name is not"),
    "fixed/42-x.txt": (entry(42, "X") + "\n", "the name is not"),
    "improved/42-x.md": (entry(42, "X") + "\n", "not a section folder"),
    "notes.md": ("text\n", "not a section folder"),
    "fixed/42-empty.md": ("", "empty"),
    "fixed/42-nl.md": (entry(42, "X"), "no newline"),
    "fixed/42-cr.md": (entry(42, "X") + "\r\n", "carriage return"),
    "fixed/42-blank.md": (entry(42, "X") + "\n\n" + entry(42, "Y") + "\n", "blank line"),
    "fixed/42-plain.md": ("Fixed a thing. ([#42](" + URL + "42))\n", "not an entry line"),
    "fixed/42-dash.md": ("- **X** - hyphen, not the dash. ([#42](" + URL + "42))\n", "not an entry line"),
    "fixed/42-space.md": (entry(42, "X") + " \n", "trailing space"),
    "fixed/42-url.md": ("- **X** — y. ([#42](https://github.com/someone/else/issues/42))\n", "expected"),
    "fixed/42-mismatch.md": ("- **X** — y. ([#42](" + URL + "43))\n", "expected"),
    "fixed/42-bare.md": (entry(42, "X") + " (#7)\n", "bare"),
    "fixed/42-fixes.md": ("- **X** — Fixes #42. ([#42](" + URL + "42))\n", "bare"),
    "fixed/42-other.md": (entry(41, "X") + "\n", "no link to issue #42"),
}
for rel, (text, reason) in BAD.items():
    d = tree(BASE, {rel: text})
    rc, out, _ = run(d, "check")
    check(f"refused: changelog.d/{rel} ({reason})", rc == 1 and reason in out and read(d) == BASE, out.strip())
    rc2, _, _ = run(d, "fold")
    check(f"fold changes nothing when changelog.d/{rel} is refused", rc2 == 1 and read(d) == BASE)
    shutil.rmtree(d)

rc, _, _ = run(tempfile.gettempdir(), "nonsense")
check("an unknown command is a usage error", rc == 2)
rc, _, _ = run(tempfile.gettempdir(), "print", "--base", "HEAD")
check("--base with print is a usage error", rc == 2)


# 4. --base: entries added straight into a (nightlies) section are refused unless a deleted fragment held them.
def git(d, *a):
    subprocess.run(["git", "-C", d] + list(a), check=True, capture_output=True,
                   env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
                        "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"})


def repo(frags):
    top = tempfile.mkdtemp(prefix="changelog-git-")
    d = os.path.join(top, "android")
    shutil.copytree(tree(BASE, frags), d)
    git(top, "init", "-q"); git(top, "add", "-A"); git(top, "commit", "-q", "-m", "base")
    return top, d


def edit(d, old, new):
    with open(os.path.join(d, "CHANGELOG.md"), "r+", encoding="utf-8", newline="") as f:
        t = f.read(); f.seek(0); f.truncate(); f.write(t.replace(old, new, 1))


top, d = repo({"fixed/42-x.md": entry(42, "Fix 42") + "\n"})
rc, out, _ = run(d, "check", "--base", "HEAD")
check("--base: no change since the base passes", rc == 0, out)
edit(d, entry(300, "Old fix") + "\n", entry(300, "Old fix") + "\n" + entry(77, "Typed straight in") + "\n")
rc, out, _ = run(d, "check", "--base", "HEAD")
check("--base: an entry typed straight into Fixed (nightlies) is refused, naming the fragment folder",
      rc == 1 and "changelog.d/fixed/" in out and "CHANGELOG.md:13" in out, out)
git(top, "checkout", "-q", "--", ".")
edit(d, entry(5, "Older fix") + "\n", entry(5, "Older fix") + "\n" + entry(78, "A late rc.8 note") + "\n")
rc, out, _ = run(d, "check", "--base", "HEAD")
check("--base: a line added outside the (nightlies) sections passes", rc == 0, out)
git(top, "checkout", "-q", "--", ".")
edit(d, entry(300, "Old fix") + "\n", entry(300, "Old fix") + "\n" + entry(42, "Fix 42") + "\n")
rc, out, _ = run(d, "check", "--base", "HEAD")
check("--base: a copy of a fragment that is still there is refused", rc == 1 and "CHANGELOG.md:13" in out, out)
git(top, "checkout", "-q", "--", ".")
rc, out, _ = run(d, "fold")
rc2, out2, _ = run(d, "check", "--base", "HEAD")
check("--base: a fold (fragments deleted, their lines added) passes", rc == 0 and rc2 == 0, out + out2)
edit(d, entry(42, "Fix 42") + "\n", entry(42, "Fix 42") + "\n" + entry(79, "Slipped in with the fold") + "\n")
rc, out, _ = run(d, "check", "--base", "HEAD")
check("--base: a line slipped in beside a fold is still refused", rc == 1 and "CHANGELOG.md:" in out, out)
rc, out, _ = run(d, "check", "--base", "no-such-revision")
check("--base: an unknown revision is refused, not passed", rc == 1 and "git" in out, out)
shutil.rmtree(top)

# 5. --pr-head: a pull request that began before the switch (its merge base has no changelog.d/README.md) is warned
#    about a typed entry and passes; one that began after it is refused. The work tree is the merge commit, as in CI.
top, d = repo({})
os.remove(os.path.join(d, "changelog.d", "README.md"))
git(top, "add", "-A"); git(top, "commit", "-q", "-m", "before the switch")
git(top, "branch", "-q", "old-pr")
with open(os.path.join(d, "changelog.d", "README.md"), "w") as f:
    f.write("# Changelog fragments\n")
git(top, "add", "-A"); git(top, "commit", "-q", "-m", "the switch")
git(top, "branch", "-q", "new-pr")
TYPED = (entry(300, "Old fix") + "\n", entry(300, "Old fix") + "\n" + entry(77, "Typed straight in") + "\n")
for branch in ("old-pr", "new-pr"):
    git(top, "checkout", "-q", branch); edit(d, *TYPED); git(top, "commit", "-q", "-am", "typed entry")
main_branch = subprocess.run(["git", "-C", top, "branch", "--format=%(refname:short)"], capture_output=True,
                             text=True).stdout.split()
main_branch = next(b for b in main_branch if b not in ("old-pr", "new-pr"))
for branch, want_rc in (("old-pr", 0), ("new-pr", 1)):
    git(top, "checkout", "-q", main_branch); git(top, "checkout", "-q", "--detach")
    git(top, "merge", "-q", "--no-ff", "--no-edit", branch)
    rc, out, _ = run(d, "check", "--base", "HEAD^1", "--pr-head", "HEAD^2")
    if branch == "old-pr":
        check("--pr-head: a typed entry from a pull request begun before the switch is a warning, and passes",
              rc == 0 and "::warning::CHANGELOG.md:13" in out and "refused" not in out, out)
    else:
        check("--pr-head: a typed entry from a pull request begun after the switch is refused",
              rc == 1 and "refused: CHANGELOG.md:13" in out and "::warning::" not in out, out)
git(top, "checkout", "-q", "old-pr"); os.makedirs(os.path.join(d, "changelog.d", "fixed"), exist_ok=True)
with open(os.path.join(d, "changelog.d", "fixed", "5-x.md"), "w") as f:
    f.write("not an entry\n")
git(top, "add", "-A"); git(top, "commit", "-q", "-m", "bad fragment")
git(top, "checkout", "-q", main_branch); git(top, "checkout", "-q", "--detach"); git(top, "merge", "-q", "--no-edit", "old-pr")
rc, out, _ = run(d, "check", "--base", "HEAD^1", "--pr-head", "HEAD^2")
check("--pr-head: a malformed fragment is still refused on a pull request begun before the switch",
      rc == 1 and "changelog.d/fixed/5-x.md" in out, out)
rc, _, _ = run(d, "check", "--pr-head", "HEAD^2")
check("--pr-head without --base is a usage error", rc == 2)
shutil.rmtree(top)

print(f"{ran - failures} of {ran} passed")
sys.exit(1 if failures else 0)
