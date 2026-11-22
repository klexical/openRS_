#!/usr/bin/env python3
"""demo-smoke-ui.py <dump.xml> <package> — reads one `uiautomator dump` for demo-smoke.sh and prints facts, one per line.

The facts use a fixed vocabulary only, so no text from the screen reaches the run's log:
  windows=<n>               the number of windows: <window> elements of a `--windows` dump, else the hierarchy's children
  ours=<n>                  the number of nodes of <package> (0: none of the app's windows is in the dump)
  others=<pkg,...>|none     the other packages with nodes in the dump (the system bars' own, a dialog of another app),
                            names of the form a.b.c only
  demo=<0|1>                1 when a node of <package> has the header's demo label as text or content-desc: "DEMO" or
                            "DEMO · <link>"
  whatsnew=<x> <y>|none     the centre of the What's New dialog's GOT IT button, when it is shown
  tab.<NAME>=<x> <y>        the centre of each bottom-nav tab found (content-desc "<NAME> tab" or "<NAME> tab, alert")
  selected=<NAME>|none      the one tab whose node is selected (Compose's `selected` semantics); else, with all five
                            tabs found, the one tab not clickable (Compose exposes a selected tab so); or none
  selectedby=flag|clickable|none  which of the two read it
  tabtree=<NAME>:<node>><parent>><grandparent> ...|none  for a tab not read as selected: each of the three as
                            <flags>/<index>/<children>/<has description 0|1>/<bounds against the tab's node>
  tabflags=<NAME>:<sssss>:<s|p>:<n> ...|none  for a tab not read as selected: each tab's selected, checked,
                            checkable, focused and clickable attributes (1 true, 0 false, - absent), read from the
                            node itself (s) or its parent (p: the node is Compose's fake child), and the number of
                            nodes with its description
A dump that is not XML exits 2 with the parser's error class and position only (never the dump's text).
"""
import re
import sys
import xml.etree.ElementTree as ET

TABS = ("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE")
FLAGS = ("selected", "checked", "checkable", "focused", "clickable")
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


def box(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else None


def relation(outer, inner):
    """same / in (inner inside outer) / other, by bounds"""
    o, i = box(outer), box(inner)
    if o is None or i is None:
        return "none"
    if o == i:
        return "same"
    return "in" if o[0] <= i[0] and o[1] <= i[1] and i[2] <= o[2] and i[3] <= o[3] else "other"


def ancestry(node, parent, package):
    """For a tab not read as selected: the node and its two nearest ancestors, each as <flags>/<index>/<children>/
    <description 0|1>/<bounds against the node>, fixed vocabulary only"""
    out, cur = [], node
    for _ in range(3):
        if cur is None or cur.tag != "node":
            break
        kids = sum(1 for c in cur if c.tag == "node")
        mine = "" if cur.get("package") == package else "x"
        out.append("%s%s/%s/%d/%d/%s" % (mine, "".join(FLAG_CODE.get(cur.get(a), "?") for a in FLAGS),
                                        cur.get("index") if (cur.get("index") or "").isdigit() else "-", kids,
                                        1 if cur.get("content-desc") else 0, relation(cur, node)))
        cur = parent.get(cur)
    return ">".join(out)


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
    parent = {c: q for q in root.iter() for c in q}
    ours = sum(1 for n in nodes if n.get("package") == package)
    others = sorted({n.get("package", "") for n in nodes} - {package})
    others = [o if PKG.fullmatch(o) else "<other>" for o in others]
    demo = any(n.get("package") == package and (DEMO.fullmatch(n.get("text", "")) or DEMO.fullmatch(n.get("content-desc", "")))
               for n in nodes)
    whatsnew = "none"
    tabs = {}
    seen, flags, tabnode, tree = {}, {}, {}, {}
    for n in nodes:
        if n.get("package") != package:
            continue   # the tabs and What's New are the app's own nodes
        if whatsnew == "none" and n.get("text") == "GOT IT":
            # The button's own node when Compose merges the label into it; else the label's (inside the button)
            whatsnew = centre(n) or "none"
        m = TAB_DESC.fullmatch(n.get("content-desc", ""))
        if m:
            seen[m.group(1)] = seen.get(m.group(1), 0) + 1
        if m and m.group(1) not in tabs:
            # Compose puts a merging node's content description on a fake first child with the node's own bounds
            # (SemanticsNode.emitFakeNodes); the tab's state (selected, clickable) is then on that parent
            # (it is added as child 0, and its bounds can differ from the parent's touch bounds). A node that is itself
            # selected or clickable is the tab's own; else the parent is taken when the node is its first child and the
            # parent, of the app, has no description and holds the node's bounds
            q = parent.get(n)
            own = "true" in (n.get("selected"), n.get("clickable")) or not (
                q is not None and q.tag == "node" and q.get("package") == package and n.get("index") == "0"
                and not q.get("content-desc") and relation(q, n) in ("same", "in"))
            state = n if own else q
            tree[m.group(1)] = ancestry(n, parent, package)
            flags[m.group(1)] = "".join(FLAG_CODE.get(state.get(a), "?") for a in FLAGS) + (":s" if own else ":p")
            tabnode[m.group(1)] = state
            c = centre(n)
            if c:
                tabs[m.group(1)] = c
    print("windows=%d" % (sum(1 for _ in root.iter("window")) if root.tag == "displays" else sum(1 for _ in root)))
    print("ours=%d" % ours)
    print("others=%s" % (",".join(sorted(set(others))) or "none"))
    print("demo=%d" % (1 if demo else 0))
    print("whatsnew=%s" % whatsnew)
    for t in TABS:
        if t in tabs:
            print("tab.%s=%s" % (t, tabs[t]))
    # The selected tab: the one tab whose node is selected; else, with all five tabs found, the one tab that is not
    # clickable (Compose exposes a selected Role.Tab as not clickable, AndroidComposeViewAccessibilityDelegateCompat)
    selected, selectedby = "none", "none"
    by_flag = [t for t in TABS if t in tabnode and tabnode[t].get("selected") == "true"]
    not_clickable = [t for t in TABS if t in tabnode and tabnode[t].get("clickable") == "false"]
    if len(by_flag) == 1:
        selected, selectedby = by_flag[0], "flag"
    elif not by_flag and len(tabnode) == len(TABS) and len(not_clickable) == 1 and all(
            tabnode[t].get("clickable") == "true" for t in TABS if t != not_clickable[0]):
        selected, selectedby = not_clickable[0], "clickable"
    print("selected=%s" % selected)
    print("selectedby=%s" % selectedby)
    # For a tab that is not read as selected: each tab's selected / checked / checkable / focused / clickable attributes (1 true,
    # 0 false, - absent) and how many nodes carry its description
    print("tabflags=%s" % (" ".join("%s:%s:%d" % (t, flags[t], seen[t]) for t in TABS if t in flags) or "none"))
    print("tabtree=%s" % (" ".join("%s:%s" % (t, tree[t]) for t in TABS if t in tree) or "none"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
