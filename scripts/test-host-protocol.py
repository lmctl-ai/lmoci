#!/usr/bin/env python3
"""Offline lmcode host protocol, strict manifest and dry callback controls."""
import json
import os
from pathlib import Path
import subprocess
import sys

from lmscript_runner import command as lmscript_command
import tempfile


def host_command(host):
    path = str(Path(host).resolve())
    if path.endswith(".jar"):
        return ["java", "-jar", path]
    if path.lower().endswith(".exe") or os.access(path, os.X_OK):
        return [path]
    raise AssertionError(f"host is not executable: {path}")


host = host_command(sys.argv[1])
lmscript = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("LMSCRIPT_BIN", "lmscript")
lmscript_cmd = lmscript_command(lmscript)
root = Path(__file__).resolve().parents[1]
schema_file = root / "schema" / "lmoci.dsl.json"


def host_run(args, code=0):
    proc = subprocess.run(host + args, cwd=root, text=True, capture_output=True, check=False)
    assert proc.returncode == code, (args, proc.returncode, proc.stdout, proc.stderr)
    return proc


schema_bytes = host_run(["--lmcode-schema", "--json"]).stdout.encode("utf-8")
assert schema_bytes == schema_file.read_bytes(), "embedded lmcode schema differs byte-for-byte"
signatures = host_run(["--lmcode-signatures"]).stdout
proc = subprocess.run(lmscript_cmd + ["--lmcode-signatures", str(schema_file)], cwd=root,
                      text=True, capture_output=True, check=False)
assert proc.returncode == 0, proc.stderr
assert signatures == proc.stdout, "registry signatures differ from the published schema"

# Retired spellings fail with a CLI frame before they can dispatch a program.
for args in (["--schema"], ["--cli-schema"], ["--dry-run", "--lmcode", "unused.dslb"]):
    result = host_run(list(args), 2)
    lines = result.stdout.splitlines()
    assert len(lines) == 3 and lines[0] == "LMCODE_RETURN_BEGIN" and lines[2] == "LMCODE_RETURN_END", (args, result.stdout)
    frame = json.loads(lines[1])
    assert frame.get("ok") is False and frame.get("phase") == "cli" and frame.get("code") == "CLI_USAGE", frame

wrapper_code = r'''import json, os, subprocess, sys
from pathlib import Path
host = json.loads(sys.argv[1]); mode, *args = sys.argv[2:]
source = Path(os.environ["LMCODE_INVOCATION_FILE"])
manifest = json.loads(source.read_text(encoding="utf-8"))
assert manifest["version"] == 1 and manifest["mode"] == "single" and isinstance(manifest.get("dry"), bool), manifest
if mode == "dry": manifest["dry"] = True
elif mode == "missing": manifest.pop("dry", None)
elif mode == "nonboolean": manifest["dry"] = "yes"
copy = Path.cwd() / (mode + "-manifest.json")
copy.write_text(json.dumps(manifest), encoding="utf-8")
env = os.environ.copy(); env["LMCODE_INVOCATION_FILE"] = str(copy.resolve())
run_dir = Path.cwd() / (mode + "-work"); run_dir.mkdir(exist_ok=True)
with (Path.cwd() / (mode + "-calls.txt")).open("a", encoding="utf-8") as f: f.write("host\n")
child = subprocess.run(host + args, cwd=run_dir, env=env, capture_output=True)
lines = [line.rstrip(b"\r") for line in child.stdout.splitlines()]
starts = [i for i,x in enumerate(lines) if x == b"LMCODE_RETURN_BEGIN"]
ends = [i for i,x in enumerate(lines) if x == b"LMCODE_RETURN_END"]
assert len(starts) == len(ends) == 1 and ends[0] == starts[0] + 2, (child.returncode, child.stdout, child.stderr)
frame = json.loads(lines[starts[0] + 1])
(Path.cwd() / (mode + "-frame.json")).write_text(json.dumps(frame), encoding="utf-8")
if mode == "dry" and frame.get("ok"):
    frame.pop("dry", None)  # The outer lmscript call requested real mode; retain the raw host frame above.
out = b"LMCODE_RETURN_BEGIN\n" + json.dumps(frame, separators=(",", ":")).encode() + b"\nLMCODE_RETURN_END\n"
sys.stdout.buffer.write(out); sys.stderr.buffer.write(child.stderr); sys.exit(child.returncode)
'''

