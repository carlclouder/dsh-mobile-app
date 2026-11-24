# dsh-auth-gateway

DSH 进程内认证网关。它加载在宿主的 `connection` 服务上，保留旧认证钩子和 Cookie 反向代理两条路径，帮助已配置的受信入口访问 DSH。

本文描述当前源码；不声明某台机器已部署或某个宿主版本必然兼容。完整说明见[认证网关文档](../../docs/dsh-auth-gateway-plugin.md)。

## 源码与加载

| 文件 | 作用 |
| --- | --- |
| [package.json](package.json) | 包版本、入口和 DSH bundle 导出 |
| [lib/index.mjs](lib/index.mjs) | 配置、认证处理、HTTP 与 WebSocket 转发 |
| [cordis.patch.yml](cordis.patch.yml) | 将 id 为 `auth-gateway` 的插件加入 profile |

插件由 DSH 管理生命周期，不创建独立常驻进程或计划任务。它不管理 Serve、桌面启动脚本或其他服务。1.1.2 只更新随包说明，不改插件实现或配置。请使用 1.1.2 或更新的已审核包；历史安装包不要当作当前源码的产物。

## 实际行为

- `allowTailnetForwarded` 开启时，包装旧 `requestRejection`、`authorizeIndex`，按回环来源和受信 Host 尝试放行。该方式依赖内部接口，新宿主可能首页成功而 API 仍 401，不能作为通用免认证保证。
- 反向代理始终按配置监听回环地址，通过宿主的 `authenticatedUrl()` 换取 Cookie，改写上游 Host，去除 Origin、Referer，再转发 HTTP 请求和 WebSocket 升级。
- Cookie 缓存支持并发换取合并、定时刷新，以及 HTTP 上游 401 后的一次刷新重试分支。最终业务结果仍须检查响应，不能只看刷新日志。
- 插件释放时关闭监听。关闭旧钩子开关不会关闭反向代理端口；源码没有还原旧钩子的释放回调，恢复原生认证需重启宿主并验证。

## 配置

| 字段 | 源码默认值 |
| --- | --- |
| `port` | `3081` |
| `upstreamHost` | `127.0.0.1` |
| `upstreamPort` | `3080` |
| `cookieRefreshMs` | `21600000` 毫秒 |
| `allowTailnetForwarded` | `true` |

**项目约定：3080 属于已有生产 DSH，3081 用于隔离测试。** 默认网关监听 3081 是源码缺省值，不是生产推荐配置。生产使用前必须单独明确网关端口、上游和 profile，不能不经配置直接占用测试端口。

Serve 只能指向该部署实际选定并完整验证的入口；本体端口与代理端口不能通用互换。端口有监听不等于认证和业务都可用。

## 安装与检查

确认目标 DSH 用户目录、`web` profile、插件包及配置后，再使用宿主插件命令：

```powershell
dsh plugin --profile web add <插件包绝对路径>
```

仓库的[安装脚本](../install-auth-gateway.ps1)支持指定 `-DshHome`、`-DshCommand`、`-PluginStore`。脚本会修改目标 profile，并清理其中冲突的第三方 `dsh-file-upload` 登记；它不是只读检查，也不替部署选择生产端口。

安装后分别检查首页、会话列表、历史、发送，以及 `remote.mux` 的工作区、交互和思考订阅。首页 200、监听成功或历史版本的“已上线”记录都不能替代当前验证。

[链路检查脚本](../check-tailscale-link.ps1)仍有固定建议恢复到测试端口 3081 的旧假设；本轮未改脚本，不应按其建议切换生产配置。

## 相关文档

- [项目说明与构建](../../README.md)
- [App 使用指南](../../USAGE.md)
- [Tailscale 部署与分层排障](../../Tailscale远程访问DSH-安装使用文档.md)
- [当前架构](../../docs/ARCHITECTURE.md)
