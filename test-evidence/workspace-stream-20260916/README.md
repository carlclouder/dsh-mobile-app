# 工作区流修复证据（2026-09-16）

## 报障（用户原话）
"dsh 主机端更新版本后；app端出现工作区 子目录消失，所有会话无分类，大量历史会话重新显示"

## 根因
新版 dsh 移除了 `workspace/list` 端点；工作区列表与归档集合只经 WebSocket 端点
`/api/remote.mux` 的 `workspace/follow` 流下发。App 仍调用旧 REST（404）→ 三个现象连锁出现。

## 宿主侧真实数据（Node 直连该流实测）
- 工作区 6 个：WeiduLinggan(1) / dsh-mobile-app(5) / AI任务(30) / Desktop(75) / RemoteProc(5) / Quantitative_investment_system(16)
- 归档会话：**157 个**（修复前全部重新显示）

## 证据文件
| 文件 | 说明 |
|---|---|
| before_workspace_empty.png | 修复前：工作区筛选条只有"全部"、会话无分类 |
| after_workspace_restored.png | 修复后：出现 dsh-mobile-app / Quantitative_investment_system 等工作区，会话按工作区分组 |
| workspace_switch.gif | 12 帧动图（间隔 600ms）：初始态 3 帧 → 点选工作区 → 切换后 9 帧（规则 18 切换类场景要求） |

## 实现要点（两个实测坑，已写入代码注释与 docs/DESIGN.md §13.4）
1. 开流帧 `payload` 必须恰含一个 args 字段（`{"args":{}}`），否则服务端回
   `gateway/internal: Remote payload must contain exactly one plain-object args field`；
2. baseline 帧业务数据在**内层 value**：`{"type":"baseline","value":{"items":[…],"archivedSessionIds":[…]}}`。

## 验证环境与边界
- 环境：模拟器（AVD dsh_test）连**真实宿主 3080**，App 为 debug 包（debug 有 Host 改写，模拟器才能访问宿主）。
- 单测 101/101、release v0.1.183 构建 0 错。
- **未验证**：真机 release 包（用户侧实测）。
