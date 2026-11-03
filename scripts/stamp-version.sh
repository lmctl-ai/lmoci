#!/usr/bin/env bash
# Stamp a deterministic CI release version into the working tree; CI never commits this edit.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

current="$(head -n1 VERSION | tr -d '[:space:]')"
if [[ "${1:-}" == "--base" ]]; then
  [[ "$current" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "invalid VERSION: $current" >&2; exit 1; }
  echo "${current%.*}"
  exit 0
fi

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "expected a semantic version" >&2; exit 2; }

python3 - "$version" <<'PY'
from pathlib import Path
import re
import sys

version = sys.argv[1]
root = Path.cwd()
(root / "VERSION").write_text(version + "\n", encoding="utf-8")

main_path = root / "src/main/java/ai/lmctl/lmoci/Main.java"
main = main_path.read_text(encoding="utf-8")
main, version_count = re.subn(
    r'(public static final String VERSION = ")[^"]+(";)',
    rf'\g<1>{version}\g<2>', main, count=1)
main, help_count = re.subn(
    r'(lmoci )[0-9]+\.[0-9]+\.[0-9]+( — Oracle Cloud Infrastructure lmcode host)',
    rf'\g<1>{version}\g<2>', main, count=1)
if version_count != 1 or help_count != 1:
    raise SystemExit("Main.java version markers changed; refusing an ambiguous stamp")
main_path.write_text(main, encoding="utf-8")

pom_path = root / "pom.xml"
pom = pom_path.read_text(encoding="utf-8")
pom, count = re.subn(
    r'(<artifactId>lmoci</artifactId>\s*<version>)[^<]+(</version>)',
    rf'\g<1>{version}\g<2>', pom, count=1)
if count != 1:
    raise SystemExit("pom.xml project version marker changed; refusing an ambiguous stamp")
pom_path.write_text(pom, encoding="utf-8")
PY

echo "stamped lmoci $version (CI working tree only)"
