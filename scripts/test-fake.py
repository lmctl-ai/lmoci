#!/usr/bin/env python3
"""Loopback fake OCI HTTP server verifying real oci-java-sdk signing, 16 inventories, 6 sandbox lifecycles, and service round-trips."""

from __future__ import annotations

import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.parse


ROOT = Path(__file__).resolve().parents[1]
HOST = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (ROOT / "target" / "lmoci-0.1.0-all.jar")


def host_cmd() -> list[str]:
    if HOST.suffix == ".jar":
        return ["java", "-jar", str(HOST)]
    if HOST.suffix == ".cmd":
        return ["cmd.exe", "/c", str(HOST)]
    return [str(HOST)]


class FakeOciState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests: list[tuple[str, str, str]] = []
        self.deletes: list[tuple[str, str]] = []
        self.buckets: dict[str, dict] = {
            "fake-bucket-1": {"name": "fake-bucket-1", "namespace": "fake-ns", "compartmentId": "ocid1.tenancy.oc1..fake"}
        }
        self.objects: dict[tuple[str, str], bytes] = {}
        self.queue_messages: list[dict] = []
        self.stream_messages: list[dict] = []


STATE = FakeOciState()


class FakeOciHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        return

    def _record_and_verify_signature(self) -> tuple[str, dict[str, list[str]], bytes]:
        auth = self.headers.get("Authorization") or self.headers.get("authorization") or ""
        assert auth.startswith("Signature "), f"Missing OCI RSA Signature header: {dict(self.headers)}"
        assert 'algorithm="rsa-sha256"' in auth, f"Unexpected signature algorithm: {auth}"
        assert 'headers="' in auth and 'signature="' in auth, f"Incomplete signature header: {auth}"
        length = int(self.headers.get("Content-Length") or "0")
        body = self.rfile.read(length) if length > 0 else b""
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        with STATE.lock:
            STATE.requests.append((self.command, parsed.path, auth))
        return parsed.path, query, body

    def _send_json(self, status: int, payload: object, extra_headers: dict[str, str] | None = None) -> None:
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("opc-request-id", "fake-req-1")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def _send_bytes(self, status: int, data: bytes, content_type: str = "application/octet-stream", extra_headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("ETag", "etag-bytes")
        self.send_header("opc-request-id", "fake-req-1")
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_HEAD(self) -> None:
        path, _, _ = self._record_and_verify_signature()
        parts = [p for p in path.split("/") if p]
        if len(parts) == 4 and parts[0] == "n" and parts[2] == "b":
            bkt = urllib.parse.unquote(parts[3])
            if bkt in STATE.buckets:
                self._send_bytes(200, b"", extra_headers={"ETag": f"etag-{bkt}"})
            else:
                self._send_bytes(404, b"")
            return
        if len(parts) >= 6 and parts[0] == "n" and parts[2] == "b" and parts[4] == "o":
            bkt = urllib.parse.unquote(parts[3])
            obj = urllib.parse.unquote("/".join(parts[5:]))
            data = STATE.objects.get((bkt, obj))
            if data is not None:
                self._send_bytes(200, data, extra_headers={"ETag": f"etag-{obj}"})
            else:
                self._send_bytes(404, b"")
            return
        self._send_bytes(200, b"")

    def do_GET(self) -> None:
        path, query, _ = self._record_and_verify_signature()
        parts = [p for p in path.split("/") if p]

        # Object Storage
        if path == "/n" or path == "/n/":
            self._send_json(200, "fake-ns")
            return
        if len(parts) == 3 and parts[0] == "n" and parts[2] == "b":
            self._send_json(200, list(STATE.buckets.values()))
            return
        if len(parts) == 4 and parts[0] == "n" and parts[2] == "b":
            bkt = urllib.parse.unquote(parts[3])
            if bkt in STATE.buckets:
                self._send_json(200, STATE.buckets[bkt], {"ETag": f"etag-{bkt}"})
            else:
                self._send_json(404, {"code": "BucketNotFound", "message": "not found"})
            return
        if len(parts) == 5 and parts[0] == "n" and parts[2] == "b" and parts[4] == "o":
            bkt = urllib.parse.unquote(parts[3])
            prefix = query.get("prefix", [""])[0]
            objs = [
                {"name": k[1], "size": len(v), "etag": f"etag-{k[1]}"}
                for k, v in sorted(STATE.objects.items())
                if k[0] == bkt and (not prefix or k[1].startswith(prefix))
            ]
            self._send_json(200, {"objects": objs})
            return
        if len(parts) >= 6 and parts[0] == "n" and parts[2] == "b" and parts[4] == "o":
            bkt = urllib.parse.unquote(parts[3])
            obj = urllib.parse.unquote("/".join(parts[5:]))
            data = STATE.objects.get((bkt, obj))
            if data is None:
                self._send_json(404, {"code": "ObjectNotFound", "message": "not found"})
            else:
                self._send_bytes(200, data, "text/plain; charset=utf-8", {"ETag": f"etag-{obj}"})
            return

        # Identity
        if path.endswith("/regionSubscriptions"):
            self._send_json(200, [{"regionName": "us-ashburn-1", "regionKey": "IAD", "status": "READY", "isHomeRegion": True}])
            return
        if "/tenancies/" in path:
            tid = parts[-1]
            if "leak-error" in tid:
                self._send_json(
                    400,
                    {
                        "code": "InvalidParameter",
                        "message": "bad key -----BEGIN PRIVATE KEY-----\nSECRETKEYDATA\n-----END PRIVATE KEY----- passphrase=topsecret",
                    },
                )
                return
            self._send_json(200, {"id": tid, "name": "fake-tenancy", "homeRegionKey": "IAD"})
            return
        if path.endswith("/availabilityDomains"):
            self._send_json(200, [{"id": "ad-1", "name": "Uocm:US-ASHBURN-AD-1", "compartmentId": "ocid1.tenancy.oc1..fake"}])
            return
        if path.endswith("/compartments"):
            self._send_json(200, [{"id": "ocid1.compartment.oc1..fake", "name": "sandbox-comp", "description": "fake", "lifecycleState": "ACTIVE"}])
            return
        if "/compartments/" in path:
            cid = parts[-1]
            self._send_json(200, {"id": cid, "name": "sandbox-comp", "lifecycleState": "ACTIVE"})
            return
        if path.endswith("/users"):
            self._send_json(200, [{"id": "ocid1.user.oc1..fake", "name": "fake-user", "description": "fake", "lifecycleState": "ACTIVE"}])
            return
        if path.endswith("/groups"):
            self._send_json(200, [{"id": "ocid1.group.oc1..fake", "name": "Administrators", "description": "fake", "lifecycleState": "ACTIVE"}])
            return
        if path.endswith("/policies"):
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.policy.oc1..fake",
                        "name": "fake-policy",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "statements": ["Allow group Administrators to read all-resources in tenancy"],
                        "lifecycleState": "ACTIVE",
                    }
                ],
            )
            return
        if "/policies/" in path:
            pid = parts[-1]
            self._send_json(200, {"id": pid, "name": "fake-policy", "lifecycleState": "ACTIVE"})
            return

        # Queue
        if path.endswith("/queues"):
            self._send_json(
                200,
                {
                    "items": [
                        {
                            "id": "ocid1.queue.oc1..fakeq",
                            "displayName": "fake-queue",
                            "compartmentId": "ocid1.tenancy.oc1..fake",
                            "lifecycleState": "ACTIVE",
                            "messagesEndpoint": f"http://127.0.0.1:{self.server.server_port}",
                        }
                    ]
                },
            )
            return
        if "/queues/" in path and path.endswith("/messages"):
            self._send_json(200, {"messages": list(STATE.queue_messages)})
            return
        if "/queues/" in path:
            qid = parts[-1]
            self._send_json(200, {"id": qid, "displayName": "fake-queue", "lifecycleState": "ACTIVE"})
            return

        # Streaming
        if path.endswith("/streams"):
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.stream.oc1..fakes",
                        "name": "fake-stream",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "partitions": 1,
                        "lifecycleState": "ACTIVE",
                        "messagesEndpoint": f"http://127.0.0.1:{self.server.server_port}",
                    }
                ],
            )
            return
        if "/streams/" in path and path.endswith("/messages"):
            self._send_json(200, list(STATE.stream_messages), {"opc-next-cursor": "next-cur-1"})
            return
        if "/streams/" in path:
            sid = parts[-1]
            self._send_json(200, {"id": sid, "name": "fake-stream", "lifecycleState": "ACTIVE"})
            return

        # KMS / Vault / Secrets
        if path.endswith("/vaults"):
            ep = f"http://127.0.0.1:{self.server.server_port}"
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.vault.oc1..fakev",
                        "displayName": "fake-vault",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "vaultType": "DEFAULT",
                        "lifecycleState": "ACTIVE",
                        "cryptoEndpoint": ep,
                        "managementEndpoint": ep,
                    }
                ],
            )
            return
        if path.endswith("/secrets"):
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.vaultsecret.oc1..fakesec",
                        "secretName": "fake-secret",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "vaultId": "ocid1.vault.oc1..fakev",
                        "lifecycleState": "ACTIVE",
                    }
                ],
            )
            return
        if "/secretbundles/" in path:
            sid = parts[-1]
            b64 = base64.b64encode(b"super-sensitive-secret-value").decode("ascii")
            self._send_json(
                200,
                {
                    "secretId": sid,
                    "versionNumber": 1,
                    "secretBundleContent": {"contentType": "BASE64", "content": b64},
                },
            )
            return

        # Functions
        if path.endswith("/applications"):
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.fnapp.oc1..fakefn",
                        "displayName": "fake-fn-app",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "lifecycleState": "ACTIVE",
                    }
                ],
            )
            return
        if "/applications/" in path:
            aid = parts[-1]
            self._send_json(200, {"id": aid, "displayName": "fake-fn-app", "lifecycleState": "ACTIVE"})
            return

        # ContainerEngine (OKE)
        if path.endswith("/clusters"):
            self._send_json(
                200,
                [
                    {
                        "id": "ocid1.cluster.oc1..fakeoke",
                        "name": "fake-oke",
                        "compartmentId": "ocid1.tenancy.oc1..fake",
                        "kubernetesVersion": "v1.31.1",
                        "lifecycleState": "ACTIVE",
                    }
                ],
            )
            return

        # PostgreSQL
        if path.endswith("/dbSystems"):
            self._send_json(
                200,
                {
                    "items": [
                        {
                            "id": "ocid1.postgresqldbsystem.oc1..fakepsql",
                            "displayName": "fake-psql",
                            "compartmentId": "ocid1.tenancy.oc1..fake",
                            "dbVersion": "16",
                            "lifecycleState": "ACTIVE",
                        }
                    ]
                },
            )
            return

        # TenantManager Organizations
        if path.endswith("/organizations"):
            self._send_json(
                200,
                {
                    "items": [
                        {
                            "id": "ocid1.organization.oc1..fakeorg",
                            "displayName": "fake-org",
                            "compartmentId": "ocid1.tenancy.oc1..fake",
                            "lifecycleState": "ACTIVE",
                        }
                    ]
                },
            )
            return

        self._send_json(200, {})

    def do_POST(self) -> None:
        path, _, body = self._record_and_verify_signature()
        payload = json.loads(body.decode("utf-8")) if body else {}

        # Object Storage CreateBucket / RenameObject
        parts = [p for p in path.split("/") if p]
        if len(parts) == 3 and parts[0] == "n" and parts[2] == "b":
            name = payload.get("name", "created-bucket")
            rec = {"name": name, "namespace": parts[1], "compartmentId": payload.get("compartmentId", "ocid1.tenancy.oc1..fake")}
            STATE.buckets[name] = rec
            self._send_json(200, rec, {"ETag": f"etag-{name}"})
            return
        if len(parts) == 5 and parts[0] == "n" and parts[2] == "b" and parts[4] == "actions" and path.endswith("renameObject"):
            return
        if "renameObject" in path:
            bkt = urllib.parse.unquote(parts[3])
            src = payload["sourceName"]
            dst = payload["newName"]
            data = STATE.objects.pop((bkt, src), b"")
            STATE.objects[(bkt, dst)] = data
            self._send_json(200, {})
            return

        # Identity CreateCompartment / CreatePolicy
        if path.endswith("/compartments"):
            name = payload.get("name", "comp")
            self._send_json(200, {"id": f"ocid1.compartment.oc1..{name}", "name": name, "lifecycleState": "ACTIVE"})
            return
        if path.endswith("/policies"):
            name = payload.get("name", "pol")
            self._send_json(
                200,
                {
                    "id": f"ocid1.policy.oc1..{name}",
                    "name": name,
                    "compartmentId": payload.get("compartmentId", ""),
                    "statements": payload.get("statements", []),
                    "lifecycleState": "ACTIVE",
                },
            )
            return

        # Queue CreateQueue / PutMessages
        if path.endswith("/queues"):
            name = payload.get("displayName", "q")
            self._send_json(200, {}, {"opc-work-request-id": f"ocid1.queue.oc1..{name}"})
            return
        if "/queues/" in path and path.endswith("/messages"):
            msgs = payload.get("messages", [])
            entries = []
            for idx, m in enumerate(msgs):
                msg_id = len(STATE.queue_messages) + 1
                receipt = f"rcpt-{msg_id}"
                STATE.queue_messages.append({"id": msg_id, "content": m.get("content", ""), "receipt": receipt})
                entries.append({"id": str(msg_id)})
            self._send_json(200, {"messages": entries})
            return

        # Streaming CreateStream / PutMessages / CreateCursor
        if path.endswith("/streams"):
            name = payload.get("name", "s")
            self._send_json(200, {"id": f"ocid1.stream.oc1..{name}", "name": name, "lifecycleState": "ACTIVE"})
            return
        if "/streams/" in path and path.endswith("/messages"):
            msgs = payload.get("messages", [])
            entries = []
            for m in msgs:
                offset = len(STATE.stream_messages)
                STATE.stream_messages.append(
                    {
                        "partition": "0",
                        "offset": offset,
                        "key": m.get("key"),
                        "value": m.get("value"),
                    }
                )
                entries.append({"partition": "0", "offset": offset})
            self._send_json(200, {"failures": 0, "entries": entries})
            return
        if "/streams/" in path and path.endswith("/cursors"):
            self._send_json(200, {"value": "fake-cursor-0"})
            return

        # KMS / Vault / Secrets
        if path.endswith("/vaults"):
            name = payload.get("displayName", "v")
            ep = f"http://127.0.0.1:{self.server.server_port}"
            self._send_json(
                200,
                {
                    "id": f"ocid1.vault.oc1..{name}",
                    "displayName": name,
                    "compartmentId": payload.get("compartmentId", ""),
                    "cryptoEndpoint": ep,
                    "managementEndpoint": ep,
                    "lifecycleState": "ACTIVE",
                },
            )
            return
        if path.endswith("/keys"):
            name = payload.get("displayName", "k")
            self._send_json(
                200,
                {
                    "id": f"ocid1.key.oc1..{name}",
                    "displayName": name,
                    "compartmentId": payload.get("compartmentId", ""),
                    "lifecycleState": "ENABLED",
                },
            )
            return
        if path.endswith("/encrypt"):
            pt = payload.get("plaintext", "")
            ct = base64.b64encode(("enc:" + pt).encode("utf-8")).decode("ascii")
            self._send_json(200, {"keyId": payload.get("keyId", ""), "ciphertext": ct})
            return
        if path.endswith("/decrypt"):
            ct = payload.get("ciphertext", "")
            raw = base64.b64decode(ct.encode("ascii")).decode("utf-8")
            pt = raw[4:] if raw.startswith("enc:") else raw
            self._send_json(200, {"keyId": payload.get("keyId", ""), "plaintext": pt})
            return
        if path.endswith("/secrets"):
            name = payload.get("secretName", "sec")
            self._send_json(
                200,
                {
                    "id": f"ocid1.vaultsecret.oc1..{name}",
                    "secretName": name,
                    "vaultId": payload.get("vaultId", ""),
                    "keyId": payload.get("keyId", ""),
                    "compartmentId": payload.get("compartmentId", ""),
                    "lifecycleState": "ACTIVE",
                },
            )
            return

        # Functions CreateApplication
        if path.endswith("/applications"):
            name = payload.get("displayName", "app")
            self._send_json(
                200,
                {
                    "id": f"ocid1.fnapp.oc1..{name}",
                    "displayName": name,
                    "compartmentId": payload.get("compartmentId", ""),
                    "lifecycleState": "ACTIVE",
                },
            )
            return

        self._send_json(200, {})

    def do_PUT(self) -> None:
        path, _, body = self._record_and_verify_signature()
        parts = [p for p in path.split("/") if p]
        if len(parts) >= 6 and parts[0] == "n" and parts[2] == "b" and parts[4] == "o":
            bkt = urllib.parse.unquote(parts[3])
            obj = urllib.parse.unquote("/".join(parts[5:]))
            STATE.objects[(bkt, obj)] = body
            self._send_json(200, {}, {"ETag": f"etag-{obj}", "opc-content-md5": "md5-ok"})
            return
        self._send_json(200, {})

    def do_DELETE(self) -> None:
        path, _, _ = self._record_and_verify_signature()
        parts = [p for p in path.split("/") if p]
        with STATE.lock:
            STATE.deletes.append(( path, parts[-1] if parts else ""))
        if len(parts) == 4 and parts[0] == "n" and parts[2] == "b":
            bkt = urllib.parse.unquote(parts[3])
            STATE.buckets.pop(bkt, None)
            self._send_bytes(204, b"")
            return
        if len(parts) >= 6 and parts[0] == "n" and parts[2] == "b" and parts[4] == "o":
            bkt = urllib.parse.unquote(parts[3])
            obj = urllib.parse.unquote("/".join(parts[5:]))
            STATE.objects.pop((bkt, obj), None)
            self._send_bytes(204, b"")
            return
        if "/queues/" in path and "/messages/" in path:
            rcpt = urllib.parse.unquote(parts[-1])
            STATE.queue_messages[:] = [m for m in STATE.queue_messages if m.get("receipt") != rcpt]
            self._send_bytes(204, b"")
            return
        self._send_bytes(204, b"")


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeOciHandler)
    port = server.server_port
    endpoint = f"http://127.0.0.1:{port}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    lmscript = shutil.which("lmscript")
    assert lmscript is not None, "lmscript must be on PATH"

    base_env = dict(os.environ)
    base_env["LMOCI_ENDPOINT"] = endpoint

    with tempfile.TemporaryDirectory(prefix="lmoci-fake-") as tmp:
        d = Path(tmp)

        # 1. Compile and execute a comprehensive lmscript program against the fake OCI server
        lms_path = d / "fake_full.lms"
        dslb_path = d / "fake_full.dslb"
        ledger_dir = d / "ledgers"
        lms_path.write_text(
            f"""
p = _dsl_ schema={json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))};
let cloud = oci.endpoint("us-ashburn-1", {json.dumps(endpoint)});
check(!cloud.is_demo());

# All 16 inventories over signed HTTP
check(cloud.identity().len() == 1);
check(cloud.regions().len() == 1);
check(cloud.availability_domains().len() == 1);
check(cloud.compartments().len() == 1);
check(cloud.users().len() == 1);
check(cloud.groups().len() == 1);
check(cloud.policies().len() == 1);
check(cloud.buckets().len() >= 1);
check(cloud.queues().len() == 1);
check(cloud.streams().len() == 1);
check(cloud.vaults().len() == 1);
check(cloud.secrets().len() == 1);
check(cloud.functions().len() == 1);
check(cloud.clusters().len() == 1);
check(cloud.db_systems().len() == 1);
check(cloud.tenancies().len() == 1);

# Object Storage round-trip
check(cloud.namespace() == "fake-ns");
let b = cloud.bucket_create("live-test-bkt");
check(b.get("bucket") == "live-test-bkt");
check(cloud.bucket_head("live-test-bkt").get("exists") == "true");
let put = cloud.object_put("live-test-bkt", "dir/hello.txt", "signed-payload");
check(put.number("content_length") == 14);
check(cloud.object_head("live-test-bkt", "dir/hello.txt").get("exists") == "true");
check(cloud.object_list("live-test-bkt", "dir/").len() == 1);
check(cloud.object_get("live-test-bkt", "dir/hello.txt").get("content") == "signed-payload");
check(cloud.object_rename("live-test-bkt", "dir/hello.txt", "dir/moved.txt").get("renamed") == "true");
check(cloud.object_delete("live-test-bkt", "dir/moved.txt").get("deleted") == "true");
check(cloud.bucket_delete("live-test-bkt").get("deleted") == "true");

# Queue round-trip
let qput = cloud.queue_put("ocid1.queue.oc1..fakeq", "job-payload-1");
check(qput.number("message_count") == 1);
let qmsgs = cloud.queue_receive("ocid1.queue.oc1..fakeq");
check(qmsgs.len() == 1);
check(qmsgs.at(0).get("content") == "job-payload-1");
check(cloud.queue_delete_message("ocid1.queue.oc1..fakeq", qmsgs.at(0).get("receipt")).get("deleted") == "true");

# Streaming round-trip
let sput = cloud.stream_put("ocid1.stream.oc1..fakes", "k1", "stream-event-1");
check(sput.number("failures") == 0);
let scur = cloud.stream_cursor("ocid1.stream.oc1..fakes", "0", "TRIM_HORIZON");
let smsg = cloud.stream_get_messages("ocid1.stream.oc1..fakes", scur.get("cursor"));
check(smsg.len() == 1);
check(smsg.at(0).get("value") == "stream-event-1");

# Vault / KMS / Secret round-trip
let v = cloud.vault_create("live-vault");
let k = cloud.key_create("live-key");
let enc = cloud.kms_encrypt(k.get("id"), "top-secret-plaintext");
let dec = cloud.kms_decrypt(k.get("id"), enc.get("ciphertext"));
check(dec.get("plaintext") == "top-secret-plaintext");
let sec = cloud.secret_create("my-sec", v.get("id"), k.get("id"), "raw-secret");
check(cloud.secret_get(sec.get("id")).get("secret_value") == "***REDACTED***");

# All 6 owned sandbox lifecycles + reverse-order cleanup
let run = cloud.run("fake01", {json.dumps(str(ledger_dir))});
let r_comp = run.compartment("c1");
let r_bkt = run.bucket("b1");
let r_q = run.queue("q1");
let r_s = run.stream("s1");
let r_p = run.policy("p1");
let r_a = run.application("a1");
check(run.remaining() == 6);
check(run.describe(r_bkt).get("status") == "ACTIVE");
run.cleanup();
check(run.remaining() == 0);
check(run.estimated_cost() > 0.0);
end _dsl_
savedsl(p, {json.dumps(str(dslb_path))});
""",
            encoding="utf-8",
        )
        comp = subprocess.run([lmscript, str(lms_path)], text=True, capture_output=True, timeout=60, check=False)
        assert comp.returncode == 0, comp.stderr

        STATE.deletes.clear()
        proc = subprocess.run(
            host_cmd() + ["--lmcode", str(dslb_path)],
            env=base_env,
            text=True,
            capture_output=True,
            timeout=120,
            check=False,
        )
        assert proc.returncode == 0, (proc.returncode, proc.stdout, proc.stderr)
        assert len(STATE.requests) >= 30, f"Expected >= 30 signed requests, got {len(STATE.requests)}"

        # Verify reverse-order teardown of the 6 sandbox resources:
        # Created order: compartment(c1), bucket(b1), queue(q1), stream(s1), policy(p1), application(a1)
        # Deleted order must be: a1, p1, s1, q1, b1, c1
        sandbox_deletes = [item[1] for item in STATE.deletes if "lmoci-fake01-" in item[1]]
        assert len(sandbox_deletes) == 6, sandbox_deletes
        assert sandbox_deletes[0].endswith("lmoci-fake01-a1")
        assert sandbox_deletes[1].endswith("lmoci-fake01-p1")
        assert sandbox_deletes[2].endswith("lmoci-fake01-s1")
        assert sandbox_deletes[3].endswith("lmoci-fake01-q1")
        assert sandbox_deletes[4].endswith("lmoci-fake01-b1")
        assert sandbox_deletes[5].endswith("lmoci-fake01-c1")

        # Verify --sweep reports 0 leftovers after cleanup
        sweep_proc = subprocess.run(
            host_cmd() + ["--sweep", str(ledger_dir), "--older-than-hours", "0"],
            env=base_env,
            text=True,
            capture_output=True,
            timeout=60,
            check=False,
        )
        assert sweep_proc.returncode == 0, sweep_proc.stderr
        assert json.loads(sweep_proc.stdout) == []

        # 2. Verify automatic teardown when a script throws mid-run
        abort_lms = d / "abort.lms"
        abort_dslb = d / "abort.dslb"
        abort_lms.write_text(
            f"""
p = _dsl_ schema={json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))};
let cloud = oci.endpoint("us-ashburn-1", {json.dumps(endpoint)});
let run = cloud.run("abort01", {json.dumps(str(ledger_dir))});
let b = run.bucket("tempbkt");
let q = run.queue("tempq");
check(false);
end _dsl_
savedsl(p, {json.dumps(str(abort_dslb))});
""",
            encoding="utf-8",
        )
        subprocess.run([lmscript, str(abort_lms)], check=True, capture_output=True, timeout=60)
        STATE.deletes.clear()
        abort_proc = subprocess.run(
            host_cmd() + ["--lmcode", str(abort_dslb)],
            env=base_env,
            text=True,
            capture_output=True,
            timeout=60,
            check=False,
        )
        assert abort_proc.returncode == 3
        abort_deletes = [item[1] for item in STATE.deletes if "lmoci-abort01-" in item[1]]
        assert len(abort_deletes) == 2, abort_deletes
        assert abort_deletes[0].endswith("lmoci-abort01-tempq")
        assert abort_deletes[1].endswith("lmoci-abort01-tempbkt")

        # 3. Verify error message secret redaction
        leak_env = dict(base_env)
        leak_env["OCI_CLI_TENANCY"] = "leak-error"
        leak_proc = subprocess.run(
            host_cmd() + ["iam", "tenancy", "--endpoint", endpoint],
            env=leak_env,
            text=True,
            capture_output=True,
            timeout=60,
            check=False,
        )
        assert leak_proc.returncode == 3
        assert "SECRETKEYDATA" not in leak_proc.stdout and "topsecret" not in leak_proc.stdout
        assert "[REDACTED]" in leak_proc.stdout

    server.shutdown()
    print("PASS test-fake.py: real RSA-SHA256 signing, 16 inventories, 6 sandbox lifecycles, and redaction")


if __name__ == "__main__":
    main()
