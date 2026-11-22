#!/usr/bin/env python3
"""demo-smoke-ui.py <dump.xml> <package> — reads one `uiautomator dump` for demo-smoke.sh and prints facts, one per line.

The facts use a fixed vocabulary only, so no text from the screen reaches the run's log:
  windows=<n>               the number of window roots (hierarchy children)
  ours=<n>                  the number of nodes of <package> (0: none of the app's windows is in the dump)
  others=<pkg,...>|none     the other packages with nodes in the dump (the system bars' own, a dialog of another app),
                            names of the form a.b.c only
  demo=<0|1>                1 when a node of <package> has the header's demo label as text or content-desc: "DEMO" or
                            "DEMO · <link>"
  whatsnew=<x> <y>|none     the centre of the What's New dialog's GOT IT button, when it is shown
  tab.<NAME>=<x> <y>        the centre of each bottom-nav tab found (content-desc "<NAME> tab" or "<NAME> tab, alert")
  selected=<NAME>|none      the tab whose node is selected (Compose's `selected` semantics), or none
  tabflags=<NAME>:<ssss>:<n> ...|none  each tab's selected, checked, checkable and focused attributes (1 true, 0 false,
                            - absent) and the number of nodes with its description, for a tab not read as selected
A dump that is not XML exits 2 with the parser's error class and position only (never the dump's text).
"""
import re
import sys
import xml.etree.ElementTree as ET

TABS = ("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")
FLAGS = ("selected", "checked", "checkable", "focused")
FLAG_CODE = {"true": "1", "false": "0", None: "-"}
TAB_DESC = re.compile(r"(%s) tab(, alert)?" % "|".join(TABS))
DEMO = re.compile("DEMO( \u00b7 [A-Z]{2,8})?")   # the pill: "DEMO", or "DEMO \u00b7 RCN" during LOSS
PKG = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+")
XML_FORBIDDEN = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f\ufffe\uffff]")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def centre(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    if not m:
        return None
    l, t, r, b = (int(v) for v in m.groups())
    if r <= l or b <= t:
        return None
    return "%d %d" % ((l + r) // 2, (t + b) // 2)


def main(argv):
    if len(argv) != 3:
        print("usage: demo-smoke-ui.py <dump.xml> <package>", file=sys.stderr)
        return 64
    path, package = argv[1], argv[2]
    try:
        with open(path, "rb") as f:
            raw = f.read().decode("utf-8", errors="replace")
    except OSError as e:
        print("unreadable dump (%s)" % type(e).__name__, file=sys.stderr)
        return 2
    # From the XML declaration or the root on (anything a shell printed before it is dropped), and without the
    # characters XML 1.0 forbids (a control character in a node's text would stop the parser)
    start = min([i for i in (raw.find("<?xml"), raw.find("<hierarchy")) if i >= 0], default=-1)
    if start < 0:
        print("unreadable dump (no XML in %d characters)" % len(raw), file=sys.stderr)
        return 2
    text = XML_FORBIDDEN.sub("", raw[start:])
    try:
        root = ET.fromstring(text)
    except ET.ParseError as e:
        line, col = e.position
        print("unreadable dump (ParseError at line %d column %d of %d characters)" % (line, col, len(text)), file=sys.stderr)
        return 2
    nodes = list(root.iter("node"))
    ours = sum(1 for n in nodes if n.get("package") == package)
    others = sorted({n.get("package", "") for n in nodes} - {package})
    others = [o if PKG.fullmatch(o) else "<other>" for o in others]
    demo = any(n.get("package") == package and (DEMO.fullmatch(n.get("text", "")) or DEMO.fullmatch(n.get("content-desc", "")))
               for n in nodes)
    whatsnew = "none"
    tabs = {}
    selected = "none"
    seen, flags = {}, {}
    for n in nodes:
        if whatsnew == "none" and n.get("text") == "GOT IT":
            # The button's own node when Compose merges the label into it; else the label's (inside the button)
            whatsnew = centre(n) or "none"
        m = TAB_DESC.fullmatch(n.get("content-desc", ""))
        if m:
            seen[m.group(1)] = seen.get(m.group(1), 0) + 1
        if m and m.group(1) not in tabs:
            flags[m.group(1)] = "".join(FLAG_CODE.get(n.get(a), "?") for a in FLAGS)
            c = centre(n)
            if c:
                tabs[m.group(1)] = c
            if n.get("selected") == "true":
                selected = m.group(1)
    print("windows=%d" % sum(1 for _ in root))
    print("ours=%d" % ours)
    print("others=%s" % (",".join(sorted(set(others))) or "none"))
    print("demo=%d" % (1 if demo else 0))
    print("whatsnew=%s" % whatsnew)
    for t in TABS:
        if t in tabs:
            print("tab.%s=%s" % (t, tabs[t]))
    print("selected=%s" % selected)
    # For a tab that is not read as selected: each tab's selected / checked / checkable / focused attributes (1 true,
    # 0 false, - absent) and how many nodes carry its description
    print("tabflags=%s" % (" ".join("%s:%s:%d" % (t, flags[t], seen[t]) for t in TABS if t in flags) or "none"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
