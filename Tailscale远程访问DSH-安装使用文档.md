# Tailscale 远程访问 DSH：安装与使用

> 更新：2026-10-21。本文是当前部署指南，不沿用旧文档中某台设备“已全部配置完成”的结论。App 功能见 [USAGE.md](USAGE.md)，源码架构见 [ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 1. 连接关系与端口约定

```text
Android App / 手机浏览器
  → Tailscale 私有网络中的 HTTPS 地址
  → 电脑上的 Tailscale Serve
  → 部署配置明确指定的本地目标
      ├─ DSH 本体：同时满足 Host 与原生认证要求
      └─ 认证网关：再转发给 DSH 本体
```

| 项目 | 本项目约定 |
| --- | --- |
| 生产 DSH | 既有实例，通常监听 `127.0.0.1:3080`；实际值以已确认的部署配置为准 |
| 隔离测试 | `3081` 用于不影响生产的测试，不作为生产推荐端口 |
| 可选网关 | 监听端口和上游端口须单独指定，不能与生产或测试实例冲突 |
| Tailscale Serve | 目标须与当前部署配置一致，不能仅凭“有端口监听”决定 |

网关源码默认 `port=3081`、`upstreamPort=3080`，这是源码缺省值，不是生产部署决定。3080 和 3081 不能通用互换：它们可能属于不同实例、数据目录或认证路径。已有部署先查看状态，不能照抄命令覆盖。

本轮文档对齐不修改任何运行端口、启动脚本或 Serve 配置。实际环境若仍把 Serve 转发到测试端口，应单独核对目标归属；文档修订不等于运行环境已迁移。

## 2. 准备条件

- 电脑安装并配置 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)，本地模型和会话可用。
- Android 8.0 及以上设备，安装 [DSH Mobile 发布包](release/DSH-Mobile-release-v0.1.223.apk)。
- 手机与电脑安装 [Tailscale](https://tailscale.com/download)，加入同一 tailnet，访问规则允许手机连接电脑。
- 电脑上的服务监听在回环地址，由 Serve 提供远程入口；不要为排障直接把 DSH 暴露到公网。

Tailscale 支持多种身份提供商，邮箱、Google、Apple 等登录方式本身不是能否互通的判据。设备所属 tailnet、访问规则及在线状态才决定可达性。登录说明见[官方身份提供商文档](https://tailscale.com/docs/integrations/identity)。

## 3. 检查电脑已有部署

先执行只读检查：

```powershell
tailscale status
tailscale serve status
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 3080, 3081 }
```

记下 HTTPS 域名、Serve 的真实目标、监听端口及其进程归属。若使用网关，还要核对网关的 `port`、`upstreamHost`、`upstreamPort`、目标 DSH 数据目录与插件配置。

有监听只能说明进程占用端口，不能证明它是正确实例、认证已通过或 API 可用。若生产已有启动管理方式，沿用其配置来源；不要额外启动第二个同端口实例，也不要先改 Serve 试错。

## 4. 新建入口的配置示例

以下示例只适用于已确认使用生产默认端口、准备新建直连入口的部署。已有生产实例不需要重复运行。

### 启动 DSH

先取得电脑的 Tailscale HTTPS 域名，然后使用当前 `dsh web --help` 支持的参数：

```powershell
dsh web --host 127.0.0.1 --port 3080 --no-open --trusted-host <机器名>.<tailnet>.ts.net
```

`--trusted-host` 用于受信域名检查，不能替代登录认证。需要认证时，使用 DSH 输出的有效带令牌链接或按下节配置并验证网关。

### 配置 Serve

确认 DSH 本体及认证链路适合直接作为入口后，才执行：

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:3080
tailscale serve status
```

若部署要求经过网关，应将目标改成**已明确配置且验证过的网关监听端口**；不要把源码默认的测试端口直接用于生产。该选择需同时核对上游实例和启动管理配置。

Serve 的功能和参数以 [Tailscale 官方 Serve 文档](https://tailscale.com/docs/reference/tailscale-cli/serve)为准。本文采用 tailnet 内可达的 Serve，不要求开放公网入口。

## 5. 认证与网关兼容

### 使用宿主令牌

在 App 设置中填写 DSH 提供的完整有效链接。App 解析 `?token=`，换取并保存会话 Cookie。换取凭证和后续访问须使用同一访问地址；不要把本机地址签发的凭证当作所有域名通用。

一次性令牌已消费后不能反复使用。凭证失效或域名变化时，应获取新链接重新认证。不要把实际令牌、Cookie 或含令牌的完整链接放入公开日志和截图。

### 使用网关

仓库保留的 `dsh-auth-gateway` 同时包含旧认证钩子和本地 Cookie 反向代理。两者具有不同的接口依赖，不能仅凭插件日志判断有效：

- 旧钩子修改 `requestRejection`、`authorizeIndex`，并检查回环来源和受信 Host。新宿主的 API 认证路径可能变化，出现首页可开而 API 返回 401。
- 反向代理从宿主进程获取认证地址，换取 Cookie 后转发 HTTP 与 WebSocket。使用前必须独立明确网关端口及上游实例，并验证两类流量。

不要为了绕过认证失败，把生产 Serve 指向测试 3081。具体源码合同、安装范围与兼容边界见[网关说明](docs/dsh-auth-gateway-plugin.md)。

## 6. 手机配置与验收

1. 打开 Tailscale，连接正确的 tailnet，按系统提示允许 VPN。
2. 安装 DSH Mobile，设置 HTTPS 地址或有效带令牌链接并保存。
3. 允许通知；需要后台使用时调整电池限制。
4. 查看已有会话历史，发送一条测试消息，确认宿主正常回复。
5. 进入存在审批或提问的测试会话，确认卡片可见并能回答，后续队列继续。
6. 宿主支持实时思考时，检查底部单行更新、展开收起、上滑留位与最终回复。

设置页“测试连接”只检测首页可达。完整验收必须包含 API 读写和 WebSocket 订阅，不能把首页 200 当作 App 已正常工作。

已有的设备验证及未验证范围见[交互验收](test-evidence/pending-interactions-20261020/README.md)和[实时思考验收](test-evidence/thinking-preview-20261021/README.md)。这些记录来自本地模拟器，不代表所有真机和远程网络均已验证。

## 7. 分层排障

| 现象 | 优先检查 | 判断边界 |
| --- | --- | --- |
| 域名不可达 | Tailscale 在线状态、设备所属 tailnet、访问规则、DNS、HTTPS | 先确认网络，不先重装 App |
| 502 | Serve 当前目标、目标进程监听和网关上游 | 监听必须属于目标实例，不能随便切端口 |
| 401 | 令牌、Cookie、认证路径与网关兼容 | 首页成功不能证明 API 成功 |
| 403 | 受信 Host、Origin 与访问地址是否匹配 | 加 `--trusted-host` 不等于取消认证 |
| 404 | 请求端点、宿主版本与协议是否匹配 | 不能把协议变化当作登录失败 |
| 页面空列表 | API 响应、工作区筛选、空白/子代理/归档过滤 | HTTP 200 还须检查业务响应 |
| 消息排队 | 宿主等待审批/提问、队列与模型错误 | 普通消息不能代替交互答案 |
| 模型列表空 | 旧目录端点兼容问题 | 当前限制见 [USAGE](USAGE.md#模型选择) |

### 首页与 API 分开检查

```powershell
$baseUrl = 'https://<机器名>.<tailnet>.ts.net'
curl.exe --connect-timeout 5 --max-time 15 -sS -o NUL -w '%{http_code}' "$baseUrl/"
'{"type":"client-request","rpcId":"connection-check","method":"session/list","payload":{"args":{"_request":{}}}}' |
    curl.exe --connect-timeout 5 --max-time 15 -sS -H 'Content-Type: application/json' --data-binary '@-' "$baseUrl/api/session/list"
```

第二条针对现代协议，未附凭证时只用于检查裸地址认证；返回 401 可能是原生认证的预期行为。若使用令牌登录，应在 App 完成认证后验证会话，不要在公开命令里粘贴凭证。API 返回 200 后还要读取响应信封，确认业务成功；WebSocket 订阅仍须另外检查。

现有 `tools/check-tailscale-link.ps1` 含有把 Serve 恢复到固定 3081 的旧建议，与本项目生产/测试端口约定不一致。本轮未改该脚本，不能把其建议直接用于生产，也不能用其检查通过替代完整验收。

## 8. 升级、换址与恢复

- 升级宿主后，重新验证首页、API、工作区流、交互与思考流；旧版本的一次成功不能证明新版本兼容。
- 更换 tailnet 或 HTTPS 域名后，核对实际域名、受信 Host、Serve 目标以及 App 地址；原凭证可能不再适用。
- 插件缺失时先确认目标 profile、安装包和数据目录。安装会修改该 profile，不要把测试安装操作作用于生产目录。
- 恢复前以部署配置和可恢复备份为依据。不要根据文档示例自动切换端口、重启服务、替换启动脚本或删除凭证。

当前项目不提供跨设备的统一开机启动配置。应沿用所在设备已经验证的管理方式，不使用旧文档中的个人路径、旧任务名或某次运行状态作为通用步骤。
