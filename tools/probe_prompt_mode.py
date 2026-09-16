"""探测新版 session/prompt 的合法 mode 取值（boundary validation 通过性）。

用法：python probe_prompt_mode.py <cookie> <host> <sessionId>
注意：会在目标会话里真实投递一条消息（内容标注为 App 适配验证）。
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3080"


def call(cookie, host, session_id, request_extra):
    request = {
        "sessionId": session_id,
        "content": [{"type": "text", "text": "[App-adapt-probe] ignore this message"}],
        "clientTimeZone": "UTC",
    }
    request.update(request_extra)
    envelope = {
        "type": "client-request",
        "rpcId": "probe-prompt",
        "method": "session/prompt",
        "payload": {"args": {"request": request}},
    }
    req = urllib.request.Request(BASE + "/api/session/prompt",
                                 data=json.dumps(envelope).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    req.add_header("Host", host)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read().decode()[:120]}"
    result = body.get("result", {})
    if result.get("ok") is True:
        return "OK " + json.dumps(result.get("value"), ensure_ascii=False)[:120]
    err = result.get("error", {}) or {}
    return f"ERR {err.get('code')}: {str(err.get('message'))[:100]}"


def main():
    cookie, host, session_id = sys.argv[1], sys.argv[2], sys.argv[3]
    variants = [
        ("不传 mode", {}),
        ('mode="queue"', {"mode": "queue"}),
        ('mode="steer"', {"mode": "steer"}),
        ('mode="immediate"', {"mode": "immediate"}),
    ]
    for label, extra in variants:
        print(f"{label:<20} -> {call(cookie, host, session_id, extra)}")


if __name__ == "__main__":
    main()
