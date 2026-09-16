"""复刻 App 的 session/page 请求，检查服务端响应与 App 解析路径是否匹配。

用法：python probe_history.py <cookie> <host> <sessionId>
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3080"


def main():
    cookie, host, session_id = sys.argv[1], sys.argv[2], sys.argv[3]
    envelope = {
        "type": "client-request",
        "rpcId": "probe-page",
        "method": "session/page",
        "payload": {"args": {"request": {
            "address": {"kind": "session", "sessionId": session_id},
            "throughSeq": 9007199254740991,
        }}},
    }
    req = urllib.request.Request(BASE + "/api/session/page",
                                 data=json.dumps(envelope).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    req.add_header("Host", host)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode()[:300])
        return
    parsed = json.loads(body)
    result = parsed.get("result", {})
    print("HTTP", status, "ok =", result.get("ok"))
    if result.get("ok") is not True:
        print("error:", json.dumps(result.get("error"), ensure_ascii=False)[:400])
        return
    value = result.get("value", {})
    print("value keys:", list(value.keys()) if isinstance(value, dict) else type(value).__name__)
    events = value.get("events") if isinstance(value, dict) else None
    if isinstance(events, list):
        print("events count:", len(events))
        if events:
            first = events[0]
            print("first item keys:", list(first.keys()) if isinstance(first, dict) else type(first).__name__)
            print("first item:", json.dumps(first, ensure_ascii=False)[:400])
    else:
        print("value preview:", json.dumps(value, ensure_ascii=False)[:400])


if __name__ == "__main__":
    main()
