"""跑探针抓 baseline 帧，统计工作区与归档集合（验证归档过滤数据确实到达 App 侧同款协议）。"""
import json
import re
import subprocess

PROBE = r"D:\AI任务\dsh-mobile-app\tools\probe_remote_mux.js"
result = subprocess.run(
    ["node", PROBE], capture_output=True, text=True, encoding="utf-8",
    errors="replace", timeout=60,
)
out = result.stdout or ""
match = re.search(r'\{"type":"item".*', out)
if match is None:
    print("未抓到 item 帧；探针输出：")
    print(out[:600])
    raise SystemExit(1)

first_line = match.group(0).splitlines()[0]
frame = json.loads(first_line)
inner = frame["value"]["value"]  # {type:baseline, value:{items, archivedSessionIds}}

print("工作区数量:", len(inner["items"]))
for ws in inner["items"][:8]:
    print(f"  - {ws['title']}  ({ws['path']})  会话 {len(ws['sessionIds'])} 个")
print("归档会话数量:", len(inner.get("archivedSessionIds", [])))
