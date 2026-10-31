#!/usr/bin/env python3
"""Live integration test against ocisim (http://127.0.0.1:4599) when running, sibling binary present, or required."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
HOST = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (ROOT / "target" / "lmoci-0.1.0-all.jar")
ENDPOINT = os.environ.get("LMOCI_OCISIM_URL", "http://127.0.0.1:4599")
REQUIRED = os.environ.get("OCISIM_REQUIRED", "0") == "1"


def host_cmd() -> list[str]:
    if HOST.suffix == ".jar":
        return ["java", "-jar", str(HOST)]
    if HOST.suffix == ".cmd":
        return ["cmd.exe", "/c", str(HOST)]
    return [str(HOST)]


def is_reachable(url: str) -> bool:
    try:
        with urllib.request.urlopen(url + "/_floci-oci/health", timeout=1.5) as resp:
            return resp.status == 200
    except Exception:
        try:
            with urllib.request.urlopen(url + "/n", timeout=1.5) as resp:
                return resp.status in (200, 401, 403, 404)
        except urllib.error.HTTPError:
            return True
        except Exception:
            return False


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def run_cli(endpoint: str, args: list[str]) -> dict | list:
    env = dict(os.environ)
    env["LMOCI_ENDPOINT"] = endpoint
    proc = subprocess.run(
        host_cmd() + args + ["--endpoint", endpoint],
        env=env,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=60,
        check=False,
    )
    assert proc.returncode == 0, (args, proc.returncode, proc.stdout, proc.stderr)
    data = json.loads(proc.stdout)
    assert data.get("ok") is True, data
    return data["result"]


def run_against(endpoint: str) -> None:
    ns_res = run_cli(endpoint, ["os", "namespace"])
    assert isinstance(ns_res, dict) and isinstance(ns_res.get("namespace"), str) and len(ns_res["namespace"]) > 0

    bkt = "lmoci-ocisim-" + uuid.uuid4().hex[:8]
    run_cli(endpoint, ["os", "create-bucket", bkt])
    head_b = run_cli(endpoint, ["os", "head-bucket", bkt])
    assert isinstance(head_b, dict) and head_b["exists"] is True

    put_res = run_cli(endpoint, ["os", "put-object", bkt, "hello/world.txt", "--content", "ocisim-live-payload"])
    assert isinstance(put_res, dict) and put_res["content_length"] == 19

    get_res = run_cli(endpoint, ["os", "get-object", bkt, "hello/world.txt"])
    assert isinstance(get_res, dict) and get_res["content"] == "ocisim-live-payload"

    list_res = run_cli(endpoint, ["os", "list-objects", bkt, "hello/"])
    assert isinstance(list_res, dict) and list_res["count"] == 1

    run_cli(endpoint, ["os", "delete-object", bkt, "hello/world.txt"])
    run_cli(endpoint, ["os", "delete-bucket", bkt])

    # IAM regions + compartments against ocisim
    regions = run_cli(endpoint, ["iam", "regions"])
    assert isinstance(regions, list) and len(regions) >= 1

    # Also test DSL script execution against ocisim
    lmscript = shutil.which("lmscript")
    if lmscript is not None:
        with tempfile.TemporaryDirectory(prefix="lmoci-ocisim-dsl-") as tmp:
            d = Path(tmp)
            lms = d / "ocisim.lms"
            dslb = d / "ocisim.dslb"
            lms.write_text(
                f"""p = _dsl_ schema={json.dumps(str(ROOT / "schema" / "lmoci.dsl.json"))};
let cloud = oci.endpoint("us-ashburn-1", {json.dumps(endpoint)});
check(!cloud.is_demo());
let run = cloud.run("sim01", {json.dumps(str(d / "ledgers"))});
let b = run.bucket("bkt");
check(cloud.object_put("lmoci-sim01-bkt", "k.txt", "from-dsl").number("content_length") == 8);
check(cloud.object_get("lmoci-sim01-bkt", "k.txt").get("content") == "from-dsl");
run.cleanup();
check(run.remaining() == 0);
end _dsl_
savedsl(p, {json.dumps(str(dslb))});
""",
                encoding="utf-8",
            )
            comp = subprocess.run([lmscript, str(lms)], text=True, capture_output=True, timeout=60, check=False)
            assert comp.returncode == 0, comp.stderr
            env = dict(os.environ)
            env["LMOCI_ENDPOINT"] = endpoint
            res = subprocess.run(host_cmd() + ["--lmcode", str(dslb)], env=env, text=True, capture_output=True, timeout=60, check=False)
            assert res.returncode == 0, (res.returncode, res.stdout, res.stderr)

    print(f"PASS test-ocisim.py: live CLI + DSL round-trip against ocisim at {endpoint}")


def main() -> None:
    if is_reachable(ENDPOINT):
        run_against(ENDPOINT)
        return

    sibling_bin = Path(os.environ.get("OCISIM_BIN", str(ROOT.parent / "ocisim" / "dist" / "ocisim")))
    if sibling_bin.is_file() and os.access(sibling_bin, os.X_OK):
        port = find_free_port()
        ephemeral_url = f"http://127.0.0.1:{port}"
        proc = subprocess.Popen(
            [str(sibling_bin), "serve", "--port", str(port)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            for _ in range(60):
                if is_reachable(ephemeral_url):
                    break
                time.sleep(0.2)
            assert is_reachable(ephemeral_url), f"spawned {sibling_bin} did not become healthy on {ephemeral_url}"
            run_against(ephemeral_url)
            return
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except Exception:
                proc.kill()

    if REQUIRED:
        raise SystemExit(f"FAIL: ocisim required at {ENDPOINT} but not reachable")
    print(f"SKIP test-ocisim.py: ocisim not running at {ENDPOINT}")


if __name__ == "__main__":
    main()
