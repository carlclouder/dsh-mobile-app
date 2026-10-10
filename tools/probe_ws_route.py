"""判别 /ws 404 的性质：纯英文路径是否可访问（区分"路由不存在"与"中文编码问题"）。"""
import http.client
import urllib.parse

HOST_AUTH = "carl-pc.taild10021.ts.net"
PORT = 3080


def probe(path, label):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    conn.request("GET", path, headers={"Host": HOST_AUTH})
    resp = conn.getresponse()
    body = resp.read()
    conn.close()
    print(f"{label}\n  {path[:110]}\n  -> HTTP {resp.status}  {len(body)} 字节  {resp.headers.get('content-type')}\n")


# 纯英文路径（ASCII junction D:\dshm 指向项目；README.md 必定存在）
probe("/ws/D:/dshm/README.md", "① 纯英文路径（判定路由是否存在）")
# 中文路径（未经 quote，http.client 会原样发出 UTF-8 字节）
probe("/ws/D:/dshm/README.md".replace("dshm", "dshm"), "② 对照：同路径再请求一次")
# 相对路径（插件要求绝对路径，应回 400 并附提示）
probe("/ws/README.md", "③ 相对路径（预期 400，可证明路由确实在工作）")
