# 手机远程访问家里电脑 DSH — 安装使用文档

> 生成日期：2026-08-23　　更新：2026-08-24
> 方案：Tailscale 加密组网 + Tailscale Serve 反向代理发布 DSH WebUI
> 状态：✅ 已全部配置完成并验证通过（电脑端）
> 更新说明：原 3081 远程专用实例已合并为 3080 单实例，对应计划任务 `DSH-WebUI-Remote-3081` 已删除，Tailscale Serve 已改指 3080。
> 2026-08-24 更新：新增 **DSH Mobile 原生安卓 App**（本轮交付）——相比浏览器方案增加息屏后台通知、审批按钮免开 App、三态会话列表、移动端排版，见下文「四、DSH Mobile 原生 App」。App 在安卓模拟器完成端到端五项实测，真机安装即可用。

---

## 一、这套方案是什么

**外网手机（4G/5G/任意WiFi）↔ 家里电脑（DSH 对话界面）** 的加密远程通道。

```
外网手机
   │  Tailscale 加密隧道（WireGuard）
   ▼
家里电脑 Tailscale 网卡
   │  Tailscale Serve 反向代理（自动 HTTPS 证书）
   ▼
DSH WebUI（127.0.0.1:3080）
   │
   ▼
AI 对话 / 远程指令执行
```

**核心信息一览：**

| 项目 | 值 |
|------|-----|
| 统一账号 | `<your-tailscale-account>`（手机电脑都用这个） |
| 手机设备 | <phone-model>（安卓），IP：<Phone-Tailscale-IP> |
| 电脑设备 | <PC-name>（Windows），IP：<PC-Tailscale-IP> |
| **手机访问地址** | **`https://<PC-name>.tailnet.ts.net`** |
| Tailscale 网络 | <tailnet>.ts.net |
| DSH 实例 | 127.0.0.1:3080（本地与手机远程共用同一实例） |

**安全性说明：**
- 只有登录了 `<your-tailscale-account>` 的设备才能访问（Tailscale 私有网络，公网无法访问）
- 全程 WireGuard 加密 + HTTPS 证书（Tailscale 自动签发）
- DSH 不直接暴露到公网/局域网，只监听本机回环地址

---

## 二、手机端安装步骤（已完成，供参考）

1. **获取安装包**
   - 桌面文件：`tailscale-android-universal-1.102.3.apk`（100.54 MB，SHA256 已校验）
   - 通过微信文件传输助手 / QQ / USB 线传到手机

2. **安装**
   - 手机文件管理器找到 APK → 点击安装 → 允许"未知来源"（如有提示）
   - 需要安卓 8.0 以上系统

3. **登录**
   - 打开 Tailscale App → 登录 → **邮箱方式** → 输入 `<your-tailscale-account>`
   - 去邮箱收验证码 → 输入 → 完成
   - ⚠️ 不要用 Google/Apple/微软快捷登录（会创建不同的独立网络，连不到电脑）

4. **开启 VPN**
   - App 顶部开关打开 → 系统弹出"VPN 连接请求"→ 允许
   - 状态栏出现钥匙图标 = 成功

---

## 三、手机端日常使用（核心！）

### 每次使用的固定动作

1. **打开 Tailscale App，确认开关开启**（钥匙图标在状态栏）
2. **打开 DSH Mobile App**（推荐，见「四、DSH Mobile 原生 App」）：点图标直达，会话列表三态分组一目了然，回合完成/审批/提问会弹系统通知
   - 或打开浏览器访问 `https://<PC-name>.tailnet.ts.net`（浏览器方案仍可用，作为备用）
3. 出现 DSH 对话界面 → **正常打字对话、下指令**，和电脑上完全一样

### 强烈建议：添加到主屏幕

- Chrome：菜单（⋮）→「添加到主屏幕」
- 手机自带浏览器：菜单 →「添加书签/主屏幕」
- 之后桌面会出现一个图标，**点一下直接进 DSH**，体验和 App 差不多

### 网络要求

| 手机所在网络 | 能否用 |
|------|------|
| 家里 WiFi | ✅ |
| 外面 4G/5G | ✅ |
| 公司/酒店 WiFi | ✅（个别网络封 VPN 端口时，Tailscale 会自动走中继） |

---

## 四、DSH Mobile 原生 App（推荐）

**是什么**：为这套远程访问专门开发的原生安卓客户端（包名 `dev.dshmobile`，v0.1.0），
连接地址默认就是 `https://<PC-name>.tailnet.ts.net`，与浏览器方案同一条 Tailscale 通道。

**比浏览器多了什么**：

