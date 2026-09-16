"""新版 dsh RPC 参数探测（第二版）：支持直接传会话 Cookie + Host 头（令牌耗尽时用）。

用法：python probe_rpc_args2.py <cookie> <host>
输出：各 RPC × 候选 args 形状 的 ok/error 表。
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3080"


def call(method, args_shape, cookie, host, timeout=10):
    envelope = {
        "type": "client-request",
        "rpcId": "probe-" + method.replace("/", "-"),
        "method": method,
        "payload": {"args": args_shape},
    }
    req = urllib.request.Request(
        BASE + "/api/" + method, data=json.dumps(envelope).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    if host:
        req.add_header("Host", host)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        status = e.code
    except Exception as e:
        return f"EXC {type(e).__name__}: {e}"
    try:
        result = json.loads(body).get("result", {})
    except Exception:
        return f"HTTP {status} non-JSON: {body[:80]}"
    if result.get("ok") is True:
        value = result.get("value")
        if isinstance(value, dict):
            preview = {k: (list(v)[:2] if isinstance(v, list) else type(v).__name__)
                       for k, v in list(value.items())[:5]}
        else:
            preview = type(value).__name__
        return f"OK {json.dumps(preview, ensure_ascii=False)[:110]}"
    err = result.get("error", {}) or {}
    return f"ERR {err.get('code')}: {str(err.get('message'))[:90]}"


def main():
    cookie = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else None
    # 会话样例 id 从 session/list 拿
    print("== 会话列表（取样例 id） ==")
    print("  session/list {'_request':{}} ->", call("session/list", {"_request": {}}, cookie, host))
    req = urllib.request.Request(
        BASE + "/api/session/list",
        data=json.dumps({"type": "client-request", "rpcId": "probe-ids",
                         "method": "session/list", "payload": {"args": {"_request": {}}}}).encode(),
        method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    if host:
        req.add_header("Host", host)
    session_id = None
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
        items = data["result"]["value"]["items"]
        session_id = items[0]["sessionId"]
        print(f"  sample sessionId = {session_id}\n")

    probes = [
        ("workspace/list", [{"_request": {}}, {}]),
        ("session/history", [{"_request": {"sessionId": session_id}}, {"sessionId": session_id}]),
        ("session/modelCatalog", [{"_request": {}}, {}]),
        ("session/queue", [{"_request": {"sessionId": session_id}}]),
        ("subagents/list", [{"_request": {}}, {}]),
    ]
    for method, shapes in probes:
        for shape in shapes:
            verdict = call(method, shape, cookie, host)
            print(f"{method:<24} args={json.dumps(shape, ensure_ascii=False)[:60]:<62} -> {verdict}")
        print()


if __name__ == "__main__":
    main()
