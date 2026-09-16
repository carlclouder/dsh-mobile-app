"""新版 dsh RPC 参数探测：确认各端点在斜杠协议下需要的 args 形状。

用法：python probe_rpc_args.py <token>
输出：每个 RPC × 候选参数形状的 ok/error 结果表。
"""
import json
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3080"


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), dict(e.headers)


def main():
    token = sys.argv[1]
    # 1) 令牌换会话 Cookie
    status, _, headers = http("GET", f"{BASE}/?token={token}")
    cookie = None
    for k, v in headers.items():
        if k.lower() == "set-cookie":
            cookie = v.split(";")[0]
    if not cookie:
        print(f"token exchange failed: HTTP {status}")
        return
    print(f"cookie: {cookie[:36]}...\n")

    # 2) 候选 RPC 与参数形状
    candidates = [
        ("session/list", [{"_request": {}}, {}]),
        ("workspace/list", [{"_request": {}}, {}]),
        ("session/modelCatalog", [{"_request": {}}, {}]),
        ("agentPresets/list", [{"_request": {}}, {}]),
        ("commands/list", [{"_request": {}}, {}]),
        ("skills/list", [{"_request": {}}, {}]),
    ]
    for method, arg_shapes in candidates:
        for shape in arg_shapes:
            envelope = {
                "type": "client-request",
                "rpcId": f"probe-{method}-{len(str(shape))}",
                "method": method,
                "payload": {"args": shape},
            }
            path = "/api/" + method
            status, body, _ = http(
                "POST", BASE + path, envelope,
                {"Content-Type": "application/json", "Cookie": cookie},
            )
            verdict = f"HTTP {status}"
            try:
                parsed = json.loads(body)
                result = parsed.get("result", {})
                if result.get("ok") is True:
                    value = result.get("value")
                    keys = list(value.keys())[:4] if isinstance(value, dict) else type(value).__name__
                    verdict += f"  OK value-keys={keys}"
                else:
                    err = result.get("error", {})
                    verdict += f"  ERR {err.get('code')}: {str(err.get('message'))[:70]}"
            except Exception:
                verdict += f"  non-JSON: {body[:70]}"
            print(f"{method:<24} args={json.dumps(shape, ensure_ascii=False):<22} -> {verdict}")
        print()


if __name__ == "__main__":
    main()
