# DSH 免令牌网关插件（dsh-auth-gateway）设计与实现

> 状态：**已实现并通过隔离实例验证（v1.1.1）；主 profile 已安装，待重启 dsh 生效**（2026-09-16）
> 产出物：`tools/dsh-auth-gateway/`（插件源码）、`C:\Users\Carl\.dsh\local-plugins\dsh-auth-gateway-1.1.1.tgz`（安装副本）

## 0. 最终形态（一句话）

插件**跑在 dsh 进程内**，在 dsh 自己的认证判定上**放行"经 Tailscale Serve 转发进来的请求"**
（判据：来源为本机 loopback + Host 命中 `--trusted-host` 受信名单），因此：

- **Tailscale Serve 保持指向 3080 不变**（不改 Serve、不改任何启动脚本 / bat）；
- 手机 App 用**裸地址**（`https://<机器名>.<tailnet>.ts.net/`，不带 `?token=`）**直接可用**；
- dsh 启动即生效、dsh 退出即消失，**零独立进程、零计划任务**。

> ⚠️ **安全含义**：**Tailscale 私有网络内可达的设备访问 Web UI 不再需要令牌**（信任边界落在 tailnet；公网仍不可达）。
> 关闭配置项 `allowTailnetForwarded`、或卸载插件，即恢复 dsh 原生认证。

## 1. 要解决的问题

dsh 从 0.1.2-rc.1 起给 Web UI 加了一次性令牌认证：**不带令牌访问裸地址会返回 401**。

手机 App 的目标使用方式是"**只填 `https://<机器名>.<tailnet>.ts.net/` 就可用**"（不填令牌、不用每次重启后重新粘贴链接）。而令牌每次 `dsh web` 重启都会更换，所以必须有东西替 App 完成认证，并且这件事要**长期无感地自动进行**。

### 用户对方案的明确要求（逐字）

1. "能否实现 远端app无感 本地路由自动填入 token？？"
2. "所谓无感是 app只使用无token的 url"；"若带了token也能识别支持"
3. "他应该是一个无感 伴随dsh启动跟随启动的东西"
4. "而不是独立维护的"
5. "dsh有很多 管理bat 这个东西 和控制bat毫无关联 完全无法配套管理"
6. "任何修改都要动bat 那简直没完没了"
7. "不要动已有bat"

## 2. 方案演进（为什么最终是"认证链放行"）

| 方案 | 做法 | 结论 |
|---|---|---|
| A. App 内粘贴带令牌链接 | App 解析 `?token=`，换取并持久化会话 Cookie（30 天） | 已实现并保留（兜底手段），但不满足"只用无 token 的 url" |
| B. 改桌面启动脚本（bat）内联启动代理 | 在 `启动DSH-WebUI.bat` 里顺带起代理进程 | **否决**：违反用户第 5、6、7 条（代理与 dsh 控制脚本毫无关联；每次调整都要动 bat） |
| C. 独立常驻代理进程 + 计划任务开机自启 | 外部 Node 进程读日志取令牌，转发注 Cookie | **否决**：违反第 3、4 条（属"独立维护的东西"）；计划任务可能被安全软件拦截；用户实测"关掉那个 cmd 窗口 App 就连不上" |
| D1. dsh 插件 + 本地反向代理端口（Serve 指向该端口） | 插件在 3081 起反向代理注入 Cookie；`tailscale serve` 指向 3081 | **已实现并验证通过，但落地受阻**：用户启动脚本内置逻辑会把 Serve 强制指回 3080（`serve status` 找不到 3080 就重设），**每次重启 dsh 都会覆盖**；用户明确不允许改 bat |
| **D2. dsh 插件 + 认证判定放行 Serve 转发（采纳）** | 插件在进程内包一层认证判定：`requestRejection`（API/WS 升级）与 `authorizeIndex`（首页），对"loopback 来源 + 受信 Host"的请求放行 | **采纳**：Serve 保持 3080，**任何脚本都不用改**，裸地址直接可用 |

D1 的反向代理能力仍保留在插件内（配置项 `port`，默认 3081），作为"若将来 Serve 改指向网关"时的备用形态；当前生效路径是 D2。

## 3. 实现

### 3.1 插件结构

