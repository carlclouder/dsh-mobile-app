"""查看 session/page 响应的 records 结构（新版历史形状）。"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:3080"


def main():
    cookie, host, session_id = sys.argv[1], sys.argv[2], sys.argv[3]
    ts = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    envelope = {
        "type": "client-request",
        "rpcId": "probe-records",
        "method": "session/page",
        "payload": {"args": {"request": {
            "address": {"kind": "session", "sessionId": session_id},
            "throughSeq": ts,
        }}},
    }
    req = urllib.request.Request(BASE + "/api/session/page",
                                 data=json.dumps(envelope).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Cookie", cookie)
    req.add_header("Host", host)
    with urllib.request.urlopen(req, timeout=20) as resp:
        body = json.loads(resp.read().decode("utf-8", "replace"))
    value = body["result"]["value"]
    records = value.get("records")
    print(f"throughSeq={ts}  hasMore={value.get('hasMore')}  records={len(records) if isinstance(records, list) else type(records).__name__}")
    if isinstance(records, list) and records:
        print("\n第一条 record 结构:")
        print(json.dumps(records[0], ensure_ascii=False)[:700])
        print("\n第二条 record:")
        print(json.dumps(records[1], ensure_ascii=False)[:400] if len(records) > 1 else "(无)")
        # 统计 record 的字段集合
        keys = set()
        for r in records[:50]:
            if isinstance(r, dict):
                keys.update(r.keys())
        print("\nrecords 字段全集:", sorted(keys))


if __name__ == "__main__":
    main()
