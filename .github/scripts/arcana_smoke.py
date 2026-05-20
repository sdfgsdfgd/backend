#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

WEBHOOK_URL = "https://sdfgsdfg.net/webhook/github/arcana-smoke"
SUMMARY_URL = "https://ops.sdfgsdfg.net/api/ops/summary"
RESULT_PATH = Path("arcana-smoke.json")
RECENT_WINDOW_MS = 60_000
POLL_INTERVAL = 3
POLL_TIMEOUT = 180
HTTP_TIMEOUT = 30
SECRET_HEADER = "X-Arcana-Smoke-Secret"
BASE_HEADERS = {
    "Accept": "*/*",
    "User-Agent": "backend-full-suite/arcana-smoke",
}


def main() -> int:
    started_ms = int(time.time() * 1000)
    trigger_arcana_smoke()
    return poll_arcana_smoke(started_ms)


def trigger_arcana_smoke() -> None:
    secret = os.environ.get("ARCANA_SMOKE_WEBHOOK_SECRET", "").strip()
    if not secret:
        raise SystemExit("ARCANA_SMOKE_WEBHOOK_SECRET is required")
    payload = {}
    if url := workflow_url():
        payload["workflow_url"] = url
    body = json.dumps(payload).encode()
    headers = {
        **BASE_HEADERS,
        "Content-Type": "application/json",
        "X-GitHub-Event": "arcana-smoke",
        SECRET_HEADER: secret,
    }
    req = urllib.request.Request(WEBHOOK_URL, data=body, headers=headers, method="POST")
    print(f"[arcana-smoke] triggering {WEBHOOK_URL}", flush=True)
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as response:
            trigger_payload = json.loads(response.read().decode())
            print(json.dumps(trigger_payload, indent=2), flush=True)
            if not trigger_payload.get("ok"):
                raise SystemExit(json.dumps(trigger_payload, indent=2))
    except urllib.error.HTTPError as error:
        preview = error.read().decode("utf-8", errors="replace")[:1_200]
        raise SystemExit(f"arcana-smoke webhook failed with HTTP {error.code}: {preview}")


def poll_arcana_smoke(started_ms: int) -> int:
    deadline = time.time() + POLL_TIMEOUT
    last_payload = None
    while time.time() < deadline:
        req = urllib.request.Request(SUMMARY_URL, headers=BASE_HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as response:
                summary = json.loads(response.read().decode())
        except Exception as error:
            print(f"[arcana-smoke] summary poll failed: {error}", flush=True)
            time.sleep(POLL_INTERVAL)
            continue
        arcana = next((repo for repo in summary.get("repos", []) if repo.get("id") == "arcana"), None)
        latest = arcana.get("latest_run") if isinstance(arcana, dict) else None
        last_payload = latest
        if is_fresh_ok(latest, started_ms):
            write_result(latest)
            print(json.dumps(latest, indent=2), flush=True)
            return 0
        time.sleep(POLL_INTERVAL)
    payload = {
        "ok": False,
        "raw_error": "Arcana smoke did not publish a fresh OK result before timeout",
        "last_payload": last_payload,
        "timestamp_ms": int(time.time() * 1000),
    }
    write_result(payload)
    raise SystemExit(json.dumps(payload, indent=2))


def is_fresh_ok(latest: dict | None, started_ms: int) -> bool:
    if not isinstance(latest, dict):
        return False
    stamp = int(latest.get("timestamp_ms") or 0)
    if stamp < started_ms - RECENT_WINDOW_MS:
        return False
    return latest.get("status") == "OK"


def write_result(payload: dict) -> None:
    RESULT_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def workflow_url() -> str | None:
    repo = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID")
    if not repo or not run_id:
        return None
    server = os.environ.get("GITHUB_SERVER_URL") or "https://github.com"
    return f"{server}/{repo}/actions/runs/{run_id}"


if __name__ == "__main__":
    sys.exit(main())
