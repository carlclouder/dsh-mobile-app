"""为临时验证实例写 user patch：把网关插件端口指到隔离端口（不占用 3081/3080）。"""
import os

dev_home = r"C:\Users\Carl\.dsh-dev\gwtest"
os.makedirs(dev_home, exist_ok=True)
path = os.path.join(dev_home, "cordis.patch.yml")

content = """# 临时验证实例的覆盖配置（会话专属，不影响主 profile 与其他会话）
# 网关插件默认 3081 → 3080；这里改到隔离端口，避免与主服务/旧代理冲突。
# 注意：patch 行是整对象替换语义，config 会被整体替换（未写字段回落到 schema 默认值）。
- id: auth-gateway
  config:
    port: 31235
    upstreamPort: 31234
"""

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(content)
print("written:", path)
print(open(path, encoding="utf-8").read())
