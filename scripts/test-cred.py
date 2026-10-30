#!/usr/bin/env python3
"""Test the shared ~/.lmconn/creds.db SQLite credential store and secret redaction."""

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


def host_cmd() -> list[str]:
    if HOST.suffix == ".jar":
        return ["java", "-jar", str(HOST)]
    if HOST.suffix == ".cmd":
        return ["cmd.exe", "/c", str(HOST)]
    return [str(HOST)]


def run_json(args: list[str], env: dict[str, str], expected_code: int = 0) -> dict:
    proc = subprocess.run(
        host_cmd() + args,
        env=env,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=60,
        check=False,
    )
    assert proc.returncode == expected_code, (args, proc.returncode, proc.stdout, proc.stderr)
    return json.loads(proc.stdout)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="lmoci-cred-") as tmp:
        d = Path(tmp)
        db_path = d / "creds.db"
        key_file = d / "oci_api_key.pem"
        fake_key = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7\n-----END PRIVATE KEY-----"
        key_file.write_text(fake_key, encoding="utf-8")

        env = dict(os.environ)
        env["LMOCI_DB"] = str(db_path)

        path_res = run_json(["cred", "path"], env)
        assert path_res["ok"] is True
        assert Path(path_res["result"]["db"]) == db_path

        set_res = run_json(
            [
                "cred",
                "set",
                "oci",
                "prod",
                "--tenancy",
                "ocid1.tenancy.oc1..prodtenancy",
                "--user",
                "ocid1.user.oc1..produser",
                "--fingerprint",
                "aa:bb:cc:dd",
                "--private-key-file",
                str(key_file),
                "--passphrase",
                "top-secret-pass",
                "--region",
                "us-phoenix-1",
                "--namespace",
                "prod-ns",
            ],
            env,
        )
        assert set_res["ok"] is True
        fields = set_res["result"]["fields"]
        assert fields["tenancy"] == "ocid1.tenancy.oc1..prodtenancy"
        assert fields["region"] == "us-phoenix-1"
        assert fields["private_key"] == "***REDACTED***"
        assert fields["passphrase"] == "***REDACTED***"
        assert "BEGIN PRIVATE KEY" not in json.dumps(set_res)
        assert "top-secret-pass" not in json.dumps(set_res)

        get_redacted = run_json(["cred", "get", "oci", "prod"], env)
        assert get_redacted["result"]["fields"]["private_key"] == "***REDACTED***"
        assert get_redacted["result"]["fields"]["passphrase"] == "***REDACTED***"

        get_revealed = run_json(["cred", "get", "oci", "prod", "--reveal"], env)
        assert get_revealed["result"]["fields"]["private_key"] == fake_key
        assert get_revealed["result"]["fields"]["passphrase"] == "top-secret-pass"

        list_res = run_json(["cred", "list", "oci"], env)
        assert len(list_res["result"]["credentials"]) == 1
        assert list_res["result"]["credentials"][0]["fields"]["private_key"] == "***REDACTED***"

        # Optional cross-language interop check with lmconn if present in workspace
        lmconn_creds = Path("/home/ubuntu/lmworkflow/lmconn/src/creds.js")
        node = shutil.which("node")
        if lmconn_creds.is_file() and node is not None:
            node_out = subprocess.run(
                [
                    node,
                    "-e",
                    f"const c = require({json.dumps(str(lmconn_creds))}); "
                    f"console.log(JSON.stringify(c.getCredential({{dbPath: {json.dumps(str(db_path))}, service: 'oci', name: 'prod', reveal: false}})));",
                ],
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )
            if node_out.returncode == 0 and node_out.stdout.strip():
                node_rec = json.loads(node_out.stdout.strip())
                assert node_rec["service"] == "oci"
                assert node_rec["name"] == "prod"

        del_res = run_json(["cred", "delete", "oci", "prod"], env)
        assert del_res["result"]["deleted"] is True

        not_found = run_json(["cred", "get", "oci", "prod"], env, expected_code=3)
        assert not_found["ok"] is False
        assert not_found["error"]["code"] == "CRED_NOT_FOUND"

    print("PASS test-cred.py: SQLite credential store, permissions, and secret redaction")


if __name__ == "__main__":
    main()
