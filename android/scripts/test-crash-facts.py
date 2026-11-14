#!/usr/bin/env python3
"""test-crash-facts.py — checks crash-facts.py (#363) on synthetic minidumps built here: only the signal, the thread's
name, module + offset of the crash and the fault address's class are printed; a key-shaped value planted in a memory
region, an annotation, the environment stream, a thread name, a module path, or as the fault address itself never
appears (QA's V1 / V2 / T1). Run: python3 -I android/scripts/test-crash-facts.py (test-launch-check.sh runs it)."""
import os, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "crash-facts.py")
KEY = "AIza" + "Q" * 35                       # a Maps-shaped value, built here so no key-shaped literal is in the file
MARKER = "PLANTED-MARKER-7731"
failures = 0; ran = 0


def mdstring(s):
    b = s.encode("utf-16-le"); return struct.pack("<I", len(b)) + b


def build(path, signal=11, fault=0, ip=None, thread_name="QEMU2 main loop", module_path="/opt/emu/lib64/libgfxstream.so",
          base=0x7F0000000000, size=0x100000, truncate=False, context_flags=0x0010000F):
    """A minidump with an exception stream, a module list, a thread-name stream, and three streams that must never be
    read: memory (the key and the marker), an annotation-like blob and a Linux environment stream (MD_LINUX_ENVIRON)."""
    blobs = []                                  # (stream type, bytes) placed after the header and the directory
    ctx = bytearray(0x4D0)
    struct.pack_into("<I", ctx, 0x30, context_flags)
    struct.pack_into("<Q", ctx, 0xF8, ip if ip is not None else base + 0x1234)
    # Placeholders: RVAs are known once the layout is fixed, so build in two passes
    secret = (KEY + " " + MARKER).encode()
    streams = [("exception", 6), ("modules", 4), ("threadnames", 24), ("memory", 5), ("annot", 0x4350000), ("environ", 0x47670007)]
    header_size = 32; dir_size = 12 * len(streams)
    out = bytearray(header_size + dir_size)
    def put(b):
        rva = len(out); out.extend(b); return rva
    ctx_rva = put(bytes(ctx))
    exc = struct.pack("<II", 4242, 0) + struct.pack("<IIQQII", signal, 1, 0, fault, 0, 0) + bytes(120) + struct.pack("<II", len(ctx), ctx_rva)
    name_rva = put(mdstring(module_path))
    mod = struct.pack("<I", 1) + struct.pack("<QIIII", base, size, 0, 0, name_rva) + bytes(108 - 24)
    tname_rva = put(mdstring(thread_name))
    tnames = struct.pack("<I", 1) + struct.pack("<IQ", 4242, tname_rva)
    mem = struct.pack("<I", 1) + struct.pack("<QII", 0x7FFE00000000, len(secret), 0)
    mem_rva = put(secret)
    mem = struct.pack("<I", 1) + struct.pack("<QII", 0x7FFE00000000, len(secret), mem_rva)
    annot = b"maps_key=" + secret
    environ = b"MAPS_API_KEY=" + secret + b"\0"
    body = {"exception": exc, "modules": mod, "threadnames": tnames, "memory": mem, "annot": annot, "environ": environ}
    dir_entries = b""
    for name, stype in streams:
        rva = put(body[name]); dir_entries += struct.pack("<III", stype, len(body[name]), rva)
    out[0:32] = struct.pack("<4sIIIIIQ", b"MDMP", 0xA793, len(streams), header_size, 0, 0, 0)
    out[header_size:header_size + dir_size] = dir_entries
    with open(path, "wb") as fh:
        fh.write(bytes(out[: len(out) // 3]) if truncate else bytes(out))


def run(*args):
    return subprocess.run([sys.executable, "-I", TOOL, *args], capture_output=True, text=True).stdout


def check(name, ok, out):
    global failures, ran
    ran += 1
    if ok: print(f"PASS {name}")
    else:
        failures += 1; print(f"FAIL {name}"); print("    " + out.replace("\n", "\n    "))


def no_secret(out):
    return KEY not in out and MARKER not in out and "QQQQ" not in out


def case(name, expect, **kw):
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "completed"))
        build(os.path.join(d, "completed", "3f2a.dmp"), **kw)
        out = run("decode", d)
        check(name, all(e in out for e in expect) and no_secret(out), out)
        return out


case("a null write (the hang detector's): SIGSEGV, the thread's name, module + offset, address 0",
     ["signal: 11 (SIGSEGV)", "crashing thread: QEMU2 main loop", "crashed in: libgfxstream.so + 0x1234", "fault address: 0 (null)"])
out = case("a small address prints its class, never its number", ["fault address: below 64 KiB (null + offset)"], fault=0x1F8)
check("  ... and no number for it", "0x1f8" not in out.lower() and "504" not in out, out)
out = case("an address inside a module prints the module, never its offset", ["fault address: inside libgfxstream.so"],
           fault=0x7F0000000000 + 0x4567)
check("  ... and no offset for it", "0x4567" not in out.lower(), out)
key_as_address = int.from_bytes(b"AIzaSyQQ", "little")
out = case("a key-shaped byte run as the fault address prints its class only", ["fault address: non-canonical"], fault=key_as_address)
check("  ... with no number of it", f"{key_as_address:x}" not in out.lower() and str(key_as_address) not in out, out)
case("an address outside every module", ["fault address: outside every module"], fault=0x7FFF00001000)
case("an instruction pointer outside every module", ["crashed in: outside every module"], ip=0x400000)
case("a thread name with characters outside the set is withheld", ["crashing thread: <name withheld>"], thread_name="main; " + KEY)
case("a thread name over 15 characters is withheld", ["crashing thread: <name withheld>"], thread_name="a-very-long-thread-name")
case("a module name with characters outside the set is withheld", ["crashed in: <name withheld> + 0x1234"],
     module_path="/tmp/" + MARKER + " " + KEY + ".so")
case("a context that is not x86-64 is not decoded", ["crashed in: not decoded (not an x86-64 context)"], context_flags=0x00400002)
case("a truncated dump prints 'could not decode' and its error class only", ["could not decode:"], truncate=True)

with tempfile.TemporaryDirectory() as d:
    check("an empty folder: no dump", "(no dump)" in run("decode", d), run("decode", d))
    check("no folder: no dump", "(no dump)" in run("decode", os.path.join(d, "missing")), "")
    os.makedirs(os.path.join(d, "completed"))
    p = os.path.join(d, "completed", "3f2a.dmp"); build(p)
    q = os.path.join(d, "settings.dat"); open(q, "wb").write((KEY + MARKER).encode())
    odd = os.path.join(d, "odd " + MARKER); open(odd, "wb").write(b"x")
    os.utime(p, (1000, 1000)); os.utime(q, (990, 990)); os.utime(odd, (995, 995))
    out = run("list", d, "960")
    check("the listing names files, sizes and times after the cold start, never contents",
          "completed/3f2a.dmp" in out and "+40 s after the cold start" in out and "settings.dat" in out and no_secret(out), out)
    check("  ... and withholds a name outside the character set", "<name withheld>" in out, out)

print(f"crash facts checks: all {ran} passed" if failures == 0 else f"crash facts checks: {failures} of {ran} failed")
sys.exit(1 if failures else 0)
