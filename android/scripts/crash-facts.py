#!/usr/bin/env python3
"""crash-facts.py — the facts a failed nightly may print about the emulator's crash dumps (#363, the owner's rule of
2026-11-14): nothing else is ever read or printed.

  crash-facts.py list <crash folder> <cold start, epoch seconds>
      A count, then per file only its kind (from a fixed list of extensions), its size and its time in seconds after
      the cold start: no file or folder name, never its contents (the owner allowed four crash facts; QA's reduced form).
  crash-facts.py decode <crash folder> <cold start, epoch seconds>
      The newest minidump (*.dmp) written after this attempt's cold start (an earlier attempt's dump is never read), its
      age, and four facts only:
        - the signal number (1-64; anything else reads "not decoded": a macOS dump stores a Mach exception there);
        - the crashing thread's name (if the dump names it);
        - where it crashed, as <module file name> + <offset> (code, which is public);
        - the fault address as a class: 0 (null), below 64 KiB, inside <module>, non-canonical, or outside every module.
      Never a raw address, a register, memory, the environment, the command line or an annotation: a dump holds the
      process's memory, where the app's Maps key or the device log can be (EMULATOR-VANISHES §4, QA's V1 / V2 / T1).
Only the minidump header, its stream directory, the exception stream, the thread-name stream and the module list are
read. Any parse failure prints "could not decode" with the error's class, never its message. Python standard library."""
import os, re, struct, sys

NAME_OK = re.compile(r"[A-Za-z0-9._+-]{1,64}")
THREAD_OK = re.compile(r"[A-Za-z0-9._+ -]{1,15}")
SIGNALS = {4: "SIGILL", 5: "SIGTRAP", 6: "SIGABRT", 7: "SIGBUS", 8: "SIGFPE", 9: "SIGKILL", 11: "SIGSEGV", 15: "SIGTERM"}
EXCEPTION_STREAM, MODULE_LIST_STREAM, THREAD_NAMES_STREAM = 6, 4, 24
CONTEXT_AMD64 = 0x00100000
AMD64_RIP_OFFSET = 0xF8   # MINIDUMP CONTEXT_AMD64: Rip


def safe_name(name, pattern=NAME_OK):
    return name if pattern.fullmatch(name or "") else "<name withheld>"


KINDS = {".dmp": "dump", ".dat": "settings", ".meta": "metadata", ".lock": "lock", ".log": "log"}


def list_folder(folder, cold_start):
    if not os.path.isdir(folder):
        print("(no crash folder)"); return
    rows = []
    for root, _, files in os.walk(folder):
        for f in files:
            try:
                st = os.stat(os.path.join(root, f))
            except OSError:
                continue
            kind = KINDS.get(os.path.splitext(f)[1].lower(), "other")
            rows.append((st.st_mtime, f"{kind}  {st.st_size} bytes  {st.st_mtime - cold_start:+.0f} s after the cold start"))
    print(f"{len(rows)} file(s)")
    for _, row in sorted(rows):
        print(row)


def read_string(data, rva):
    (length,) = struct.unpack_from("<I", data, rva)
    raw = data[rva + 4: rva + 4 + length]
    if len(raw) != length:
        raise ValueError("string past the end")
    return raw.decode("utf-16-le", errors="replace")


def decode(path):
    with open(path, "rb") as fh:
        data = fh.read()
    sig, _ver, count, dir_rva = struct.unpack_from("<4sIII", data, 0)
    if sig != b"MDMP":
        raise ValueError("not a minidump")
    streams = {}
    for i in range(count):
        stype, size, rva = struct.unpack_from("<III", data, dir_rva + 12 * i)
        if rva + size > len(data):
            raise ValueError("stream past the end")
        streams.setdefault(stype, (size, rva))
    if EXCEPTION_STREAM not in streams:
        print("could not decode: no exception record"); return
    _, erva = streams[EXCEPTION_STREAM]
    thread_id, = struct.unpack_from("<I", data, erva)
    code, _flags, _record, address = struct.unpack_from("<IIQQ", data, erva + 8)
    ctx_size, ctx_rva = struct.unpack_from("<II", data, erva + 8 + 152)

    modules = []
    if MODULE_LIST_STREAM in streams:
        _, mrva = streams[MODULE_LIST_STREAM]
        (n,) = struct.unpack_from("<I", data, mrva)
        for i in range(n):
            base, size, _c, _t, name_rva = struct.unpack_from("<QIIII", data, mrva + 4 + 108 * i)
            modules.append((base, size, safe_name(os.path.basename(read_string(data, name_rva)))))

    def module_of(a):
        for base, size, name in modules:
            if base <= a < base + size:
                return name, a - base
        return None

    thread = None
    if THREAD_NAMES_STREAM in streams:
        _, trva = streams[THREAD_NAMES_STREAM]
        (n,) = struct.unpack_from("<I", data, trva)
        for i in range(n):
            tid, name_rva = struct.unpack_from("<IQ", data, trva + 4 + 12 * i)
            if tid == thread_id:
                thread = safe_name(read_string(data, name_rva), THREAD_OK)

    if 1 <= code <= 64:
        print(f"signal: {code}" + (f" ({SIGNALS[code]})" if code in SIGNALS else ""))
    else:
        print("signal: not decoded (no signal number in this dump)")
    print(f"crashing thread: {thread if thread else 'no name in the dump'}")
    ip = None
    if ctx_size >= AMD64_RIP_OFFSET + 8 and ctx_rva + ctx_size <= len(data):
        flags, = struct.unpack_from("<I", data, ctx_rva + 0x30)
        if flags & CONTEXT_AMD64 == CONTEXT_AMD64:
            ip, = struct.unpack_from("<Q", data, ctx_rva + AMD64_RIP_OFFSET)
    if ip is None:
        print("crashed in: not decoded (not an x86-64 context)")
    else:
        where = module_of(ip)
        print(f"crashed in: {where[0]} + {where[1]:#x}" if where else "crashed in: outside every module")
    print(f"fault address: {address_class(address, module_of)}")


def address_class(a, module_of):
    """Never the number: a wild pointer can be built from data (QA's V1)."""
    if a == 0:
        return "0 (null)"
    if a < 0x10000:
        return "below 64 KiB (null + offset)"
    top = a >> 47
    if top not in (0, 0x1FFFF):
        return "non-canonical"
    where = module_of(a)
    return f"inside {where[0]}" if where else "outside every module"


def newest_dump(folder, since):
    dumps = []
    for root, _, files in os.walk(folder):
        for f in files:
            if f.endswith(".dmp"):
                p = os.path.join(root, f)
                m = os.path.getmtime(p)
                if m >= since:
                    dumps.append((m, p))
    return max(dumps) if dumps else None


def main(argv):
    if len(argv) == 4 and argv[1] == "list":
        list_folder(argv[2], float(argv[3])); return 0
    if len(argv) == 4 and argv[1] == "decode":
        try:  # the search too: an unreadable entry must not print a traceback with its path
            since = float(argv[3])
            d = newest_dump(argv[2], since) if os.path.isdir(argv[2]) else None
            if not d:
                print("(no dump written after this attempt's cold start)"); return 0
            print(f"dump written {d[0] - since:+.0f} s after the cold start")
            decode(d[1])
        except Exception as e:  # never the message: it could hold a path or bytes of the file
            print(f"could not decode: {type(e).__name__}")
        return 0
    print("usage: crash-facts.py list <folder> <cold start epoch> | decode <folder> <cold start epoch>", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
