#!/usr/bin/env python3
"""demo-smoke-ui.py <dump.xml> <package> — reads one `uiautomator dump` for demo-smoke.sh and prints facts, one per line.

The facts use a fixed vocabulary only, so no text from the screen reaches the run's log:
  windows=<n>               the number of window roots (hierarchy children)
  ours=<n>                  the number of nodes of <package> (0: none of the app's windows is in the dump)
  others=<pkg,...>|none     the other packages with nodes in the dump (the system bars' own, a dialog of another app),
                            names of the form a.b.c only
  demo=<0|1>                1 when a node's text or content-desc is the header's demo label: "DEMO" or "DEMO · <link>"
  whatsnew=<x> <y>|none     the centre of the What's New dialog's GOT IT button, when it is shown
  tab.<NAME>=<x> <y>        the centre of each bottom-nav tab found (content-desc "<NAME> tab" or "<NAME> tab, alert")
  selected=<NAME>|none      the tab whose node is selected (Compose's `selected` semantics), or none
A dump that is not XML exits 2 with the parser's error class only.
"""
import re
import sys
import xml.etree.ElementTree as ET

TABS = ("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")
TAB_DESC = re.compile(r"(%s) tab(, alert)?" % "|".join(TABS))
DEMO = re.compile("DEMO( \u00b7 [A-Z]{2,8})?")   # the pill: "DEMO", or "DEMO \u00b7 RCN" during LOSS
PKG = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+")
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
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as e:
        print("unreadable dump (%s)" % type(e).__name__, file=sys.stderr)
        return 2
    nodes = list(root.iter("node"))
    ours = sum(1 for n in nodes if n.get("package") == package)
    others = sorted({n.get("package", "") for n in nodes} - {package})
    others = [o if PKG.fullmatch(o) else "<other>" for o in others]
    demo = any(DEMO.fullmatch(n.get("text", "")) or DEMO.fullmatch(n.get("content-desc", "")) for n in nodes)
    whatsnew = "none"
    tabs = {}
    selected = "none"
    for n in nodes:
        if whatsnew == "none" and n.get("text") == "GOT IT":
            # The button's own node when Compose merges the label into it; else the label's (inside the button)
            whatsnew = centre(n) or "none"
        m = TAB_DESC.fullmatch(n.get("content-desc", ""))
        if m and m.group(1) not in tabs:
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
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
