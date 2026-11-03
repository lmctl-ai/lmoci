#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Prove a strict manifest dry invocation does not issue mutating OCI requests."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import sys

from lmscript_runner import command as lmscript_command
import tempfile
import threading

LMSCRIPT, HOST, SCHEMA = sys.argv[1], sys.argv[2], str(Path(sys.argv[3]).resolve())
LMSCRIPT_CMD = lmscript_command(LMSCRIPT)
seen = []

class Spy(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
    def handle_any(self):
        size = int(self.headers.get("Content-Length") or 0)
        if size:
            self.rfile.read(size)
        seen.append((self.command, self.path))
        path = self.path.split("?", 1)[0]
        if self.command in ("POST", "PUT", "DELETE"):
            if self.command == "PUT" and "/o/" in path:
                return self.reply(200, {})
            return self.reply(500, {"code": "InternalServerError", "message": "spy refuses mutations"})
        if path in ("/n", "/n/"):
            return self.reply(200, "floci-local")
        if path == "/20210201/queues":
            return self.reply(200, {"items": []})
        return self.reply(404, {"code": "BucketNotFound", "message": "spy: nothing exists"})
    do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = handle_any


def host_command():
    if str(HOST).endswith(".jar"):
        return ["java", "-jar", str(Path(HOST).resolve())]
    return [str(Path(HOST).resolve())]

wrapper_source = r'''import json, os, subprocess, sys
from pathlib import Path
host = json.loads(sys.argv[1]); mode, *args = sys.argv[2:]
source = Path(os.environ["LMCODE_INVOCATION_FILE"])
manifest = json.loads(source.read_text(encoding="utf-8"))
assert manifest.get("version") == 1 and manifest.get("mode") == "single", manifest
assert manifest.get("dry") is False, manifest
manifest["dry"] = mode == "dry"
copy = Path.cwd() / (mode + "-invocation.json")
copy.write_text(json.dumps(manifest), encoding="utf-8")
env = os.environ.copy(); env["LMCODE_INVOCATION_FILE"] = str(copy.resolve())
run_dir = Path.cwd() / (mode + "-work"); run_dir.mkdir(exist_ok=True)
child = subprocess.run(host + args, cwd=run_dir, env=env, capture_output=True)
lines = [line.rstrip(b"\r") for line in child.stdout.splitlines()]
starts = [i for i,x in enumerate(lines) if x == b"LMCODE_RETURN_BEGIN"]
ends = [i for i,x in enumerate(lines) if x == b"LMCODE_RETURN_END"]
assert len(starts) == len(ends) == 1 and ends[0] == starts[0] + 2, (child.returncode, child.stdout, child.stderr)
frame = json.loads(lines[starts[0] + 1])
(Path.cwd() / (mode + "-frame.json")).write_text(json.dumps(frame), encoding="utf-8")
if mode == "dry" and frame.get("ok"):
    frame.pop("dry", None)  # run_lmcode submitted dry:false; retain the raw host frame above.
out = b"LMCODE_RETURN_BEGIN\n" + json.dumps(frame, separators=(",", ":")).encode() + b"\nLMCODE_RETURN_END\n"
sys.stdout.buffer.write(out); sys.stderr.buffer.write(child.stderr); sys.exit(child.returncode)
'''

server = ThreadingHTTPServer(("127.0.0.1", 0), Spy)
endpoint = f"http://127.0.0.1:{server.server_address[1]}"
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix="lmoci-dry-spy-") as scratch:
        work = Path(scratch)
        wrapper = work / "host-wrapper.py"
        wrapper.write_text(wrapper_source, encoding="utf-8")
        program = f'''p = _lmcode_ schema={json.dumps(SCHEMA)};
let cloud = oci.endpoint("us-ashburn-1", {json.dumps(endpoint)});
cloud.object_put("lmoci-dry-spy", "k.txt", "payload");
end _lmcode_
'''
        env = dict(os.environ)
        for key in ("LMOCI_LIVE", "LMCLOUD_ENDPOINT", "LMCLOUD_STORAGE_ENDPOINT", "LMCLOUD_QUEUE_ENDPOINT",
                    "LMOCI_ENDPOINT", "LMOCI_STORAGE_ENDPOINT", "LMOCI_QUEUE_ENDPOINT", "OCI_ENDPOINT",
                    "OCI_OBJECT_STORAGE_ENDPOINT", "OCI_QUEUE_ENDPOINT"):
            env.pop(key, None)
        env["HOME"] = str(work / "home")
        env["LMCTL_HOME"] = str(work / "home/.lmctl")
        env["LMOCI_ENDPOINT"] = endpoint
        Path(env["HOME"]).mkdir()

        def run_mode(mode):
            driver = work / f"{mode}-dry-spy.lms"
            driver.write_text(
                f"HOST = {json.dumps(host_command())}; WRAPPER = {json.dumps(str(wrapper))}; PYTHON = {json.dumps(sys.executable)};\n"
                + program
                + f'r = run_lmcode(p, {{command: [PYTHON, WRAPPER, json_stringify(HOST), "{mode}", "--lmcode", "$DSLFILE"]}});\n'
                + f'write_file("{mode}-result.json", json_stringify(r));\n',
                encoding="utf-8",
            )
            first = len(seen)
            proc = subprocess.run(LMSCRIPT_CMD + [str(driver)], cwd=work, env=env, text=True, capture_output=True, check=False)
            assert proc.returncode == 0, (mode, proc.stdout, proc.stderr)
            calls = list(seen[first:])
            result = json.loads((work / f"{mode}-result.json").read_text(encoding="utf-8"))
            frame = json.loads((work / f"{mode}-frame.json").read_text(encoding="utf-8"))
            return result, frame, calls

        dry_result, dry_frame, dry_seen = run_mode("dry")
        dry_mutations = [request for request in dry_seen if request[0] in ("POST", "PUT", "DELETE")]
        assert dry_result.get("ok") is True, dry_result
        assert dry_frame.get("dry") is True and dry_frame.get("ok") is True, dry_frame
        assert not dry_mutations, dry_mutations
        assert not (work / "dry-work" / "lmoci-dry-spy").exists()

        real_result, real_frame, real_seen = run_mode("real")
        real_mutations = [request for request in real_seen if request[0] in ("POST", "PUT", "DELETE")]
        assert real_result.get("ok") is True and real_frame.get("ok") is True, (real_result, real_frame)
        assert real_mutations, ("real-mode control did not attempt any mutation", real_result, real_frame, real_seen)
        print(f"PASS manifest dry made zero mutations; real control attempted {len(real_mutations)} mutation request(s)")
finally:
    server.shutdown()
