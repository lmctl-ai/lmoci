#!/usr/bin/env bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/scripts" "$scratch/src/main/java/ai/lmctl/lmoci"
cp "$ROOT/VERSION" "$ROOT/pom.xml" "$scratch/"
cp "$ROOT/scripts/stamp-version.sh" "$scratch/scripts/"
cp "$ROOT/src/main/java/ai/lmctl/lmoci/Main.java" "$scratch/src/main/java/ai/lmctl/lmoci/"
chmod +x "$scratch/scripts/stamp-version.sh"

base="$("$scratch/scripts/stamp-version.sh" --base)"
[[ "$base" == "$(head -n1 "$ROOT/VERSION" | sed 's/\.[^.]*$//')" ]]
version="$base.9876"
"$scratch/scripts/stamp-version.sh" "$version"
grep -qx "$version" "$scratch/VERSION"
grep -q "<version>$version</version>" "$scratch/pom.xml"
grep -q "public static final String VERSION = \"$version\";" "$scratch/src/main/java/ai/lmctl/lmoci/Main.java"
grep -q "lmoci $version — Oracle Cloud Infrastructure lmcode host" "$scratch/src/main/java/ai/lmctl/lmoci/Main.java"
before=$(sha256sum "$scratch/VERSION" "$scratch/pom.xml" "$scratch/src/main/java/ai/lmctl/lmoci/Main.java")
"$scratch/scripts/stamp-version.sh" "$version"
after=$(sha256sum "$scratch/VERSION" "$scratch/pom.xml" "$scratch/src/main/java/ai/lmctl/lmoci/Main.java")
[[ "$before" == "$after" ]]
echo 'PASS deterministic and idempotent release version stamping'