```
tools/dsh-auth-gateway/
├── package.json          # dsh.bundle.patch 声明（与 dsh-image-serve 同款约定）
├── cordis.patch.yml      # insert 条目：id=auth-gateway, name=dsh-auth-gateway
├── README.md             # 安装 / 运维 / 回滚速查
└── lib/index.mjs         # 插件实现（零第三方依赖，仅 Node 内置 http + schemastery）
```

### 3.2 认证链放行（当前生效路径，v1.1.1 起）

dsh 的请求认证有**两个入口**，插件两处都包：

```js
// ① API 与 WebSocket 升级：认证判定入口
const originalRejection = ctx.connection.requestRejection.bind(ctx.connection);
ctx.connection.requestRejection = (request) => {
  const verdict = originalRejection(request);
  if (verdict === undefined) return undefined;              // 原生认证已通过
  return isForwardedFromTailnet(request) ? undefined : verdict;
};

// ② 首页 HTML：走的是另一条入口（实测只补 ① 时 API 已放行、根路径仍 401）
const originalAuthorizeIndex = ctx.connection.authorizeIndex.bind(ctx.connection);
ctx.connection.authorizeIndex = (req, res) => {
  if (isForwardedFromTailnet(req)) return true;             // 直接放行首页
  return originalAuthorizeIndex(req, res);
};
```

放行判据（**两个条件同时满足**）：

| 条件 | 取值 | 原因 |
|---|---|---|
| 来源地址 | 本机 loopback（`127.0.0.1` / `::1`） | Tailscale Serve 是本机进程，转发进来的请求必来自 loopback |
| `Host` 头 | 命中 dsh 的 `--trusted-host` 名单（如 `carl-pc.taild10021.ts.net`） | 本机浏览器用 `127.0.0.1:3080` 访问时 Host 不受信，仍走原生认证（实测 401） |

### 3.3 令牌换取会话 Cookie（备用路径 / 反向代理形态）

dsh 的 `connection` 服务暴露 `authenticatedUrl(baseUrl)`，其实现是把**进程内启动令牌**写进 `?token=`：

```js
authenticatedUrl(baseUrl) {
  const url = new URL(baseUrl);
  url.pathname = "/"; url.search = ""; url.hash = "";
  url.searchParams.set(TOKEN_QUERY, this.launchToken);
  return url.href;
}
```

官方 `dsh-web-app` 打印 `dsh web: http://.../?token=...` 用的就是它——**插件在进程内就能取令牌，不需要读日志文件**。拿到后 `GET` 一次即得会话 Cookie（默认 30 天），供反向代理形态注入。令牌是进程内变量，dsh 重启后插件自动重新换取，全程无人工干预。

反向代理形态的转发规则（保留能力，当前 Serve 未指向它）：

| 环节 | 处理 | 原因 |
|---|---|---|
| `Host` 头 | 改写为 `127.0.0.1:<上游端口>` | dsh 的 Host/Origin 信任围栏按 Host 判定；并保证与签发 Cookie 时的 Host 一致 |
| `Origin` / `Referer` | 剥离 | 外部域名来源会被围栏判定为跨源 |
| `Cookie` | 注入网关缓存的那一份 | 用网关的有效凭证，避免客户端旧 Cookie 干扰 |
| 401 响应 | 强制刷新 Cookie 并**重试一次** | Cookie 过期/被拒时自愈；只重试一次，避免循环 |
| WebSocket 升级 | 同规则转发（含 101 透传） | 浏览器端实时通道需要 |

### 3.4 生命周期与配置

```js
ctx.effect(() => {
  server.listen(config.port, "127.0.0.1", ...);   // 反向代理（备用形态）
  return () => server.close();                    // 插件卸载 / 进程退出时关闭
}, "dsh-auth-gateway: reverse proxy");
```

端口被占用时只记录日志（`listen EADDRINUSE`）并降级，**不会让 dsh 启动失败**（已实测）。

| 配置 | 默认 | 说明 |
|---|---|---|
| `allowTailnetForwarded` | `true` | **免令牌放行开关**（关闭即恢复原生认证） |
| `port` | 3081 | 反向代理监听端口（备用形态；当前 Serve 不指向它） |
| `upstreamHost` / `upstreamPort` | `127.0.0.1` / `3080` | 反向代理上游 |
| `cookieRefreshMs` | 6 小时 | 会话 Cookie 主动刷新间隔；401 时另有被动刷新 |

覆盖方式（用户 `cordis.patch.yml`；注意 patch 行是**整对象替换**语义，字段要写全）：

