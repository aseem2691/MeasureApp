#!/usr/bin/env python3
"""Check ELF LOAD segment alignment of native libs in an APK (16 KB page-size compliance)."""
import struct
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"
REQUIRED = 0x4000  # 16 KB


def check_elf(data):
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if not is64:
        return None
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    min_align = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
            if min_align is None or p_align < min_align:
                min_align = p_align
    return min_align


with zipfile.ZipFile(APK) as z:
    bad = []
    for name in z.namelist():
        if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
            align = check_elf(z.read(name))
            status = "OK " if align and align >= REQUIRED else "BAD"
            if align is not None and align < REQUIRED:
                bad.append(name)
            print(f"{status} {name} (align={hex(align) if align else '?'})")
    print()
    if bad:
        print(f"NOT 16KB compliant: {len(bad)} libs")
        sys.exit(1)
    print("All libs 16KB aligned ✔")
