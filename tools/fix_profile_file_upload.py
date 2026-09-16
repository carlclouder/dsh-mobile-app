"""修复主 profile：移除被 pnpm 重新登记进来的第三方 dsh-file-upload（规则 22 坑一：与内置同名冲突，重启即崩）。

同时做 JSON 无 BOM 校验（规则：package.json 严禁 UTF-8 BOM，否则 dsh 启动 JSON.parse 崩）。
"""
import json
import os

path = r"C:\Users\Carl\.dsh\profiles\web\package.json"
raw = open(path, "rb").read()
print("BOM check (前3字节):", raw[:3], "→", "有BOM(需修)" if raw[:3] == b"\xef\xbb\xbf" else "无BOM(OK)")

data = json.loads(raw.decode("utf-8"))
bundles = data.get("dsh", {}).get("profile", {}).get("bundles", [])
print("修改前 bundles:", bundles)

removed = [b for b in bundles if b == "dsh-file-upload"]
data["dsh"]["profile"]["bundles"] = [b for b in bundles if b != "dsh-file-upload"]

deps = data.get("dependencies", {})
dep_removed = [k for k in deps if k == "dsh-file-upload"]
for key in dep_removed:
    del deps[key]

out = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(out)

# 回读校验
check_raw = open(path, "rb").read()
check = json.loads(check_raw.decode("utf-8"))
print("修改后 bundles:", check["dsh"]["profile"]["bundles"])
print("移除的 bundle 条目:", removed, "| 移除的 dependencies:", dep_removed)
print("BOM 校验:", "无BOM(OK)" if check_raw[:3] != b"\xef\xbb\xbf" else "仍有BOM(异常)")
print("auth-gateway 在册:", "dsh-auth-gateway" in check["dsh"]["profile"]["bundles"])