with tempfile.TemporaryDirectory(prefix="lmoci-host-protocol-") as scratch:
    work = Path(scratch)
    wrapper = work / "host-wrapper.py"
    driver = work / "protocol.lms"
    wrapper.write_text(wrapper_code, encoding="utf-8")
    schema_literal = json.dumps(str(schema_file))
    host_literal = json.dumps(host)
    wrapper_literal = json.dumps(str(wrapper))
    python_literal = json.dumps(sys.executable)
    driver.write_text(
        f"HOST = {host_literal}; WRAPPER = {wrapper_literal}; PYTHON = {python_literal};\n"
        f"p = _lmcode_ schema={schema_literal};\n"
        'file_write("effect-sentinel.txt", "callback ran");\n'
        'let cloud = oci.demo();\n'
        'lmcode_return_put("demo", cloud.is_demo());\n'
        'lmcode_return_put("namespace", cloud.namespace());\n'
        'end _lmcode_\n'
        'real = run_lmcode(p, {command: [PYTHON, WRAPPER, json_stringify(HOST), "real", "--lmcode", "$DSLFILE"]});\n'
        'write_file("real-result.json", json_stringify(real));\n'
        'dry = run_lmcode(p, {command: [PYTHON, WRAPPER, json_stringify(HOST), "dry", "--lmcode", "$DSLFILE"]});\n'
        'write_file("dry-result.json", json_stringify(dry));\n'
        'missing = run_lmcode(p, {command: [PYTHON, WRAPPER, json_stringify(HOST), "missing", "--lmcode", "$DSLFILE"]});\n'
        'write_file("missing-result.json", json_stringify(missing));\n'
        'nonboolean = run_lmcode(p, {command: [PYTHON, WRAPPER, json_stringify(HOST), "nonboolean", "--lmcode", "$DSLFILE"]});\n'
        'write_file("nonboolean-result.json", json_stringify(nonboolean));\n'
        'q = _lmcode_ schema=' + schema_literal + ';\n'
        'lmcode_return_put("part", "second");\n'
        'end _lmcode_\n'
        'builder = lmcode_build(HOST); builder.add(p); builder.add(q);\n'
        'built = run_lmcode(builder); write_file("builder-result.json", json_stringify(built));\n',
        encoding="utf-8",
    )
    env = dict(os.environ)
    env.pop("LMOCI_LIVE", None)
    env.pop("OCI_ENDPOINT", None)
    env["HOME"] = str(work / "home")
    env["LMCTL_HOME"] = str(work / "home" / ".lmctl")
    Path(env["HOME"]).mkdir()
    compiler = subprocess.run(lmscript_cmd + [str(driver)], cwd=work, env=env, capture_output=True, text=True)
    assert compiler.returncode == 0, (compiler.returncode, compiler.stdout, compiler.stderr)

    real = json.loads((work / "real-result.json").read_text(encoding="utf-8"))
    assert real["ok"] is True and isinstance(real.get("result"), dict), real
    assert (work / "real-work" / "effect-sentinel.txt").read_text(encoding="utf-8") == "callback ran"

    dry = json.loads((work / "dry-result.json").read_text(encoding="utf-8"))
    dry_frame = json.loads((work / "dry-frame.json").read_text(encoding="utf-8"))
    assert dry["ok"] is True, dry
    assert dry_frame == {"ok": True, "result": {"demo": True, "namespace": ""}, "dry": True}, dry_frame
    assert not (work / "dry-work" / "effect-sentinel.txt").exists()
    assert (work / "dry-calls.txt").read_text(encoding="utf-8").splitlines() == ["host"]

    for mode in ("missing", "nonboolean"):
        result = json.loads((work / f"{mode}-result.json").read_text(encoding="utf-8"))
        frame = json.loads((work / f"{mode}-frame.json").read_text(encoding="utf-8"))
        assert result.get("ok") is False and result.get("phase") == "cli" and result.get("code") == "CLI_USAGE", result
        assert frame.get("ok") is False and frame.get("phase") == "cli" and frame.get("code") == "CLI_USAGE", frame
        assert not (work / f"{mode}-work" / "effect-sentinel.txt").exists()
        assert (work / f"{mode}-calls.txt").read_text(encoding="utf-8").splitlines() == ["host"]

    builder = json.loads((work / "builder-result.json").read_text(encoding="utf-8"))
    assert builder.get("ok") is True and builder.get("parts") == 2 and len(builder.get("result", [])) == 2, builder

print("PASS lmcode host schema/signatures, strict manifest v1, dry effects, builder results and retired-flag refusal")
