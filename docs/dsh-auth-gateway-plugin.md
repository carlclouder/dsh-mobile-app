# DSH 免令牌网关插件（dsh-auth-gateway）设计与实现

> 状态：**已实现并在隔离实例验证通过；主 profile 已安装，待重启生效**（2026-09-16）
> 产出物：`tools/dsh-auth-gateway/`（插件源码）、`C:\Users\Carl\.dsh\local-plugins\dsh-auth-gateway-1.0.0.tgz`（安装副本）

## 1. 要解决的问题

dsh 从 0.1.2-rc.1 起给 Web UI 加了一次性令牌认证：**不带令牌访问裸地址会返回 401**。

手机 App 的目标使用方式是"**只填 `https://<机器名>.<tailnet>.ts.net/` 就可用**"（不填令牌、不用每次重启后重新粘贴链接）。而令牌每次 `dsh web` 重启都会更换，所以必须有东西替 App 完成"令牌 → 会话凭证"的兑换，并且这件事要**长期无感地自动进行**。

### 用户对方案的明确要求（逐字）

1. "能否实现 远端app无感 本地路由自动填入 token？？"
2. "所谓无感是 app只使用无token的 url"；"若带了token也能识别支持"
3. "他应该是一个无感 伴随dsh启动跟随启动的东西"
4. "而不是独立维护的"
5. "dsh有很多 管理bat 这个东西 和控制bat毫无关联 完全无法配套管理"
6. "任何修改都要动bat 那简直没完没了"
7. "不要动已有bat"

## 2. 方案演进（为什么最终是 dsh 插件）

| 方案 | 做法 | 否决原因 |
|---|---|---|
| A. App 内粘贴带令牌链接 | App 解析 `?token=`，换取并持久化会话 Cookie（30 天） | 令牌每次重启更换，用户需反复取令牌粘贴；不满足用户第 1、2 条"无感、只用无 token 的 url" |
| B. 改桌面启动脚本（bat）内联启动代理 | 在 `启动DSH-WebUI.bat` 里顺带起代理进程 | 违反用户第 5、6、7 条：代理与 dsh 的控制脚本毫无关联，塞进去不成体系、每次调整都要动 bat，且用户明确要求"不要动已有 bat" |
| C. 独立常驻代理进程 + 计划任务开机自启 | 外部 Node 进程读日志取令牌，转发注 Cookie | 违反用户第 3、4 条：是"独立维护的东西"，且计划任务可能被安全软件拦截或不启动；用户实测"关掉那个 cmd 窗口 App 就连不上" |
| **D. dsh 插件（采纳）** | 把网关做成 dsh 自身的 bundle 插件，**跑在 dsh 进程内** | — |

方案 D 满足全部要求：dsh 启动 → 插件加载 → 网关就绪；dsh 退出 → 网关随之消失。**没有独立进程、没有计划任务、不碰任何 bat**。以后调整只改插件（重新打包安装 + 重启 dsh）。

## 3. 实现

### 3.1 插件结构

```
tools/dsh-auth-gateway/
├── package.json          # dsh.bundle.patch 声明（与 dsh-image-serve 同款约定）
├── cordis.patch.yml      # insert 条目：id=auth-gateway, name=dsh-auth-gateway
└── lib/index.mjs         # 插件实现（零第三方依赖，仅 Node 内置 http + schemastery）
```

### 3.2 认证机制（关键）

dsh 的 `connection` 服务暴露 `authenticatedUrl(baseUrl)`，其实现是：

```js
authenticatedUrl(baseUrl) {
  const url = new URL(baseUrl);
  url.pathname = "/"; url.search = ""; url.hash = "";
  url.searchParams.set(TOKEN_QUERY, this.launchToken);   // 进程内启动令牌
  return url.href;
}
```

官方 `dsh-web-app` 打印那行 `dsh web: http://127.0.0.1:3080/?token=...` 用的就是它——**所以插件在进程内就能拿到令牌，不需要读日志文件**。

拿到带令牌的根地址后 `GET` 一次，dsh 的 `authorizeIndex` 会返回 303 并 `Set-Cookie` 下发会话 Cookie；插件缓存该 Cookie 并注入到后续所有转发请求。

> 令牌与 Cookie 的关系：令牌是**进程内变量**（可反复取用），Cookie 是**对外凭证**（默认 30 天）。dsh 重启 → 新进程 → 新令牌 → 插件重新换一次 Cookie，全程无需人工干预。

### 3.3 转发规则

| 环节 | 处理 | 原因 |
|---|---|---|
| `Host` 头 | 改写为 `127.0.0.1:<上游端口>` | dsh 的 Host/Origin 信任围栏按 Host 判定；同时保证与签发 Cookie 时的 Host 一致 |
| `Origin`/`Referer` | 剥离 | 外部域名来源会被围栏判定为跨源 |
| `Cookie` | 注入网关缓存的那一份 | 用网关的有效凭证，避免客户端旧 Cookie 干扰 |
| 401 响应 | 强制刷新 Cookie 并**重试一次** | Cookie 过期/被拒时自愈；只重试一次避免循环 |
| WebSocket 升级 | 同规则转发（含 101 透传） | 浏览器端实时通道需要；App 当前走轮询，保留完整能力 |
| 监听地址 | 仅 `127.0.0.1` | 安全边界不变，远程可见性仍由 Tailscale Serve 决定 |

