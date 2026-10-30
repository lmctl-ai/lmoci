#!/usr/bin/env python3
"""Live integration test against ocisim (http://127.0.0.1:4599) when running or required."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
HOST = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else (ROOT / "target" / "lmoci-0.1.0.jar")
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
        with urllib.request.urlopen(url + "/n", timeout=2) as resp:
            return resp.status in (200, 401, 403, 404)
    except urllib.error.HTTPError:
        return True
    except Exception:
        return False


def run_cli(args: list[str]) -> dict:
    env = dict(os.environ)
    env["LMOCI_ENDPOINT"] = ENDPOINT
    proc = subprocess.run(
        host_cmd() + args + ["--endpoint", ENDPOINT],
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


def main() -> None:
    if not is_reachable(ENDPOINT):
        if REQUIRED:
            raise SystemExit(f"FAIL: ocisim required at {ENDPOINT} but not reachable")
        print(f"SKIP test-ocisim.py: ocisim not running at {ENDPOINT}")
        return

    ns_res = run_cli(["os", "namespace"])
    assert isinstance(ns_res.get("namespace"), str) and len(ns_res["namespace"]) > 0

    bkt = "lmoci-ocisim-" + uuid.uuid4().hex[:8]
    run_cli(["os", "create-bucket", bkt])
    head_b = run_cli(["os", "head-bucket", bkt])
    assert head_b["exists"] is True

    put_res = run_cli(["os", "put-object", bkt, "hello/world.txt", "--content", "ocisim-live-payload"])
    assert put_res["content_length"] == 19

    get_res = run_cli(["os", "get-object", bkt, "hello/world.txt"])
    assert get_res["content"] == "ocisim-live-payload"

    list_res = run_cli(["os", "list-objects", bkt, "hello/"])
    assert list_res["count"] == 1

    run_cli(["os", "delete-object", bkt, "hello/world.txt"])
    run_cli(["os", "delete-bucket", bkt])

    print(f"PASS test-ocisim.py: live Object Storage round-trip against ocisim at {ENDPOINT}")


if __name__ == "__main__":
    main()
