"""更新 app/personal.properties 的部署域名（tail98fa18 → taild10021）。

背景：用户更换 Tailscale 网络后域名变更，APK 编译时注入的默认地址仍是旧域名，
导致手机装包后默认地址 DNS 解析失败（不可达）。
"""
path = r"D:\AI任务\dsh-mobile-app\app\personal.properties"
with open(path, encoding="utf-8") as f:
    text = f.read()
print("=== 修改前 ===")
print(text)

text = text.replace("carl-pc.tail98fa18.ts.net", "carl-pc.taild10021.ts.net")

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)

with open(path, encoding="utf-8") as f:
    updated = f.read()
print("=== 修改后 ===")
print(updated)
print("changed:", "taild10021" in updated and "tail98fa18" not in updated)