```yaml
- id: auth-gateway
  config:
    allowTailnetForwarded: true
    port: 3081
    upstreamPort: 3080
```

## 4. 部署形态

```
手机 / 浏览器 ──443──▶ Tailscale Serve ──▶ 127.0.0.1:3080（dsh web）
                                             ▲
                     插件在进程内放行"来自 Serve 的请求"
```

- **Tailscale Serve 保持 `http://127.0.0.1:3080` 不变**（用户的启动 / 重启脚本本来就维护它）。
- 本机浏览器：仍用 dsh 打印的带令牌地址访问（启动脚本已自动打开），行为不变。
- 远程设备：访问 `https://<机器名>.<tailnet>.ts.net/`（**裸地址**），由插件放行。

## 5. 验证证据（2026-09-16）

### 5.1 隔离实例验证（随机端口 + 独立 home，全程不影响 3080）

实例启动参数带 `--trusted-host carl-pc.taild10021.ts.net`，用 curl 模拟两类来源：

| 验证项 | 请求特征 | 结果 |
|---|---|---|
| 插件加载 | 启动日志 | `[auth-gateway] 已开启 tailnet 免令牌放行（受信 Host: carl-pc.taild10021.ts.net）` |
| **API：Serve 转发风格** | `Host: carl-pc.taild10021.ts.net`，来源 loopback，无 Cookie/令牌 | **200** |
| **首页：Serve 转发风格** | 同上，`GET /` | **200**（v1.1.1 修复点；v1.1.0 时为 401） |
| 对照：本机裸 Host | `Host: 127.0.0.1:<port>` | API 401 / 首页 401（原生认证保留，范围未扩大） |
| 对照：Host 拼写错误 | `Host: carl-cp.taild10021.ts.net` | 401 + `dsh web authentication required...`（精确匹配生效） |
| 反向代理端口（备用形态） | `POST http://127.0.0.1:31235/api/session/list` | 200 |
| 进程归属 | 两端口 PID | 同属 dsh 进程（非独立进程） |

### 5.2 主 profile 重启前预验证

用主 home 在隔离端口起实例：**正常监听、无崩溃**（此前被 pnpm 带回来的第三方 `dsh-file-upload` 已移除，否则会因与内置同名而 `duplicate loader entry id` 启动失败）。

### 5.3 未验证项（如实标注）

- **重启主服务后的实际生效**（需重启 dsh 才能验证，见 §9 待办）。
- 真机经 Tailscale Serve 的完整链路（服务器侧能力已由 curl 模拟 Serve 条件验证，手机侧需实测）。
- WebSocket 升级路径的放行（App 当前走轮询，未构造 WS 客户端实测；代码路径与 API 相同）。

## 6. 运维

| 场景 | 表现 | 处理 |
|---|---|---|
| 确认插件在跑 | dsh 日志含 `[auth-gateway] 已开启 tailnet 免令牌放行` | 无该行则重启 dsh（插件随 dsh 启动） |
| 手机连不上但本机能用 | 检查 Tailscale 是否在线、Serve 是否指向 3080 | `tailscale serve status`；恢复：`tailscale serve --bg --https=443 http://127.0.0.1:3080` |
| 想临时恢复原生认证 | —— | 用户 `cordis.patch.yml` 里把 `allowTailnetForwarded` 设为 `false` 后重启 dsh |
| 端口占用告警 | 日志 `监听 3081 失败: listen EADDRINUSE` | 属备用形态端口，不影响放行功能；如需可改 `port` |

## 7. 回滚

```powershell
$env:DSH_HOME = "C:\Users\Carl\.dsh"
dsh plugin --profile web remove dsh-auth-gateway
# 重启 dsh 生效：此后恢复 dsh 原生令牌认证
# App 仍可用"带令牌链接"方式（粘贴一次换取 30 天 Cookie）
```

## 8. 本次发现的坑（已固化到全局规则）

**`dsh plugin ... add` 会把先前移除的 `dsh-file-upload` 重新登记进 bundles**：dsh 0.1.5 起内置 `file-upload`，第三方同名插件同时挂载会 `duplicate loader entry id` 导致**启动即崩**。本次安装网关插件后 bundles 中又出现了它，已在重启前移除（同时从 `dependencies` 删除）。

**因此：每次 `dsh plugin add/install` 之后，必须复查 `~/.dsh/profiles/web/package.json` 的 `dsh.profile.bundles`，确认没有多余的 `dsh-file-upload`。**

