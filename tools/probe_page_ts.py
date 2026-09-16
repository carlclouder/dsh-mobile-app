"""探测 session/page 的 throughSeq 正确取值（不传 / 0 / 游标值）。

用法：python probe_page_ts.py <cookie> <host> <sessionId>
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3080"


def call(cookie, host, session_id, request_body):
    envelope = {
        "type": "client-request",
        "rpcId": "probe-ts",
        "method": "session/page",
        "payload": {"args": {"request": request_body}},
    }
    req = urllib.request.Request(BASE + "/api/session/page",
                                 data=json.dumps(envelope).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    req.add_header("Host", host)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read().decode()[:120]}"
    result = body.get("result", {})
    if result.get("ok") is not True:
        err = result.get("error", {}) or {}
        return f"ERR {err.get('code')}: {str(err.get('message'))[:110]}"
    value = result.get("value", {})
    events = value.get("events") if isinstance(value, dict) else None
    n = len(events) if isinstance(events, list) else "?"
    return f"OK events={n} value-keys={list(value.keys())[:5] if isinstance(value, dict) else value}"


def main():
    cookie, host, session_id = sys.argv[1], sys.argv[2], sys.argv[3]
    base_req = {"address": {"kind": "session", "sessionId": session_id}}
    variants = [
        ("不传 throughSeq", dict(base_req)),
        ("throughSeq=0", {**base_req, "throughSeq": 0}),
        ("throughSeq=694", {**base_req, "throughSeq": 694}),
        ("throughSeq=1000", {**base_req, "throughSeq": 1000}),
        ("throughSeq + maxMessages=10", {**base_req, "throughSeq": 694, "maxMessages": 10}),
    ]
    for label, req_body in variants:
        print(f"{label:<34} -> {call(cookie, host, session_id, req_body)}")


if __name__ == "__main__":
    main()
