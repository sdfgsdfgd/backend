#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

WEBHOOK_URL = "https://sdfgsdfg.net/webhook/github/server-py-selftest"
STATUS_URL = "https://sdfgsdfg.net/api/selftest/status"
RESULT_PATH = Path("server-py-selftest.json")
RECENT_WINDOW_MS = 60_000
POLL_INTERVAL = 5
POLL_TIMEOUT = 2_100
ZEN_POLL_TIMEOUT = 7_200  # bounded total poll budget for a matching active repair
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
    return poll_selftest(started_ms, (payload or {}).get("zen_run_id") or "", payload)


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
        if error.code in {520, 524}:
            print(f"[server-py-live-selftest] trigger returned HTTP {error.code}; polling status endpoint.", flush=True)
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


def poll_selftest(started_ms: int, zen_run_id: str = "", last_payload: dict | None = None) -> int:
    started = time.monotonic()
    deadline = started + (ZEN_POLL_TIMEOUT if zen_state(last_payload or {}) in ZEN_IN_PROGRESS else POLL_TIMEOUT)
    last_poll_error = ""
    while time.monotonic() < deadline:
        url = STATUS_URL + "?" + urllib.parse.urlencode({"zen_run_id": zen_run_id}) if zen_run_id else STATUS_URL
        req = urllib.request.Request(url, headers=BASE_HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as response:
                if response.status == 204:
                    time.sleep(POLL_INTERVAL)
                    continue
                candidate = json.loads(response.read().decode())
                if (zen_run_id and candidate.get("zen_run_id") != zen_run_id) or (not zen_run_id and (
                    int(candidate.get("timestamp_ms") or 0) < started_ms - RECENT_WINDOW_MS
                    or (workflow_url() and candidate.get("workflow_url") != workflow_url())
                )):
                    time.sleep(POLL_INTERVAL)
                    continue
                last_payload = candidate
                last_poll_error = ""
                zen_run_id = zen_run_id or last_payload.get("zen_run_id") or ""
                if zen_state(last_payload) in ZEN_IN_PROGRESS:
                    deadline = started + ZEN_POLL_TIMEOUT
                write_result(last_payload)
        except Exception as error:
            last_poll_error = str(error)
            print(f"[server-py-live-selftest] status poll failed: {error}", flush=True)
            time.sleep(POLL_INTERVAL)
            continue
        if is_terminal(last_payload, started_ms):
            return finish(last_payload)
        time.sleep(POLL_INTERVAL)

    state = zen_state(last_payload or {})
    active = state in ZEN_IN_PROGRESS
    payload = {
        "ok": False,
        "ci_stage": "zen_wait" if active else "poll",
        "raw_error": (f"Zen repair {zen_run_id} last observed {state}; recovery remains unconfirmed after the bounded {ZEN_POLL_TIMEOUT // 60}-minute wait"
                      if active else "No terminal result for this self-test before the bounded wait expired"),
        "zen_run_id": zen_run_id,
        "zen": (last_payload or {}).get("zen"),
        "workflow_url": workflow_url(),
        "status_url": STATUS_URL,
        "last_poll_error": last_poll_error,
        "last_payload": last_payload,
        "timestamp_ms": int(time.time() * 1000),
    }
    write_result(payload)
    raise SystemExit(json.dumps(payload, indent=2))


def zen_state(payload: dict) -> str:
    zen = payload.get("zen") or {}
    if payload.get("zen_run_id") and zen.get("run_id") == payload["zen_run_id"]:
        return str(zen.get("state") or "").strip().lower()
    return ""


def is_terminal(payload: dict, started_ms: int) -> bool:
    stamp = int(payload.get("timestamp_ms") or 0)
    if stamp < started_ms - RECENT_WINDOW_MS:
        return False
    state = zen_state(payload)
    if state in ZEN_IN_PROGRESS:
        print(f"[server-py-live-selftest] zen autofix is active ({state}); waiting.", flush=True)
        return False
    return True


def finish(payload: dict) -> int:
    print(json.dumps(payload, indent=2), flush=True)
    if not payload.get("ok"):
        detail = payload.get("raw_error") or payload.get("text_excerpt") or "Self-test failed"
        if payload.get("zen_run_id"):
            detail += f"; Zen {payload['zen_run_id']}: {zen_state(payload) or 'status unavailable'} ({(payload.get('zen') or {}).get('summary', '')})"
        raise SystemExit(detail)
    zen = payload.get("zen") or {}
    if payload.get("zen_run_id") and (
        zen.get("run_id") != payload["zen_run_id"] or zen.get("state") not in {"verified", "verified_and_pushed"}
    ):
        raise SystemExit(f"Zen has not resolved this run: {zen.get('summary') or zen.get('state') or 'status unavailable'}")
    audit = payload.get("model_audit") or {}
    if audit.get("status") != "OK" or audit.get("unknown") or audit.get("discovery_errors"):
        raise SystemExit(f"UI discovery still needs investigation: {json.dumps(audit)}")
    discoveries = (zen.get("codex") or {}).get("discoveries", [])
    if any(item.get("resolution") == "unresolved" for item in discoveries):
        raise SystemExit("Zen still reports unresolved UI discoveries")
    if discoveries:
        print(f"::notice::PASS with {len(discoveries)} handled UI discoveries: {json.dumps(discoveries)}")
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
