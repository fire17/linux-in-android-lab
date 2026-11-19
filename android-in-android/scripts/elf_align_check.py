#!/usr/bin/env python3
"""elf_align_check.py MIN_ALIGN FILE... — exit 1 if any ELF64 PT_LOAD p_align < MIN_ALIGN."""
import struct, sys

def aligns(path):
    b = open(path, "rb").read()
    if b[:4] != b"\x7fELF" or b[4] != 2:
        raise SystemExit(f"{path}: not ELF64")
    phoff = struct.unpack_from("<Q", b, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", b, 0x36)
    out = []
    for i in range(phnum):
        o = phoff + i * phentsize
        if struct.unpack_from("<I", b, o)[0] == 1:  # PT_LOAD
            out.append(struct.unpack_from("<Q", b, o + 48)[0])
    return out

need = int(sys.argv[1])
bad = False
for f in sys.argv[2:]:
    a = aligns(f)
    ok = all(x >= need for x in a)
    print(f"{'ok ' if ok else 'BAD'} {f}: PT_LOAD align {[hex(x) for x in a]}")
    bad |= not ok
sys.exit(1 if bad else 0)
