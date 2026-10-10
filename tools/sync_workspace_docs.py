"""同步文档：工作区流（workspace/follow）适配记录（DESIGN §13 + PROGRESS 断点）。"""
import sys

# ---------- 1) DESIGN.md §13 追加一条 ----------
design = r"D:\AI任务\dsh-mobile-app\docs\DESIGN.md"
text = open(design, encoding="utf-8").read()
anchor = "### 13.4 App 侧发送反馈补充"
addition = """### 13.4 工作区与归档集合改由 WebSocket 流提供（2026-09-16 手机实测报障）

**现象**（用户原话）："app端出现工作区 子目录消失，所有会话无分类，大量历史会话重新显示"。

**根因**（源码 + 实测双重证据）：新版 dsh **不存在 `workspace/list` 端点**，工作区列表与
归档集合（`archivedSessionIds`）只通过 **WebSocket 端点 `/api/remote.mux`** 上的
`workspace/follow` 流下发。App 此前仍调用旧 REST 端点（404），拿不到数据 → 分组失效、
归档过滤失效（归档会话全部重新显示、并触发通知）。

**实现**（`EventStreamService.runWorkspaceStream`）：

| 环节 | 规格（均经实测校准） |
|---|---|
| 载体 | `ws(s)://<base>/api/remote.mux`（`REMOTE_STREAM_MUX_PATH`，dsh-api-gateway 源码常量） |
| 开流帧 | `{"type":"open","streamId":"<uuid>","endpoint":"workspace/follow","payload":{"args":{}}}` |
| 帧外壳 | `{"type":"item","streamId":..,"value":<业务帧>}` / `{"type":"end","streamId"}` / `{"type":"error",…}` |
| baseline 帧 | `{"type":"baseline","value":{"items":[WorkspaceView…],"archivedSessionIds":[…]}}` ← **业务数据在内层 `value`** |
| 增量帧 | `upsert{workspace}` / `remove{workspaceId}` / `order{workspaceIds}` / `archived{archivedSessionIds}` |
| 心跳 | 服务端每 2 秒发 WS Ping，OkHttp 自动回 Pong |
| 断线 | `end`/`error`/socket 关闭 → 退避重连（1s 起，上限 15s），重连时按当前基址重建 URL |

**两个实测坑（已固化到代码注释）**：

1. **开流 payload 必须恰含一个 `args` 字段**：`payload:{}` 会被服务端拒绝
   （`gateway/internal: Remote payload must contain exactly one plain-object args field`）；
2. **baseline 业务数据在内层 `value`**：按外层取会静默拿空（表现为界面工作区只有"全部"、归档会话重新出现）。

**配套改动**：`runBaseline` 的轮询对账在**流就绪后跳过** `workspace.list` 调用（新版必然 404，
省一次无效请求）；旧版宿主（点号协议）无此流，仍走 REST 路径。

**验证**（模拟器连真实宿主）：工作区 chips 恢复显示 `全部 | dsh-mobile-app | Quantitative_investment_system`，
会话按工作区分组、归档会话不再出现；单测 101/101、release v0.1.183。

**探测工具**：`tools/probe_remote_mux.js`（Node 直连该 WS 打印原始帧，用于校准协议）。

"""
if anchor not in text:
    sys.exit("DESIGN 锚点未找到")
if "13.4 工作区与归档集合改由 WebSocket 流提供" in text:
    print("DESIGN 已含该章节，跳过")
else:
    text = text.replace(anchor, addition + anchor, 1)
    # 原 13.4/13.5/13.6 顺延为 13.5/13.6/13.7
    text = text.replace("### 13.4 App 侧发送反馈补充", "### 13.5 App 侧发送反馈补充", 1)
    text = text.replace("### 13.5 验证状态", "### 13.6 验证状态", 1)
    text = text.replace("### 13.6 依赖的宿主侧前置条件", "### 13.7 依赖的宿主侧前置条件", 1)
    with open(design, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    print("DESIGN §13.4 已新增并顺延后续小节")

# ---------- 2) PROGRESS 断点追加 ----------
progress = r"D:\AI任务\dsh-mobile-app\PROGRESS.md"
ptext = open(progress, encoding="utf-8").read()
marker = "**状态：宿主机「免令牌网关」以认证链放行定稿，待重启生效（2026-09-16）"
index = ptext.find(marker)
if index < 0:
    sys.exit("PROGRESS 锚点未找到")
new_line = (
    "**状态：工作区/归档数据源改为 WebSocket 流，修复手机报障（2026-09-16）——用户报"
    "\u201capp端出现工作区 子目录消失，所有会话无分类，大量历史会话重新显示\u201d。"
    "根因：新版 dsh 无 `workspace/list` 端点，工作区与归档集合只经 **`/api/remote.mux` 的 "
    "`workspace/follow` 流**下发；App 仍调旧 REST（404）→ 分组与归档过滤同时失效。"
    "已实现 `EventStreamService.runWorkspaceStream`：连该 WS、发 `{type:open,payload:{args:{}}}`、"
    "收 `item` 帧并解包**内层 value**（实测 baseline 为 `{type:baseline,value:{items,archivedSessionIds}}`）、"
    "增量 upsert/remove/order/archived、断线退避重连；流就绪后轮询跳过必然 404 的 `workspace.list`。"
    "模拟器实测：工作区 chips 恢复（全部 | dsh-mobile-app | Quantitative_investment_system）、"
    "会话按工作区分组、归档会话不再出现；单测 101/101、release v0.1.183。"
    "两个实测坑已写入代码注释与 DESIGN §13.4：①开流 payload 必须恰含 args 字段；②baseline 数据在内层 value。"
    "探测工具 tools/probe_remote_mux.js。**\n\n"
)
ptext = ptext[:index] + new_line + ptext[index:]
with open(progress, "w", encoding="utf-8", newline="") as handle:
    handle.write(ptext)
print("PROGRESS 断点已追加")
