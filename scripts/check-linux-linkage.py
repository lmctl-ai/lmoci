#!/usr/bin/env python3
"""Verify that a Linux native executable only dynamically links glibc/system libraries."""

import re
import subprocess
import sys

ALLOWED_SONAMES = {
    "linux-vdso.so.1",
    "libz.so.1",
    "libm.so.6",
    "libdl.so.2",
    "libpthread.so.0",
    "librt.so.1",
    "libresolv.so.2",
    "libc.so.6",
    "ld-linux-x86-64.so.2",
    "ld-linux-aarch64.so.1",
}
MAX_GLIBC = (2, 35, 0)


def parse_version(text: str) -> tuple[int, ...]:
    return tuple(int(part) for part in text.split("."))


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-linux-linkage.py <path-to-elf-binary>", file=sys.stderr)
        return 2
    binary = sys.argv[1]

    ldd = subprocess.run(["ldd", binary], check=True, text=True, capture_output=True).stdout
    for raw in ldd.splitlines():
        line = raw.strip()
        if not line:
            continue
        soname = line.split()[0]
        if soname.startswith("/lib") or soname.startswith("/usr/lib"):
            soname = soname.rsplit("/", 1)[-1]
        if soname not in ALLOWED_SONAMES:
            print(f"disallowed dynamic dependency in {binary}: {line}", file=sys.stderr)
            return 1

    objdump = subprocess.run(["objdump", "-T", binary], check=True, text=True, capture_output=True).stdout
    versions = set(re.findall(r"GLIBC_([0-9]+(?:\.[0-9]+)*)", objdump))
    too_new = sorted(v for v in versions if parse_version(v) > MAX_GLIBC)
    if too_new:
        print(
            f"GLIBC symbol versions newer than {'.'.join(map(str, MAX_GLIBC))} in {binary}: {', '.join(too_new)}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
