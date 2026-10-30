#!/usr/bin/env sh
set -eu

if ! command -v native-image >/dev/null 2>&1 && ! command -v native-image.cmd >/dev/null 2>&1; then
  printf '%s\n' 'native-image is required (install GraalVM for Java 21 or newer)' >&2
  exit 2
fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
if [ "$(uname -s)" = Linux ]; then
  timeout 900 scripts/build-sqlite.sh
fi
mvn -q -Pnative -DskipTests package
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    if ! mt.exe -nologo '-inputresource:target/lmoci.exe;#1' -manifest scripts/windows-utf8.manifest -out:target/lmoci-merged.manifest; then
      cp scripts/windows-utf8.manifest target/lmoci-merged.manifest
    fi
    mt.exe -nologo -manifest target/lmoci-merged.manifest '-outputresource:target/lmoci.exe;#1'
    ;;
esac
