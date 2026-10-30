#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?GraalVM JAVA_HOME is required}"
mkdir -p target/classes
separator=:
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) separator=';' ;; esac
"$JAVA_HOME/bin/javac" --add-modules org.graalvm.nativeimage,org.graalvm.word \
  -cp "$JAVA_HOME/lib/svm/builder/svm.jar${separator}target/classes" \
  -d target/classes src/native/java/ai/lmctl/lmoci/*.java