| 能力 | 说明 |
|------|------|
| 息屏后台通知 | 回合完成/需要审批/Agent 提问弹系统通知（前台服务保活，整夜挂机可达） |
| 审批按钮免开 App | 通知上直接点「允许一次 / 拒绝」；在别处已处理的审批自动撤通知不误报 |
| 三态会话列表 | 跑动中（绿）/ 等你输入（琥珀）/ 空闲（灰）分组，一眼看清全局 |
| 移动端排版 | 原生列表 + 对话页 WebView 注入移动端样式（单手可读可输入） |
| 地址可改 | 设置页可改服务器地址 + 「测试连接」一键自检 |

**安装**：

1. 把 APK 传到手机（微信/QQ 文件传输助手或 USB 线）：
   `<repo>\app\build\outputs\apk\debug\app-debug.apk`（约 10 MB）
2. 手机文件管理器点击安装（允许"未知来源"，如提示）
3. 或 USB 连电脑后一条命令：
   ```powershell
   & "D:\dshm\.toolchain\android-sdk\platform-tools\adb.exe" install -r "<repo>\app\build\outputs\apk\debug\app-debug.apk"
   ```

**首次使用（3 件事）**：

1. 启动时系统弹「允许通知」→ **点允许**（否则收不到任何提醒）
2. 首页状态条显示「已连接」= 成功；「不可达」= Tailscale 没开或电脑不在线（检查方法见下文 Q1）
3. App 设置页 → 电池优化 → 去系统设置 → 设为不受限（息屏保活关键）

**验证情况**：已在安卓模拟器（Android 15 / API 35）完成端到端实测——
连接 ✅ 三态列表 ✅ 回合完成通知 ✅ 审批按钮应答 ✅ 对话页 WebView ✅。
测试细节与排障记录见项目台账 `<repo>\PROGRESS.md`。

> ⚠️ 说明：App 与浏览器共用同一台电脑上的 DSH 实例，可以同时用；电脑端 3080 实例重启后
> App 会自动重连（前台服务 START_STICKY + 断线退避重连）。

---

## 五、电脑端状态说明

### 当前已配置的内容

| 组件 | 说明 |
|------|------|
| Tailscale 主程序 | 已登录 <your-tailscale-account>，开机自启 |
| Tailscale Serve | 已启用，把 127.0.0.1:3080 发布为 HTTPS 域名 |
| DSH 实例 | 127.0.0.1:3080（本地与远程共用，登录自启，见下文） |

### ✅ 开机自启已全部配置

| 组件 | 自启方式 | 验证状态 |
|------|---------|---------|
| Tailscale 主程序 | Windows 服务（Automatic） | ✅ |
| Tailscale Serve | 配置持久化在节点状态，重启自动恢复 | ✅ |
| DSH 实例 3080 | **启动文件夹脚本 `DSH-WebUI-AutoStart.bat`（登录时自启）** | ✅ |

自启脚本位置：
`C:\Users\<user>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\DSH-WebUI-AutoStart.bat`

脚本每次登录时自动做四件事：
1. 检查 Tailscale 服务，没运行就启动；
2. 检查 Tailscale Serve 是否转发到 3080，丢了就自动恢复（`serve --bg --https=443 → 127.0.0.1:3080`）；
3. 检查 Tailscale 登录账号是否为 `<your-tailscale-account>`，不符则打警告；
4. 检查 3080 端口，没有服务就后台静默拉起 `dsh web`。

**手动管理（不用记命令，桌面三个脚本）：**
- 启动：双击桌面 `启动DSH-WebUI.bat`（同样自带 Tailscale 自检 + 就绪等待 + 自动开浏览器）
- 停止：双击桌面 `停止DSH-WebUI.bat`
- 重启：双击桌面 `重启DSH-WebUI.bat`

> 历史：曾部署过 3081 远程专用实例 + 计划任务 `DSH-WebUI-Remote-3081`，2026-08-23 已合并为 3080 单实例，计划任务已删除。

也就是说：**电脑开机登录后，手机直接访问 `https://<PC-name>.tailnet.ts.net` 即可，无需任何手动操作。**

---

## 六、常见问题排查

### Q1：手机打不开 `https://<PC-name>.tailnet.ts.net`

按顺序检查：

1. **Tailscale App 开关开了吗？** 状态栏要有钥匙图标
2. **手机能上网吗？** 先用浏览器随便开个网页测试
3. **电脑在线吗？** 家里电脑要开机且 Tailscale 在运行
4. **DSH 3080 实例活着吗？** 电脑上执行：
   ```powershell
   netstat -ano | findstr ":3080"
   ```
   有 LISTENING = 正常；没有 = 双击桌面 `启动DSH-WebUI.bat` 手动拉起

### Q2：手机 App 显示"已登录"但连不上

- 确认登录账号是 `<your-tailscale-account>`（App 点头像能看到）
- App 里下拉刷新设备列表，应能看到 `<PC-name>`
- 关掉开关再打开（重新连接）

### Q3：安卓手机锁屏后连不上

- 安卓省电机制会挂起后台 VPN，**解锁屏幕后重试**即可
- 可在手机设置里给 Tailscale App 开"无限制后台/电池不优化"

