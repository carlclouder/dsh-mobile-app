# DSH 认证网关：源码合同与部署边界

> 对齐日期：2026-10-21。本文描述仓库中 `dsh-auth-gateway` 的实际源码，不把旧宿主上的验证结果作为当前部署已生效的证明。源码包说明见 [tools/dsh-auth-gateway/README.md](../tools/dsh-auth-gateway/README.md)。

## 1. 作用与适用范围

网关运行在 DSH 进程内，由 Cordis 加载，用于让受信远程入口访问需要认证的 DSH。它不在手机内工作，不是独立后台程序，也不管理 Tailscale Serve 或桌面启动脚本。

App 自身支持有效带令牌链接换取 Cookie，网关并非所有部署的必选项。裸地址能否使用，取决于已部署认证链路及当前宿主兼容性，不能仅凭安装了插件判断。

## 2. 两条实现路径

### 旧认证钩子

`allowTailnetForwarded=true` 时，插件包装 `connection.requestRejection` 和 `connection.authorizeIndex`。请求同时满足以下条件时，尝试放行：

1. TCP 来源为回环地址。
2. 请求 Host 与宿主 `trustedHosts` 中的主机名匹配；源码在比较前转为小写并去除端口。

这依赖宿主内部认证接口。DSH 新版本可能采用不同 API 认证入口，出现首页能打开、API 和推送仍返回 401 的情况。因此，旧文档“钩子已放行，所以 Serve 直连本体必然可用”的结论不再作为通用部署依据。

`allowTailnetForwarded=false` 表示该次加载不再安装钩子，本地反向代理仍会按配置监听。源码未提供钩子还原回调；恢复原生认证时需在配置或卸载后重启宿主，并重新验证。

### Cookie 反向代理

插件在回环地址启动 HTTP 服务，使用 `connection.authenticatedUrl()` 取得进程内认证地址，再向上游换取并缓存 Cookie。转发请求时：

- Host 改为上游地址，去除外部 Origin、Referer，覆盖为网关持有的 Cookie。
- HTTP 请求转发给配置的上游，代码包含一次上游 401 后刷新凭证的重试分支。
- WebSocket `upgrade` 单独转发，用于工作区、交互和思考等实时订阅。
- 监听随 DSH 进程运行；插件释放时关闭监听，不创建独立进程或计划任务。

这条路径同样依赖宿主认证地址、Cookie 与升级接口，部署后必须分别验证 HTTP 业务请求和 WebSocket。监听成功日志只能证明网关已绑定端口。

## 3. 配置与端口

| 配置项 | 源码默认值 | 含义 |
| --- | --- | --- |
| `allowTailnetForwarded` | `true` | 是否启用旧认证钩子 |
| `port` | `3081` | 网关回环监听端口 |
| `upstreamHost` | `127.0.0.1` | DSH 上游主机 |
| `upstreamPort` | `3080` | DSH 上游端口 |
| `cookieRefreshMs` | `21600000` | Cookie 主动刷新间隔，单位毫秒 |

**本项目的 3081 是隔离测试端口，用于避免影响生产。** `port=3081` 是现存源码默认值，不能不经配置直接用于生产。生产 DSH 沿用已有部署，通常在 3080；如生产需要反向代理，应单独指定未冲突的网关监听端口及准确上游。

Serve 的目标由部署配置决定。直连本体、经过反向代理是不同认证链路，3080 与 3081 不能通用互换。不能用“该端口有程序监听”作为切换依据，也不能在排障时把生产流量导入测试实例。

配置覆盖位置为目标 DSH profile 的 `cordis.patch.yml`，使用插件条目 id `auth-gateway`。覆盖前确认 profile、数据目录、完整配置对象和端口归属；本文不提供会自动修改运行环境的默认配置。

## 4. 安装与恢复范围

仓库提供以下文件：

| 文件 | 用途 |
| --- | --- |
| [lib/index.mjs](../tools/dsh-auth-gateway/lib/index.mjs) | 插件入口和实现 |
| [package.json](../tools/dsh-auth-gateway/package.json) | 包版本、导出及 DSH bundle 声明 |
| [cordis.patch.yml](../tools/dsh-auth-gateway/cordis.patch.yml) | 加载插件的 bundle patch |
| [install-auth-gateway.ps1](../tools/install-auth-gateway.ps1) | 安装/修复脚本，作用于指定 DSH 用户目录 |

当前源码包版本以 `package.json` 为准。建议使用 [1.1.2 安装包](../tools/dsh-auth-gateway/dsh-auth-gateway-1.1.2.tgz) 并核对 [SHA-256](../tools/dsh-auth-gateway/SHA256SUMS)；该版本只更新随包说明，插件实现与配置和 1.1.1 相同。历史包不能视为当前源码的构建结果。

安装前先确认 `DSH_HOME` 和目标 profile，在隔离环境验证兼容性。已确认目标后，可使用 DSH 插件命令安装明确的 tgz：

```powershell
dsh plugin --profile web add <插件包绝对路径>
```

这会修改当前 DSH 用户目录下的 `web` profile，不是只读操作。已有安装管理方式应使用其确认过的路径，不把本机个人目录写成通用命令。

`tools/install-auth-gateway.ps1` 支持 `-DshHome`、`-DshCommand`、`-PluginStore`。它选择可用版本包、安装或修复 profile，并会移除该清单中冲突的 `dsh-file-upload` 登记；不能描述为只复制一个文件或无副作用检查。脚本不会自动替你选择合适的生产网关端口。

插件缺失时核对包、登记和安装目录，再执行明确的安装恢复。不能仅凭 `install` 输出“已是最新”就认定缺失文件已恢复。是否需要重启或可热加载，以目标宿主实际行为为准。

## 5. 验证标准

每次变更宿主版本、网关配置或访问域名后，分别验证：

1. 目标 DSH 实例和网关监听属于预期数据目录与进程。
2. Serve 目标与该部署配置一致，未指向测试实例。
3. 手机访问域名的 HTTPS 与首页成功。
4. 相同入口的会话列表、历史和发送请求业务成功；HTTP 200 还须读取响应信封。
5. `remote.mux` 的工作区、交互和当前会话思考订阅可用。
6. 不应免令牌访问的路径仍按原生认证要求返回结果。

只验证首页、端口或插件日志，不构成 App 完整可用的证据。旧宿主的通过记录也不能替代当前版本验证。分层排障命令见[Tailscale 指南](../Tailscale远程访问DSH-安装使用文档.md#7-分层排障)。

现有 `check-tailscale-link.ps1` 带有固定恢复到 3081 的旧建议，与生产/测试端口约定不一致，本轮未修改脚本。不得直接按其建议切换生产 Serve。

## 6. 安全与回退

裸地址访问意味着认证信任转移到入口可达性及网关。回环监听和受信 Host 不能代替 tailnet 访问规则；应核对实际能访问该入口的设备范围。令牌与 Cookie 不写入公开文档、截图或日志。

回退需按实际使用的路径处理：关闭 `allowTailnetForwarded` 不会关闭 Cookie 反代，旧钩子的恢复还需重启宿主；卸载插件会撤掉其反向代理监听，指向该监听的 Serve 将失去上游。回退前准备好替代认证路径、恢复配置和业务验证，不能只关一个开关便宣称恢复完成。

本轮只对齐文档，没有安装插件、修改配置、切换 Serve 或重启服务。
