#!/usr/bin/env python3
"""CLI, schema parity, failure codes, examples, and managed memory enforcement."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from lmscript_runner import command as lmscript_command
import tempfile


ROOT = Path(__file__).resolve().parents[1]
HOST = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (ROOT / "target" / "lmoci-0.2.0-all.jar")
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


def lmscript_host_env() -> dict[str, str]:
    command = host_cmd()
    env = dict(BASE_ENV)
    env["LMOCI_HOST"] = command[0]
    env["LMOCI_HOST_ARG1"] = command[1] if len(command) > 1 else ""
    env["LMOCI_HOST_ARG2"] = command[2] if len(command) > 2 else ""
    return env


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
    assert run(["--version"]).stdout == "lmoci 0.2.0\n"
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

    lmscript = os.environ.get("LMOCI_LMSCRIPT_BIN") or shutil.which("lmscript")
    assert lmscript is not None, "lmscript must be on PATH"
    lmscript_cmd = lmscript_command(lmscript)
    sig_proc = subprocess.run(
        lmscript_cmd + ["--lmcode-signatures", str(ROOT / "schema" / "lmoci.dsl.json")],
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=45,
        check=False,
    )
    assert sig_proc.returncode == 0, sig_proc.stderr
    assert sig_proc.stdout == run(["--lmcode-signatures"]).stdout, "schema/registry signature mismatch"

    host_sig_proc = subprocess.run(
        lmscript_cmd + ["--lmcode-signatures", str(ROOT / "schema" / "lmoci.dsl.json"), "--without-std"],
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

        # Memory budget enforcement through the version-1 run_lmcode manifest.
        budget_lms = d / "budget.lms"
        command_literal = json.dumps(host_cmd() + ["--lmcode", "$DSLFILE"])
        budget_lms.write_text(
            f'p = _lmcode_ schema={json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))};\n'
            'let i = 0; while (i < 10000) { let data = str_repeat("x", 1000000); i = i + 1; }\n'
            'end _lmcode_\n'
            f'r = run_lmcode(p, {{command: {command_literal}}});\n'
            'write_file("budget-result.json", json_stringify(r));\n',
            encoding="utf-8",
        )
        small_env = dict(BASE_ENV, LMOCI_MAX_MEMORY_MB="64", HOME=str(d / "budget-home"))
        Path(small_env["HOME"]).mkdir()
        comp = subprocess.run(lmscript_cmd + [str(budget_lms)], cwd=d, env=small_env,
                              text=True, capture_output=True, check=False)
        assert comp.returncode == 0, (comp.stdout, comp.stderr)
        budget = json.loads((d / "budget-result.json").read_text(encoding="utf-8"))
        assert budget.get("ok") is False and "memory budget" in budget.get("message", ""), budget

        # The strict host protocol and callback dry controls run in test-host-protocol.py.
        # Compile and execute every example in an isolated working copy through run_lmcode.
        work = d / "examples-work"
        work.mkdir()
        (work / "schema").mkdir()
        shutil.copyfile(ROOT / "schema" / "lmoci.dsl.json", work / "schema" / "lmoci.dsl.json")
        shutil.copytree(ROOT / "examples", work / "examples")
        script_env = lmscript_host_env()
        for example in sorted((work / "examples").glob("*.lms")):
            comp = subprocess.run(lmscript_cmd + [str(example)], cwd=work, env=script_env,
                                  text=True, capture_output=True, check=False)
            assert comp.returncode == 0, (example.name, comp.stderr, comp.stdout)

    print("PASS test-cli.py: schema/signature parity, memory budget, direct CLI and lmcode examples")


if __name__ == "__main__":
    main()