**`dsh plugin install` 不会补齐被删除的插件，还会把登记一起清掉（2026-09-16 隔离环境实测）**：手动删除 `profiles/web/node_modules/dsh-auth-gateway` 后再执行 `dsh plugin --profile web install`，输出 `Already up to date`（pnpm 认为依赖已满足）**并未恢复插件**，且 `dsh.profile.bundles` 中的 `dsh-auth-gateway` **同时消失**——说明 **bundles 清单记录的是"实际安装状态"而非"期望状态"**，dsh 没有"声明后自动补齐"的能力。因此插件丢失后**唯一恢复手段是重新 `add` 插件包**（见 §10）。

## 9. 安装与恢复（插件如何"伴随 dsh"）

### 9.1 事实边界

| 问题 | 事实 |
|---|---|
| 装好之后会不会跟着 dsh 跑？ | **会**。dsh 每次启动按 `dsh.profile.bundles` 加载插件，无需任何外部机制 |
| dsh 版本升级会不会弄丢插件？ | **不会**。插件装在用户目录 `~/.dsh`，npm 升级只动 dsh 自身安装目录（两个解析锚点：dsh 安装目录、profile 目录） |
| 能否"声明期望状态后自动安装"？ | **不能**。实测 `dsh plugin install` 不补齐缺失插件、还会清掉登记（见 §8） |
| 什么情况会丢？ | 删除/重建 `~/.dsh`、换电脑、重装系统、手工删过 `profiles/web/node_modules` 里的插件目录 |

### 9.2 一键安装 / 修复脚本

```
tools/install-auth-gateway.ps1
```

```powershell
powershell -ExecutionPolicy Bypass -File "D:\AI任务\dsh-mobile-app\tools\install-auth-gateway.ps1"
```

脚本行为（**幂等**，重复运行无副作用）：

1. 定位插件包：优先 `~/.dsh/local-plugins/` 中版本号最大的 tgz，其次仓库 `tools/dsh-auth-gateway/`；
2. 检查 profile 清单登记与已装版本，**已是目标版本则只报告状态、不做任何改动**；
3. 需要时执行 `dsh plugin --profile web add <tgz>`；
4. **自动复查并移除冲突项 `dsh-file-upload`**（与 dsh 内置同名，否则重启即崩）；
5. 输出结果并提示"重启 dsh 生效"。

可选参数：`-DshHome`（指定 dsh 用户目录，默认 `$env:DSH_HOME` 或 `~/.dsh`）、`-DshCommand`、`-PluginStore`。

**何时需要跑**：新机器部署、删除过 `~/.dsh`、dsh 大版本升级后、插件被误删时。日常运行**不需要**跑（插件已随 dsh 启动自动加载）。

**为什么不挂成自动机制**：用户明确要求"不是独立维护的东西"，且**在 dsh 进程内执行代码的唯一载体就是插件本身**（鸡生蛋问题），不存在"dsh 启动时自动安装自己"的路径；因此把安装动作固化为**一次性命令**，安装在用户目录后即长期生效。

### 9.3 验证记录（隔离环境）

| 场景 | 结果 |
|---|---|
| 主环境已装 1.1.1 时运行脚本 | 报告"已是目标版本，无需改动"，未修改任何文件（幂等） |
| 隔离环境（清单登记=False、无插件目录）运行脚本 | 成功安装 → 登记=True、插件目录就位、bundles 恢复 |
| 归档副本 | `~/.dsh/local-plugins/dsh-auth-gateway-1.1.1.tgz`（恢复来源） |

## 9. 待办与变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-16 | 初版（v1.0.0）：反向代理形态，隔离验证通过；主 profile 安装 |
| 2026-09-16 | 发现"用户启动脚本会把 Serve 指回 3080"，D1 形态无法落地 → 改为认证链放行 |
| 2026-09-16 | v1.1.0：新增 `requestRejection` 放行（API 通过，首页仍 401） |
| 2026-09-16 | v1.1.1：补 `authorizeIndex` 放行首页；隔离实例四项对照全部符合预期；主 profile 升级安装 |
| — | **待用户重启 dsh 生效**；旧机制（计划任务 `DSH-AuthProxy`、`start-auth-proxy.vbs/.cmd`、外部代理进程、3081 占用）已全部清理 |
