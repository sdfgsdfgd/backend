#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

WEBHOOK_URL = "https://sdfgsdfg.net/webhook/github/server-py-selftest"
STATUS_URL = "https://sdfgsdfg.net/api/selftest/status"
RESULT_PATH = Path("server-py-selftest.json")
RECENT_WINDOW_MS = 60_000
POLL_INTERVAL = 5
POLL_TIMEOUT = 2_100
HTTP_TIMEOUT = 20
ZEN_IN_PROGRESS = {"queued", "in_progress", "verifying", "committing", "pushing"}
BASE_HEADERS = {
    "Accept": "*/*",
    "User-Agent": "curl/8.5.0",
}


def main() -> int:
    started_ms = int(time.time() * 1000)
    payload = trigger_selftest()
    if payload and is_terminal(payload, started_ms):
        return finish(payload)
    return poll_selftest(started_ms)


def trigger_selftest() -> dict | None:
    payload = {"new_chat": True}
    if url := workflow_url():
        payload["workflow_url"] = url
    body = json.dumps(payload).encode()
    headers = {
        **BASE_HEADERS,
        "Content-Type": "application/json",
        "X-GitHub-Event": "live-selftest",
    }
    req = urllib.request.Request(WEBHOOK_URL, data=body, headers=headers, method="POST")
    print(f"[server-py-live-selftest] triggering {WEBHOOK_URL}", flush=True)
    try:
        with urllib.request.urlopen(req, timeout=POLL_TIMEOUT) as response:
            payload = json.loads(response.read().decode())
            write_result(payload)
            return payload
    except urllib.error.HTTPError as error:
        preview = error.read().decode("utf-8", errors="replace")[:1_200]
        if error.code == 524:
            print("[server-py-live-selftest] trigger timed out at edge; polling status endpoint.", flush=True)
            return None
        fail_payload = {
            "ok": False,
            "ci_stage": "trigger",
            "raw_error": f"Trigger webhook failed with HTTP {error.code}",
            "trigger_response_preview": preview,
            "timestamp_ms": int(time.time() * 1000),
        }
        write_result(fail_payload)
        raise SystemExit(json.dumps(fail_payload, indent=2))
    except Exception as error:
        fail_payload = {
            "ok": False,
            "ci_stage": "trigger",
            "raw_error": f"Trigger webhook failed: {error}",
            "timestamp_ms": int(time.time() * 1000),
        }
        write_result(fail_payload)
        raise SystemExit(json.dumps(fail_payload, indent=2))


def poll_selftest(started_ms: int) -> int:
    deadline = time.time() + POLL_TIMEOUT
    last_payload = None
    while time.time() < deadline:
        req = urllib.request.Request(STATUS_URL, headers=BASE_HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as response:
                if response.status == 204:
                    time.sleep(POLL_INTERVAL)
                    continue
                last_payload = json.loads(response.read().decode())
                write_result(last_payload)
        except Exception as error:
            print(f"[server-py-live-selftest] status poll failed: {error}", flush=True)
            time.sleep(POLL_INTERVAL)
            continue
        if is_terminal(last_payload, started_ms):
            return finish(last_payload)
        time.sleep(POLL_INTERVAL)

    payload = {
        "ok": False,
        "ci_stage": "poll",
        "raw_error": "Self-test did not update before timeout",
        "last_payload": last_payload,
        "timestamp_ms": int(time.time() * 1000),
    }
    write_result(payload)
    raise SystemExit(json.dumps(payload, indent=2))


def is_terminal(payload: dict, started_ms: int) -> bool:
    stamp = int(payload.get("timestamp_ms") or 0)
    if stamp < started_ms - RECENT_WINDOW_MS:
        return False
    zen = payload.get("zen")
    zen_state = str(zen.get("state") or "").lower() if isinstance(zen, dict) else ""
    if not payload.get("ok") and zen_state in ZEN_IN_PROGRESS:
        print(f"[server-py-live-selftest] zen autofix is active ({zen_state}); waiting.", flush=True)
        return False
    return True


def finish(payload: dict) -> int:
    print(json.dumps(payload, indent=2), flush=True)
    if not payload.get("ok"):
        raise SystemExit(payload.get("raw_error") or payload.get("text_excerpt") or "Self-test failed")
    return 0


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
