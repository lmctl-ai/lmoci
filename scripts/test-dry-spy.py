#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Prove that `lmscript --dry-run` reaches lmoci as a dry invocation, can
evaluate cloud.last_error().code() (hostkit v3 + lmcode-go Registry.Enum), and
makes ZERO mutating provider requests: a loopback HTTP spy answers OCI read routes
and records every request. A real run of the same program must POST (control)."""
import http.server, json, os, subprocess, sys, tempfile, threading

from lmscript_runner import command as lmscript_command

LMSCRIPT, HOST, SCHEMA = sys.argv[1], sys.argv[2], sys.argv[3]
LMSCRIPT_CMD = lmscript_command(LMSCRIPT)
seen = []

class Spy(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
    def handle_any(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n: self.rfile.read(n)
        seen.append((self.command, self.path))
        path = self.path.split("?")[0]
        if self.command in ("POST", "PUT", "DELETE"):
            return self.reply(500, {"code": "InternalServerError", "message": "spy refuses mutations"})
        if path == "/n/":
            return self.reply(200, "floci-local")
        if path == "/20210201/queues":
            return self.reply(200, {"items": []})
        return self.reply(404, {"code": "BucketNotFound", "message": "spy: nothing exists"})
    do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = handle_any

srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Spy)
threading.Thread(target=srv.serve_forever, daemon=True).start()
ep = f"http://127.0.0.1:{srv.server_address[1]}"
prog = f'''
s = load_schema(schema={json.dumps(SCHEMA)});
p = _lmcode_ s;
  cloud.set_endpoint("all", {json.dumps(ep)});
  let created = cloud.bucket_create("lmoci-dry-spy");
  let code_ok = cloud.last_error().code() == ErrorCode.OK;
  let m = cloud.object_put("lmoci-dry-spy", "k.txt", "payload", "text/plain", str_map().set("env", "dev"));
  let body = cloud.object_get("lmoci-dry-spy", "k.txt");
  cloud.object_delete("lmoci-dry-spy", "k.txt");
  let deleted = cloud.bucket_delete("lmoci-dry-spy");
  let qc = cloud.queue_create("lmoci-dry-spy-q");
  let id = cloud.queue_send("lmoci-dry-spy-q", "hello");
  let batch = cloud.queue_receive("lmoci-dry-spy-q", 1, 0, 30);
  let qd = cloud.queue_delete("lmoci-dry-spy-q");
  lmcode_return_put("created", created);
  lmcode_return_put("code_ok", code_ok);
  lmcode_return_put("body", body);
  lmcode_return_put("qc", qc);
  lmcode_return_put("n", batch.len());
end _lmcode_
r = run_lmcode(p, {{command:[{json.dumps(HOST)}, "--lmcode", "$DSLFILE"]}});
println(json_stringify(r));
'''
with tempfile.TemporaryDirectory() as d:
    f = os.path.join(d, "dry.lms")
    open(f, "w").write(prog)
    dry = subprocess.run(LMSCRIPT_CMD + ["--dry-run", f], capture_output=True, text=True, timeout=120)
    dry_seen = list(seen)
    del seen[:]
    real = subprocess.run(LMSCRIPT_CMD + [f], capture_output=True, text=True, timeout=120)
    real_seen = list(seen)
srv.shutdown()

mut = [s for s in dry_seen if s[0] in ("POST", "PUT", "DELETE")]
print(f"dry run: rc={dry.returncode} requests={len(dry_seen)} mutating={len(mut)}")
code_ok = '"code_ok":true' in dry.stdout
print(f"dry run evaluated last_error().code() == ErrorCode.OK: {code_ok}")
if dry.returncode != 0 or mut or not dry_seen or '"dry":true' not in dry.stdout or not code_ok:
    print(dry.stdout, dry.stderr, dry_seen, file=sys.stderr)
    sys.exit("FAIL: dry run made mutating requests or did not reach the host in dry mode")
if not any(s[0] == "POST" for s in real_seen):
    print(real.stdout, real.stderr, real_seen, file=sys.stderr)
    sys.exit("FAIL: control real run did not attempt a mutation (spy is blind)")
print(f"control real run attempted {sum(1 for s in real_seen if s[0] in ('POST','PUT','DELETE'))} mutating request(s)")
print("PASS: lmscript --dry-run made zero mutating OCI requests")
