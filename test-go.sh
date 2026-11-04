#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Go host verification and ocisim conformance runner for lmoci (no make).
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
# Tests select fake/loopback mode explicitly: never inherit live or endpoint overrides.
unset LMOCI_LIVE LMCLOUD_ENDPOINT LMCLOUD_STORAGE_ENDPOINT LMCLOUD_QUEUE_ENDPOINT \
  LMOCI_ENDPOINT LMOCI_STORAGE_ENDPOINT LMOCI_QUEUE_ENDPOINT OCI_ENDPOINT OCI_OBJECT_STORAGE_ENDPOINT OCI_QUEUE_ENDPOINT

LMSCRIPT_BIN="${LMSCRIPT_BIN:-/home/ubuntu/lmctl-mono/lmscript/bin/lmscript}"
LMCLOUD_DIR="${LMCLOUD_DIR:-/home/ubuntu/cloud/lmcloud}"
SIM_SH="${SIM_SH:-${LMCLOUD_DIR:-/home/ubuntu/cloud/lmcloud}/scripts/sim.sh}"
export LMCLOUD_SIM_DIR="${LMCLOUD_SIM_DIR:-${LMCLOUD_DIR}/.cache/sims}"

echo "==> [1/9] Building Go host bin/lmoci (CGO_ENABLED=0 -trimpath)"
mkdir -p bin
CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o bin/lmoci ./cmd/lmoci
BIN="$ROOT/bin/lmoci"

if [[ "$(uname -s)" == "Linux" ]]; then
  echo "==> [2/9] Checking Linux binary linkage (static or glibc allowlist)"
  if command -v readelf >/dev/null 2>&1; then
    needed="$(readelf -d "$BIN" 2>/dev/null | grep '(NEEDED)' || true)"
    if [[ -n "$needed" ]]; then
      bad="$(echo "$needed" | grep -vE '\[(libc\.so\.6|libm\.so\.6|libpthread\.so\.0|libdl\.so\.2|librt\.so\.1|ld-linux[^]]*)\]' || true)"
      [[ -z "$bad" ]] || { echo "disallowed NEEDED libraries on Linux: $bad" >&2; exit 1; }
    fi
  elif command -v ldd >/dev/null 2>&1; then
    ldd_out="$(ldd "$BIN" 2>&1 || true)"
    case "$ldd_out" in
      *"not a dynamic executable"*|*"statically linked"*) ;;
      *)
        bad="$(echo "$ldd_out" | awk '/=>/ {print $1}' | grep -vE '^(libc\.so\.6|libm\.so\.6|libpthread\.so\.0|libdl\.so\.2|librt\.so\.1|ld-linux.*|linux-vdso\.so\..*)$' || true)"
        [[ -z "$bad" ]] || { echo "disallowed dynamic libs on Linux: $bad" >&2; exit 1; }
        ;;
    esac
  fi
fi

echo "==> [3/9] Checking --version, --help, --notices, sdk.lock, and lmcloud.lock"
VER=$("$BIN" --version)
[[ "$VER" == "lmoci 0.1.0" ]] || { echo "unexpected --version: $VER" >&2; exit 1; }
"$BIN" --help | grep -q "lmoci 0.1.0"
"$BIN" --notices | grep -q "github.com/oracle/oci-go-sdk/v65"
grep -q "06cd9ae991d90fea9ad38f3b071da646c76832f4" sdk.lock
grep -q "06cd9ae991d9" go.mod
./scripts/sync-lmcloud-schema.sh --verify
# The bytes the host serves come from hostkit's schema package; they must match the lmcloud.lock pin.
want_sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' lmcloud.lock)"
got_sha="$("$BIN" --lmcode-schema --json | sha256sum | awk '{print $1}')"
[[ "$got_sha" == "$want_sha" ]] || { echo "served schema sha256 $got_sha != lmcloud.lock $want_sha" >&2; exit 1; }

