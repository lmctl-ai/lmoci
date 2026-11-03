#!/usr/bin/env python3
"""Failing source controls for the lmoci Java host protocol cutover."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
failures = []
try:
    lock = json.loads((ROOT / "sdk.lock").read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    lock = {}
    failures.append(f"sdk.lock must use the Java SDK JSON pin format: {exc}")
if lock.get("repo") != "lmctl-ai/lmcode-java":
    failures.append("sdk.lock does not pin the Java SDK repository")
if not re.fullmatch(r"[0-9a-f]{40}", lock.get("commit", "")):
    failures.append("SDK commit is not a full reviewed SHA")
if not re.fullmatch(r"[0-9a-f]{64}", lock.get("sha256", "")):
    failures.append("SDK archive checksum is not a full SHA-256")

main = (ROOT / "src/main/java/ai/lmctl/lmoci/Main.java").read_text(encoding="utf-8")
for token in ("InvocationResults.fromCommand", "ResultContract.fromSchema", "results.select(",
              "results.complete(", "results.writeFrame(", "context.setDry(results.dry())",
              "Reports.failureFrame"):
    if token not in main:
        failures.append("Main.java is missing " + token)
if "Reports.ok(\"null\")" in main:
    failures.append("Main.java still uses the bare success envelope")
if 'if ("--dry-run".equals(arg))' not in main:
    failures.append("Main.java does not refuse the legacy CLI dry flag")
cloud_host = (ROOT / "src/main/java/ai/lmctl/lmoci/CloudHost.java").read_text(encoding="utf-8")
if "lmoci.dryRun" in main + cloud_host:
    failures.append("host setup still consults the legacy system-property dry override")
if "test-host-protocol.py" not in (ROOT / "test.sh").read_text(encoding="utf-8"):
    failures.append("test.sh does not run the new protocol controls")

if failures:
    print("FAIL lmoci lmcode host controls:")
    for failure in failures:
        print("  - " + failure)
    raise SystemExit(1)
print("PASS Java SDK pin and lmcode invocation/result/dry host invariants")
