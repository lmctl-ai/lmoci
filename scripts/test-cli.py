#!/usr/bin/env python3
"""CLI, schema parity, failure codes, examples, and managed memory enforcement."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HOST = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (ROOT / "target" / "lmoci-0.1.0-all.jar")
BASE_ENV = dict(os.environ)
for k in (
    "LMOCI_ENDPOINT",
    "OCI_ENDPOINT",
    "LMOCI_MAX_MEMORY_MB",
    "LMOCI_MAX_COST_USD",
    "CI",
    "GITHUB_ACTIONS",
    "OCI_CLI_USER",
    "OCI_CLI_TENANCY",
    "OCI_CLI_FINGERPRINT",
    "OCI_CLI_KEY_FILE",
    "OCI_CLI_KEY_CONTENT",
    "OCI_CONFIG_FILE",
):
    BASE_ENV.pop(k, None)


def host_cmd() -> list[str]:
    if HOST.suffix == ".jar":
        return ["java", "-jar", str(HOST)]
    if HOST.suffix == ".cmd":
        return ["cmd.exe", "/c", str(HOST)]
    return [str(HOST)]


def run(args: list[str], code: int = 0, extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    env = dict(BASE_ENV)
    if extra:
        env.update(extra)
    proc = subprocess.run(
        host_cmd() + args,
        env=env,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=120,
        check=False,
    )
    assert proc.returncode == code, (
        args,
        f"expected={code} got={proc.returncode}",
        proc.stdout[-1200:],
        proc.stderr[-1200:],
    )
    return proc


def main() -> None:
    assert run(["--version"]).stdout == "lmoci 0.1.0\n"
    assert run(["--notices"]).stdout == (ROOT / "THIRD_PARTY_NOTICES").read_text(encoding="utf-8")
    assert "LMOCI_MAX_MEMORY_MB" in run(["--help"]).stdout
    assert "\u2014 Oracle Cloud" in run(
        ["--help"],
        extra={"JAVA_TOOL_OPTIONS": "-Dstdout.encoding=windows-1252 -Dstderr.encoding=windows-1252"},
    ).stdout

    schema = run(["--lmcode-schema", "--json"]).stdout
    assert schema == (ROOT / "schema" / "lmoci.dsl.json").read_text(encoding="utf-8")
    parsed_schema = json.loads(schema)
    assert parsed_schema["name"] == "lmoci"
    assert "str_len" not in parsed_schema["functions"], "schema must omit built-in std functions"

    lmscript = shutil.which("lmscript")
    assert lmscript is not None, "lmscript must be on PATH"
    sig_proc = subprocess.run(
        [lmscript, "--lmcode-signatures", str(ROOT / "schema" / "lmoci.dsl.json")],
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=45,
        check=False,
    )
    assert sig_proc.returncode == 0, sig_proc.stderr
    assert sig_proc.stdout == run(["--lmcode-signatures"]).stdout, "schema/registry signature mismatch"

    host_sig_proc = subprocess.run(
        [lmscript, "--lmcode-signatures", str(ROOT / "schema" / "lmoci.dsl.json"), "--without-std"],
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=45,
        check=False,
    )
    assert host_sig_proc.returncode == 0, host_sig_proc.stderr
    assert host_sig_proc.stdout == run(["--lmcode-signatures", "--without-std"]).stdout

    run([], 2)
    run(["--nonesuch"], 2)
    run(["--lmcode", "missing-bytecode.dslb"], 3)

    for raw in ["0", "-1", "wat", "1.5", "99999999999999999999999999"]:
        err = run(["--version"], 2, {"LMOCI_MAX_MEMORY_MB": raw}).stderr
        assert "positive whole MiB" in err, err

    for raw in ["-0.01", "100.01", "nan", "1e100"]:
        err = run(["--version"], 2, {"LMOCI_MAX_COST_USD": raw}).stderr
        assert "LMOCI_MAX_COST_USD" in err, err

    ci_err = run(
        ["--self-test"],
        2,
        {"CI": "true", "OCI_CLI_USER": "ocid1.user.oc1..liveuser"},
    ).stderr
    assert "Refusing to run in CI" in ci_err, ci_err

    run(["--self-test"])

    with tempfile.TemporaryDirectory(prefix="lmoci-cli-") as tmp:
        d = Path(tmp)
        bad = d / "bad.dslb"
        bad.write_bytes(b"not bytecode")
        run(["--lmcode", str(bad)], 3)

        # Memory budget enforcement
        budget_lms = d / "budget.lms"
        budget_dslb = d / "budget.dslb"
        budget_lms.write_text(
            "p = _dsl_ schema="
            + json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))
            + ";\nlet i = 0; while (i < 10000) { let data = str_repeat(\"x\", 1000000); i = i + 1; }\n"
            + "end _dsl_\nsavedsl(p, "
            + json.dumps(str(budget_dslb))
            + ");\n",
            encoding="utf-8",
        )
        comp = subprocess.run([lmscript, str(budget_lms)], text=True, capture_output=True, timeout=60, check=False)
        assert comp.returncode == 0, comp.stderr
        small = run(["--lmcode", str(budget_dslb)], 3, {"LMOCI_MAX_MEMORY_MB": "64"})
        assert "LMOCI_MAX_MEMORY_MB" in small.stderr and "memory budget" in small.stdout

        # --dry-run enforcement even when pointing at an unreachable endpoint
        dry_lms = d / "dry.lms"
        dry_dslb = d / "dry.dslb"
        dry_lms.write_text(
            "p = _dsl_ schema="
            + json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))
            + ";\nlet cloud = oci.endpoint(\"us-ashburn-1\", \"http://127.0.0.1:9\");\n"
            + "check(cloud.is_demo());\n"
            + "check(cloud.buckets().len() == 1);\n"
            + "let owned = cloud.run(\"dry01\", "
            + json.dumps(str(d / "ledgers"))
            + ");\nlet b = owned.bucket(\"safe\");\ncheck(owned.remaining() == 1);\n"
            + "owned.cleanup();\ncheck(owned.remaining() == 0);\nend _dsl_\n"
            + "savedsl(p, "
            + json.dumps(str(dry_dslb))
            + ");\n",
            encoding="utf-8",
        )
        comp = subprocess.run([lmscript, str(dry_lms)], text=True, capture_output=True, timeout=60, check=False)
        assert comp.returncode == 0, comp.stderr
        run(["--dry-run", "--lmcode", str(dry_dslb)], 0, {"LMOCI_MAX_MEMORY_MB": "128"})

        # Compile and run all examples/*.lms in an isolated working copy
        work = d / "examples-work"
        work.mkdir()
        (work / "schema").mkdir()
        (work / "examples").mkdir()
        shutil.copyfile(ROOT / "schema" / "lmoci.dsl.json", work / "schema" / "lmoci.dsl.json")
        for example in sorted((ROOT / "examples").glob("*.lms")):
            target_example = work / "examples" / example.name
            shutil.copyfile(example, target_example)
            comp = subprocess.run(
                [lmscript, str(target_example)],
                cwd=str(work),
                text=True,
                capture_output=True,
                timeout=60,
                check=False,
            )
            assert comp.returncode == 0, (example.name, comp.stderr, comp.stdout)
        for dslb in sorted((work / "data").glob("*.dslb")):
            res = subprocess.run(
                host_cmd() + ["--lmcode", str(dslb)],
                cwd=str(work),
                env=BASE_ENV,
                text=True,
                capture_output=True,
                timeout=60,
                check=False,
            )
            assert res.returncode == 0, (dslb.name, res.returncode, res.stdout, res.stderr)

    print("PASS test-cli.py: CLI, schema/signature parity, budgets, dry-run, and examples")


if __name__ == "__main__":
    main()