echo "==> [4/9] Checking --lmcode-schema [--json] byte parity (top-level and 'cloud' subcommand)"
cmp -s <("$BIN" --lmcode-schema) "$ROOT/schema/lmcloud.dsl.json"
cmp -s <("$BIN" --lmcode-schema --json) "$ROOT/schema/lmcloud.dsl.json"
cmp -s <("$BIN" cloud --lmcode-schema --json) "$ROOT/schema/lmcloud.dsl.json"
if [[ -f "$LMCLOUD_DIR/schema/lmcloud.dsl.json" ]]; then
  cmp -s <("$BIN" --lmcode-schema --json) "$LMCLOUD_DIR/schema/lmcloud.dsl.json"
fi

echo "==> [5/9] Checking --lmcode-signatures parity against lmscript"
diff -u <("$LMSCRIPT_BIN" --lmcode-signatures "$ROOT/schema/lmcloud.dsl.json") <("$BIN" --lmcode-signatures)
diff -u <("$LMSCRIPT_BIN" --lmcode-signatures "$ROOT/schema/lmcloud.dsl.json" --without-std) <("$BIN" --lmcode-signatures --without-std)
diff -u <("$LMSCRIPT_BIN" --lmcode-signatures "$ROOT/schema/lmcloud.dsl.json" --without-std) <("$BIN" cloud --lmcode-signatures --without-std)

echo "==> [6/9] Checking CLI failure frames (unknown flag, corrupt bytecode, invalid LMOCI_MAX_MEMORY_MB)"
set +e
"$BIN" --unknown-flag >/dev/null 2>&1
RC_FLAG=$?
TMP_BAD=$(mktemp)
printf 'CORRUPT_BYTECODE' > "$TMP_BAD"
"$BIN" --lmcode "$TMP_BAD" >/dev/null 2>&1
RC_BAD=$?
LMOCI_MAX_MEMORY_MB=not-a-number "$BIN" --lmcode "$TMP_BAD" >/dev/null 2>&1
RC_MEM=$?
rm -f "$TMP_BAD"
set -e
[[ "$RC_FLAG" -eq 2 && "$RC_BAD" -eq 3 && "$RC_MEM" -eq 2 ]] || {
  echo "expected exit codes flag=2 bad=3 mem=2; got flag=$RC_FLAG bad=$RC_BAD mem=$RC_MEM" >&2
  exit 1
}

echo "==> [7/9] Checking real and dry invocation through lmscript run_lmcode"
"$LMSCRIPT_BIN" -e '
s = load_schema(schema="schema/lmcloud.dsl.json");
p = _lmcode_ s;
  let prov = cloud.provider();
  let c = cloud.bucket_create("lmoci-smoke-bucket");
  let ex = cloud.bucket_exists("lmoci-smoke-bucket");
  let d = cloud.bucket_delete("lmoci-smoke-bucket");
  lmcode_return_put("prov", prov);
  lmcode_return_put("created", c);
  lmcode_return_put("exists", ex);
  lmcode_return_put("deleted", d);
end _lmcode_
r_real = run_lmcode(p, {command:["./bin/lmoci", "--lmcode", "$DSLFILE"]});
assert(r_real.ok && r_real.result.prov == "oci" && r_real.result.created && r_real.result.exists && r_real.result.deleted, "real smoke failed");
'

echo "==> [7b/9] Dry-run proof: lmscript --dry-run makes zero mutating OCI requests (HTTP spy)"
python3 scripts/test-dry-spy.py "$LMSCRIPT_BIN" "$BIN" "$ROOT/schema/lmcloud.dsl.json"

echo "==> [8/9] Running go vet ./... and go test -race -count=3 ./..."
go vet ./...
go test -race -count=3 ./...