### 3.4 生命周期

```js
ctx.effect(() => {
  server.listen(config.port, "127.0.0.1", ...);
  return () => server.close();       // 插件卸载/进程退出时关闭
}, "dsh-auth-gateway: reverse proxy");
```

端口被占用时只记录日志（`listen EADDRINUSE`）并降级，**不会让 dsh 启动失败**（已实测）。

### 3.5 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `port` | 3081 | 网关监听端口（Tailscale Serve 指向它） |
| `upstreamHost` | `127.0.0.1` | 上游（dsh 自身）主机 |
| `upstreamPort` | 3080 | 上游端口 |
| `cookieRefreshMs` | 6 小时 | 主动刷新间隔；401 时另有被动刷新 |

覆盖方式（用户 `cordis.patch.yml`，注意 patch 行是**整对象替换**语义，写的字段要全）：

```yaml
- id: auth-gateway
  config:
    port: 3081
    upstreamPort: 3080
```

## 4. 部署形态

```
手机/浏览器 ──443──▶ Tailscale Serve ──▶ 127.0.0.1:3081（本插件，注入会话 Cookie）
                                              │
                                              ▼
                                      127.0.0.1:3080（dsh web 本体，仍需认证）
```

- 本机浏览器：仍可直接用 dsh 打印的带令牌地址访问 3080（启动脚本已自动打开）。
- 远程设备：访问 `https://<机器名>.<tailnet>.ts.net/`（**裸地址，无令牌**），由插件完成认证。
- **Tailscale Serve 需指向 3081**（原为 3080）：
  ```powershell
  tailscale serve --bg --https=443 http://127.0.0.1:3081
  ```

## 5. 验证证据（2026-09-16）

### 5.1 隔离实例验证（随机端口 + 独立 home，不影响 3080）

| 验证项 | 命令要点 | 结果 |
|---|---|---|
| 插件随 dsh 加载 | 启动日志 | `[auth-gateway] 免令牌网关已就绪: http://127.0.0.1:31235 → http://127.0.0.1:31234` |
| **网关裸访问（无 token）** | `POST http://127.0.0.1:31235/api/session/list` | **200** |
| 对照：直连 dsh 裸访问 | `POST http://127.0.0.1:31234/api/session/list` | 401（证明认证由插件补齐） |
| 网关首页裸访问 | `GET http://127.0.0.1:31235/` | 200 |
| **非独立进程** | 两端口归属 PID | 31235 与 31234 同属 PID 31352（= dsh 进程） |
| Cookie 获取 | 插件日志 | `[auth-gateway] 已刷新会话 Cookie` |

### 5.2 主 profile 重启前预验证

用主 home 在隔离端口（31250）起实例：**正常监听、无崩溃**（此前被 pnpm 带回来的第三方 `dsh-file-upload` 已移除，否则会因与内置同名而 `duplicate loader entry id` 启动失败）。插件已加载并如实报告 `3081 EADDRINUSE`（旧代理仍在占用，属预期，且不影响 dsh 启动）。

### 5.3 未验证项（如实标注）

- 重启主服务后插件在 3080 场景下的实际生效（需用户重启 dsh 才能验证）。
- 真机经 Tailscale Serve 的完整链路（服务器侧链路由 curl 验证，手机侧需实测）。
- WebSocket 升级转发路径（App 走轮询，未构造 WS 客户端实测）。

## 6. 运维

| 场景 | 表现 | 处理 |
|---|---|---|
| 确认网关在跑 | `Get-NetTCPConnection -LocalPort 3081 -State Listen` 有输出 | 无输出则重启 dsh（插件随 dsh 启动） |
| 端口被占用 | 日志 `监听 3081 失败: listen EADDRINUSE` | 释放占用者后重启 dsh |
| 手机连不上但本机能用 | 检查 Tailscale Serve 是否指向 3081 | `tailscale serve --bg --https=443 http://127.0.0.1:3081` |
| 网关日志 | 与 dsh 同一条日志流（`%USERPROFILE%\.dsh\logs\dsh-webui.log`），前缀 `[auth-gateway]` | — |

## 7. 回滚

```powershell
$env:DSH_HOME = "C:\Users\Carl\.dsh"
dsh plugin --profile web remove dsh-auth-gateway
tailscale serve --bg --https=443 http://127.0.0.1:3080   # Serve 指回 dsh 本体
# 重启 dsh 生效
```

回滚后 App 需改用"带令牌链接"方式（App 仍支持：粘贴一次换取 30 天 Cookie）。

## 8. 本次发现的坑（已固化到规则）

**`dsh plugin ... add` 会把先前移除的 `dsh-file-upload` 重新登记进 bundles**：dsh 0.1.5 起内置 `file-upload`，第三方同名插件同时挂载会 `duplicate loader entry id` 导致**启动即崩**。本次安装网关插件后 bundles 中又出现了它，已在重启前移除（同时从 `dependencies` 删除）。

**因此：每次 `dsh plugin add/install` 之后，必须复查 `~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles`，确认没有多余的 `dsh-file-upload`。**

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-16 | 初版：插件实现、隔离验证、主 profile 安装、重启前预验证；文档同步 |
