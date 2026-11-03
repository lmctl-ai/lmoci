#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
VERSION="$(head -n1 VERSION | tr -d '[:space:]')"

chmod +x scripts/*.py scripts/*.sh

if [[ "${LMOCI_NATIVE:-0}" == "1" ]]; then
  if [[ ! -x target/lmoci && ! -x target/lmoci.exe ]]; then
    sh scripts/build-native.sh
  fi
  if [[ -x target/lmoci.exe ]]; then
    HOST="$ROOT/target/lmoci.exe"
  else
    HOST="$ROOT/target/lmoci"
  fi
  if [[ "$(uname -s)" == "Linux" ]]; then
    python3 scripts/check-linux-linkage.py "$HOST"
  fi
else
  if [[ ! -f "target/lmoci-${VERSION}-all.jar" ]]; then
    mvn -q package -DskipTests
  fi
  HOST="$ROOT/target/lmoci-${VERSION}-all.jar"
fi

LMSCRIPT="${LMSCRIPT_BIN:-$(command -v lmscript || true)}"
[ -n "$LMSCRIPT" ] || { echo "lmscript not found" >&2; exit 1; }
LMSCRIPT_PY="$LMSCRIPT"
if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == CYGWIN* ]]; then
  case "$LMSCRIPT" in
    *.cmd) LMSCRIPT_PY="$(cygpath -m "$LMSCRIPT")";;
    *) [[ -f "$LMSCRIPT.cmd" ]] && LMSCRIPT_PY="$(cygpath -m "$LMSCRIPT.cmd")";;
  esac
fi
export LMOCI_LMSCRIPT_BIN="$LMSCRIPT_PY"

if [[ "$HOST" == *.jar ]]; then
  python3 scripts/check-host-flags.py schema/lmoci.dsl.json java -jar "$HOST"
else
  python3 scripts/check-host-flags.py schema/lmoci.dsl.json "$HOST"
fi
python3 scripts/check-lmcode-host.py
python3 scripts/test-host-protocol.py "$HOST" "$LMOCI_LMSCRIPT_BIN"
python3 scripts/test-operation-parity.py "$HOST" "$LMOCI_LMSCRIPT_BIN"
python3 scripts/test-lmoci-dry-spy.py "$LMOCI_LMSCRIPT_BIN" "$HOST" schema/lmoci.dsl.json
python3 scripts/test-cli.py "$HOST"
python3 scripts/test-cred.py "$HOST"
python3 scripts/test-fake.py "$HOST"
python3 scripts/test-ocisim.py "$HOST"
