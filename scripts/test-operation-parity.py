#!/usr/bin/env python3
"""Compare current lmcode operation results with captured pre-migration offline Java CLI results."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOST = str(Path(sys.argv[1]).resolve())
LMSCRIPT = sys.argv[2]
fixture = json.loads((ROOT / "testdata/lmcode-operation-parity-before.json").read_text(encoding="utf-8"))
assert fixture["legacy_host_version"] == "lmoci 0.1.0"
assert len(fixture["cases"]) >= 5
host = ["java", "-jar", HOST] if HOST.endswith(".jar") else [HOST]
schema = str(ROOT / "schema/lmoci.dsl.json")

with tempfile.TemporaryDirectory(prefix="lmoci-op-parity-") as scratch:
    work = Path(scratch)
    (work / "home").mkdir()
    env = dict(os.environ)
    for key in ("LMOCI_LIVE", "LMCLOUD_ENDPOINT", "LMCLOUD_STORAGE_ENDPOINT", "LMCLOUD_QUEUE_ENDPOINT",
                "LMOCI_ENDPOINT", "LMOCI_STORAGE_ENDPOINT", "LMOCI_QUEUE_ENDPOINT", "OCI_ENDPOINT",
                "OCI_OBJECT_STORAGE_ENDPOINT", "OCI_QUEUE_ENDPOINT"):
        env.pop(key, None)
    env["HOME"] = str(work / "home")
    env["LMCTL_HOME"] = str(work / "home/.lmctl")
    for case in fixture["cases"]:
        source = (
            f"p = _lmcode_ schema={json.dumps(schema)};\n"
            + case["new_lmcode_program"] + "\n"
            + "end _lmcode_\n"
            + f"r = run_lmcode(p, {{command: {json.dumps(host + ['--lmcode', '$DSLFILE'])}}});\n"
            + "if (!r.ok) { println(json_stringify(r)); exit(1); }\n"
            + "write_file(\"actual.json\", json_stringify(r.result));\n"
        )
        script = work / f"{case['name']}.lms"
        script.write_text(source, encoding="utf-8")
        proc = subprocess.run([LMSCRIPT, str(script)], cwd=work, env=env, text=True, capture_output=True, check=False)
        assert proc.returncode == 0, (case["name"], proc.stdout, proc.stderr)
        actual = json.loads((work / "actual.json").read_text(encoding="utf-8"))
        assert actual == case["legacy_result"], (case["name"], case["input_argv"], case["legacy_result"], actual)
        print("PASS", case["name"], json.dumps(actual, ensure_ascii=False, sort_keys=True))

print(f"PASS {len(fixture['cases'])} pre-migration offline operations match lmcode results")
