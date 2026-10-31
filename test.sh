#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"

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
  if [[ ! -f target/lmoci-0.1.0-all.jar ]]; then
    mvn -q package -DskipTests
  fi
  HOST="$ROOT/target/lmoci-0.1.0-all.jar"
fi

if [[ "$HOST" == *.jar ]]; then
  python3 scripts/check-host-flags.py schema/lmoci.dsl.json java -jar "$HOST"
else
  python3 scripts/check-host-flags.py schema/lmoci.dsl.json "$HOST"
fi
python3 scripts/test-cli.py "$HOST"
python3 scripts/test-cred.py "$HOST"
python3 scripts/test-fake.py "$HOST"
python3 scripts/test-ocisim.py "$HOST"