if [[ -x "$SIM_SH" && -x "$LMCLOUD_DIR/tests/run-conformance.sh" ]]; then
  echo "==> [9/9] Running lmcloud conformance (open) against ocisim"
  SIM_NAME="${LMOCI_SIM_NAME:-lmoci-test-$$}"
  cleanup_sim() {
    "$SIM_SH" stop oci --name "$SIM_NAME" >/dev/null 2>&1 || true
  }
  trap cleanup_sim EXIT
  ENDPOINT=$("$SIM_SH" start oci --name "$SIM_NAME" --free-port)
  LMSCRIPT_BIN="$LMSCRIPT_BIN" "$LMCLOUD_DIR/tests/run-conformance.sh" "$BIN" "$ENDPOINT" all
  cleanup_sim
  trap - EXIT

  SIM_BIN="$LMCLOUD_SIM_DIR/ocisim"
  if [[ -x "$SIM_BIN" ]] && command -v openssl >/dev/null 2>&1; then
    echo "==> [9b/9] Running full lmcloud conformance under ocisim --auth enforce"
    ENF_TMP=$(mktemp -d)
    PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
    openssl genrsa -out "$ENF_TMP/allow_priv.pem" 2048 >/dev/null 2>&1
    openssl rsa -in "$ENF_TMP/allow_priv.pem" -pubout -out "$ENF_TMP/allow_pub.pem" >/dev/null 2>&1
    openssl genrsa -out "$ENF_TMP/deny_priv.pem" 2048 >/dev/null 2>&1
    openssl rsa -in "$ENF_TMP/deny_priv.pem" -pubout -out "$ENF_TMP/deny_pub.pem" >/dev/null 2>&1
    ALLOW_KID="ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000/ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000/aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
    DENY_KID="ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000/ocid1.user.oc1..denieduser00000000000000000000000000000000000000000000/11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00"
    python3 -c '
import json, sys
allow_pub = open(sys.argv[1]).read()
deny_pub = open(sys.argv[2]).read()
cfg = {
  "credentials": [
    {
      "keyId": sys.argv[3],
      "publicKeyPem": allow_pub,
      "policy": {"Version": "2026-01-01", "Statement": [{"Effect": "Allow", "Action": "*", "Resource": "*"}]}
    },
    {
      "keyId": sys.argv[4],
      "publicKeyPem": deny_pub,
      "policy": {"Version": "2026-01-01", "Statement": [{"Effect": "Deny", "Action": "*", "Resource": "*"}]}
    }
  ]
}
open(sys.argv[5], "w").write(json.dumps(cfg))
' "$ENF_TMP/allow_pub.pem" "$ENF_TMP/deny_pub.pem" "$ALLOW_KID" "$DENY_KID" "$ENF_TMP/auth.json"
    "$SIM_BIN" serve --port "$PORT" --auth enforce --auth-config "$ENF_TMP/auth.json" >"$ENF_TMP/ocisim.log" 2>&1 &
    ENF_PID=$!
    cleanup_enf() {
      kill -TERM "$ENF_PID" >/dev/null 2>&1 || true
      wait "$ENF_PID" >/dev/null 2>&1 || true
      rm -rf "$ENF_TMP"
    }
    trap cleanup_enf EXIT
    for _ in $(seq 1 50); do
      if curl -s -o /dev/null "http://127.0.0.1:$PORT/n/" 2>/dev/null || curl -s -o /dev/null "http://127.0.0.1:$PORT" 2>/dev/null; then
        break
      fi
      sleep 0.1
    done
    LMOCI_SIM_KEY_ID="$ALLOW_KID" \
    LMOCI_SIM_PRIVATE_KEY_PATH="$ENF_TMP/allow_priv.pem" \
    LMCLOUD_ENFORCE_DENIED_OCI_KEY_ID="$DENY_KID" \
    LMCLOUD_ENFORCE_DENIED_OCI_PRIVATE_KEY_PATH="$ENF_TMP/deny_priv.pem" \
    LMSCRIPT_BIN="$LMSCRIPT_BIN" "$LMCLOUD_DIR/tests/run-conformance.sh" "$BIN" "http://127.0.0.1:$PORT" all
    cleanup_enf
    trap - EXIT
  fi
else
  echo "==> [9/9] Skipping ocisim conformance (sim.sh or lmcloud repo not found)"
fi

echo "ALL GO HOST CHECKS PASSED"