### Q4：换了新手机怎么迁移

1. 新手机装 Tailscale App → 登录 `<your-tailscale-account>`
2. 完成，直接访问同一个网址

### Q5：想关闭远程访问

```powershell
# 电脑上关闭发布（手机立即无法访问）
tailscale serve --https=443 off
# 重新开启
tailscale serve --bg --https=443 http://127.0.0.1:3080
```

### Q6：手机 ping 不通电脑/电脑 ping 不通手机

- 安卓默认屏蔽 ping，**不代表网络不通**
- 以浏览器实际访问 `https://<PC-name>.tailnet.ts.net` 的结果为准

### Q7：装了 DSH Mobile App 收不到通知

按顺序检查：

1. **系统通知权限**：设置 → 应用 → DSH Mobile → 通知 → 允许（App 首启也会申请，别点拒绝）
2. **电池优化**：App 设置页 → 电池优化 → 去系统设置 → 设为不受限（安卓省电会杀后台服务）
3. **Tailscale 在线**：App 首页状态条「不可达」= VPN 断，先开钥匙图标
4. **通知开关**：App 设置页三类通知开关默认全开，检查是否被误关
5. 都不行 → App 设置页「测试连接」自检，失败看 Q1 的电脑侧检查

### Q8：App 显示「已连接」但会话列表是空的

- 正常现象：列表默认隐藏空白会话与子代理会话（避免刷屏），有真实对话的会话才会出现
- 下拉列表即可手动刷新

---

## 七、技术细节备忘（给未来的自己）

- DSH 的 `--host` 参数只允许 `127.0.0.1` 或 `0.0.0.0`（v4 配置校验限制），绑定 Tailscale 网卡 IP 会报错；`0.0.0.0` 被 CLI 安全检查禁止。所以采用 **127.0.0.1:3080 + Tailscale Serve 反代** 的正统方案
- Tailscale Serve 是 Tailscale 内置的反向代理功能，自动签发 Let's Encrypt 证书（`*.ts.net` 域），只在 tailnet 内可达，公网不可见
- 曾试过的旧方案（第三方 `dsh-tailscale-gateway` 插件）已废弃并从 `~/.dsh/profiles/web/package.json` 移除（2026-08-23 修复了它导致的 dsh web 启动故障）
- 电脑曾有第二个 Tailscale 账号 `<your-tailscale-account>`（<old-tailnet>.ts.net），已登出，统一使用 `<your-tailscale-account>`（<tailnet>.ts.net）
- 架构演变：最初本地 3080 + 远程 3081 双实例（3081 由计划任务 `DSH-WebUI-Remote-3081` 管理），2026-08-23 晚合并为 3080 单实例——Tailscale Serve 改指 3080，自启统一走启动文件夹脚本，计划任务删除，AIGC 目录下的旧版启动脚本同日清理
- APK 下载：GitHub 直连慢（~60KB/s），`gh-proxy.com` 镜像快（~450KB/s），完整文件 100.54 MB，官方 SHA256：`ce01f538379768144d7e3f31706a8eb6038fcaae6aa3c410a83bc56113ac0763`
- DSH Mobile App 工程：`<repo>\`（Kotlin + Jetpack Compose + OkHttp，无第三方框架）；
  构建命令（需从纯 ASCII 联接路径跑，避免中文路径坑）：
  ```powershell
  $env:JAVA_HOME="D:\dshm\.toolchain\jdk17"; $env:ANDROID_HOME="D:\dshm\.toolchain\android-sdk"
  & "D:\dshm\.toolchain\gradle-8.9\bin\gradle.bat" -p "D:\dshm" --no-daemon assembleDebug
  ```
  产物：`<repo>\app\build\outputs\apk\debug\app-debug.apk`；
  App 服务器地址默认即 `https://<PC-name>.tailnet.ts.net`（设置页可改，换址自动断旧流重连新址）；
  debug 版含模拟器测试辅助（明文 HTTP + Host 改写，仅 debug 构建生效，真机走 HTTPS 不受影响）。

---

## 八、快速开始卡片（打印/截图用）

```
┌─────────────────────────────────────────┐
│  手机远程访问 DSH 快速卡                  │
├─────────────────────────────────────────┤
│  0.(推荐) 装 DSH Mobile App, 点图标直达   │
│  1. 开 Tailscale App 开关                │
│  2. 打开 App / 浏览器:                    │
│     https://<PC-name>.tailnet.ts.net    │
│  3. 正常对话下指令, 息屏等通知             │
├─────────────────────────────────────────┤
│  电脑重启后: 全自动(登录自启), 无需操作    │
└─────────────────────────────────────────┘
 │  连不上? → 检查钥匙图标/电脑开机/App账号  │
 │  没通知? → 通知权限/电池不受限/开关全开   │
 └─────────────────────────────────────────┘
```
