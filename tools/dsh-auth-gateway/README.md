# dsh-auth-gateway

DSH 免令牌网关插件：**让手机 App 用裸地址（不带 `?token=`）远程访问 dsh Web UI**。

## 它解决什么问题

dsh 从 0.1.2-rc.1 起对 Web UI 启用链接令牌认证——裸地址访问返回 401，而令牌每次
`dsh web` 重启都会更换。手机 App 想"只填一个地址就一直能用"，就必须有人替它把令牌
换成长期会话凭证。

本插件**跑在 dsh 进程内**，在 `127.0.0.1:3081` 起一个本地反向代理，转发请求给
dsh 自身（默认 `127.0.0.1:3080`），并自动注入有效会话 Cookie：

```
手机 ──443──▶ Tailscale Serve ──▶ 127.0.0.1:3081（本插件）──▶ 127.0.0.1:3080（dsh web）
```

令牌直接用 dsh 内部的 `connection.authenticatedUrl()` 取（官方打印
`dsh web: http://.../?token=...` 用的就是它），**不读日志、不需要外部脚本**。

## 为什么是插件（而不是外部代理进程）

| 方案 | 问题 |
|---|---|
| 外部 Node 代理 + 计划任务开机自启 | 属"独立维护的东西"；可能被安全软件拦截；误关窗口即断连 |
| 改桌面启动 bat 内联启动代理 | 代理与 dsh 控制脚本毫无关联，不成体系；每次调整都要动 bat |
| **本插件** | 随 dsh 启动自动加载、随 dsh 退出自动消失；**零独立进程、零计划任务、不碰任何 bat** |

## 安装

```powershell
$env:DSH_HOME = "C:\Users\Carl\.dsh"
dsh plugin --profile web add <本包 tgz 路径>     # 例如 C:\Users\Carl\.dsh\local-plugins\dsh-auth-gateway-1.0.0.tgz
# 重启 dsh 使其生效（bundles 清单变更不支持热加载）
```

安装后 **必须复查** `~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles`：
不得出现 `dsh-file-upload`（第三方版与 dsh 0.1.5 内置同名，会导致
`duplicate loader entry id`，dsh 启动即崩；安装动作有时会把它带回来）。

## 配套设置

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:3081   # Serve 指向网关而非 dsh 本体
```

## 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `port` | 3081 | 网关监听端口 |
| `upstreamHost` | `127.0.0.1` | 上游主机 |
| `upstreamPort` | 3080 | 上游（dsh 自身）端口 |
| `cookieRefreshMs` | 6 小时 | 会话 Cookie 主动刷新间隔；上游 401 时立即刷新并重试一次 |

覆盖方式（用户 `cordis.patch.yml`，注意 patch 是**整对象替换**语义，字段要写全）：

```yaml
- id: auth-gateway
  config:
    port: 3081
    upstreamPort: 3080
```

## 运维

| 场景 | 处理 |
|---|---|
| 确认网关在跑 | `Get-NetTCPConnection -LocalPort 3081 -State Listen` 有输出即正常；无输出则重启 dsh |
| 日志 | 与 dsh 同一日志流（`%USERPROFILE%\.dsh\logs\dsh-webui.log`），前缀 `[auth-gateway]` |
| 端口被占 | 日志出现 `监听 3081 失败: listen EADDRINUSE`；释放占用后重启 dsh（插件会优雅降级，不会导致 dsh 启动失败） |

## 回滚

```powershell
$env:DSH_HOME = "C:\Users\Carl\.dsh"
dsh plugin --profile web remove dsh-auth-gateway
tailscale serve --bg --https=443 http://127.0.0.1:3080   # Serve 指回 dsh 本体
# 重启 dsh 生效；之后 App 需改用"带令牌链接"方式（App 仍支持）
```

完整设计与验证证据见项目文档 `docs/dsh-auth-gateway-plugin.md`。
