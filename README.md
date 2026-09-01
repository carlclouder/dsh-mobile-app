# DSH Mobile —— 手机远程访问 DSH 完整指南

DSH Mobile 是为 [DeepSeek Harness](https://github.com)（DSH）打造的**安卓远程客户端**：你在家里电脑上运行 DSH（AI 对话工作台），用一部手机，无论用 4G/5G 还是公司/酒店 WiFi，都能加密接入——看会话列表、息屏收通知、免开 App 直接批审批、进会话对话。

**本指南面向零基础用户，从"电脑怎么配"到"手机怎么装、怎么用"全流程讲清。** 整套方案用 **Tailscale 加密组网**，不暴露公网，安全可靠。

> 如果你是开发者想重新构建/测试 App，工程细节见文末「构建与测试」。

---

## 一、这套方案长什么样

外网手机 ↔ 家里电脑 DSH 之间是一条加密通道：

```
手机（4G/5G/任意WiFi）
   │  Tailscale 加密隧道（WireGuard，私有网络）
   ▼
家里电脑 Tailscale 网卡
   │  Tailscale Serve 反向代理（自动 HTTPS 证书）
   ▼
DSH 工作台（运行在 127.0.0.1:3080）
```

| 项目 | 值 |
|------|-----|
| 手机访问地址 | **`https://<PC-name>.tailnet.ts.net`** |
| Tailscale 网络 | `<tailnet>.ts.net` |
| 统一账号 | `<your-tailscale-account>`（手机、电脑都用这个） |
| 电脑 Tailscale IP | `<PC-Tailscale-IP>` |
| 手机 Tailscale IP | `<Phone-Tailscale-IP>` |
| DSH 实例 | 电脑上 `127.0.0.1:3080`（本地与手机共用同一实例） |

**安全说明**：只有登录了同一 Tailscale 账号（`<your-tailscale-account>`）的设备才能访问；全程 WireGuard 加密 + HTTPS 证书；DSH 只监听本机回环地址，不暴露公网/局域网。

---

## 二、主机端：安装配置（一次做好，以后全自动）

> 这部分是在 **家里电脑** 上做的，做好后电脑开机登录即自动就绪，手机随时可连。

### 1. 安装并登录 Tailscale

1. 到 [Tailscale 官网](https://tailscale.com/download) 下载 Windows 客户端并安装（或 `winget install tailscale`）。
2. 启动 Tailscale，登录账号选 **邮箱** 方式，输入 `<your-tailscale-account>`。
   - ⚠️ **不要**用 Google / Apple / 微软快捷登录（会创建不同的独立网络，手机连不上电脑）。
3. 登录后，电脑右下角状态栏会出现 Tailscale 图标，表示已加入 `<tailnet>.ts.net` 网络。

### 2. 用 Tailscale Serve 发布 DSH

DSH 只在电脑本机的 `127.0.0.1:3080` 监听。要让手机能访问，用 Tailscale 内置的反向代理把它转成一个 HTTPS 域名：

```powershell
tailscale serve --bg --https=443 http://127.0.0.1:3080
```

执行后确认发布成功：

```powershell
tailscale serve status
# 期望输出： https://<PC-name>.tailnet.ts.net (tailnet only)
#            |-- / proxy http://127.0.0.1:3080
```

> 说明：`<PC-name>.<tailnet>.ts.net` 这个域名是 Tailscale 自动生成的（电脑设备名 + 网络名），证书自动签发，只在 tailnet 内可达，公网不可见。

**以后要临时关闭/重开远程访问**：
```powershell
tailscale serve --https=443 off                # 关闭（手机立即无法访问）
tailscale serve --bg --https=443 http://127.0.0.1:3080   # 重新开启
```

### 3. 让 DSH 开机自启

DSH（`dsh web`）也要能开机自启，否则电脑重启后手机就连不上。已配置好的方式是**启动文件夹脚本**（每次登录自动拉起）：

- 路径：`C:\Users\<user>\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\DSH-WebUI-AutoStart.bat`
- 脚本会自动：检查 Tailscale 服务（没运行就启动）→ 检查 Tailscale Serve 转发（丢了就恢复）→ 检查 3080 端口（没有服务就拉起 `dsh web`）。

如果电脑上还没这个脚本，你可以手动启动 DSH：
```powershell
# 在 DSH 安装目录（或你的项目目录）执行
dsh web
# 浏览器打开 http://127.0.0.1:3080 确认能进 DSH 界面
```

> 电脑端也准备了桌面脚本方便手动管理：`启动DSH-WebUI.bat` / `停止DSH-WebUI.bat` / `重启DSH-WebUI.bat`（如有）。

### 4. 验证主机端就绪

在电脑浏览器打开 `http://127.0.0.1:3080`，应能进入 DSH Web 界面。这一步也就是「主机端装好了」。

> **电脑开机登录后，手机直接访问即可，无需再手动操作。** 若防火墙提示，允许 Tailscale 通过即可。

---

## 三、手机端：安装 DSH Mobile App

> 建议直接装 **DSH Mobile 原生 App**（体验最好：息屏通知、免开 App 批审批、三态列表、流式对话）。浏览器方案 `https://<PC-name>.tailnet.ts.net` 仍可用作备用。

### 1. 获取 APK

最新构建产物在**电脑上**这个固定路径（每次打包都会自动更新到这里）：

```
<repo>\app\build\outputs\apk\release\DSH-Mobile-release-v<版本>.apk
```

取出这个 APK，传到手机（任选其一）：
- **微信 / QQ 文件传输助手**：发给自己，手机端保存到下载目录；
- **USB 数据线**：手机连电脑，把 APK 拷到手机存储；
- **局域网共享 / 网盘**。

> 手机只需 **Android 8.0 及以上**。

### 2. 安装 APK

手机文件管理器找到这个 APK → 点击安装。若提示「允许安装未知来源应用」，按提示允许即可。

### 3. 安装 Tailscale App（重要）

App 连电脑走的是 Tailscale 隧道，所以**手机上也要装 Tailscale**：

1. 在应用商店 / [Tailscale 官网](https://tailscale.com/download) 下载 **Tailscale** 安卓版并安装；
2. 打开 → 登录 → **邮箱**方式 → 输入 `<your-tailscale-account>`；
3. 顶部开关打开 → 系统弹「VPN 连接请求」→ 允许；
4. 状态栏出现**钥匙图标** = Tailscale 已连接。

> ⚠️ Tailscale 必须保持连接（钥匙图标在），否则 App 连不上电脑。

---

## 四、手机端：首次使用（3 件事，别漏）

1. **允许通知**：打开 DSH Mobile，系统弹「允许通知」→ **点允许**（否则收不到任何回合/审批/提问提醒）。
2. **确认已连接**：首页顶部状态条显示 **「已连接」** = 成功；显示 **「不可达」** = Tailscale 没开或电脑不在线（排查见「六、常见问题」）。
3. **关闭电池优化限制**：App 设置页 →「电池优化 → 去系统设置」→ 把 DSH Mobile 设为**不受限**。这一步很关键——安卓省电机制会杀后台服务，不设会导致息屏收不到通知。

> 服务器地址默认就是 `https://<PC-name>.tailnet.ts.net`，一般无需改。如改了别的地方，可到「设置 → 服务器地址」恢复/修改后点「测试连接」。

---

## 五、手机端：日常使用

### 会话列表（首页）
- **三段分组**（一眼看清全局）：
  - **跑动中**（绿点）= agent 正在干活；
  - **等你输入**（琥珀点）= 这轮跑完，等你说话；
  - **空闲**（灰点）= 很久没有新提问的旧会话。
- 点任意会话 → 进入对话页；右下角 **+** → 在所选工作区新建会话（**创建后自动进入新会话**）。
- 下拉列表可手动刷新；顶部「工作区 ▾/▴」可折叠切换工作区。
- **会话操作**：每个会话行尾有 **⋮** 按钮，可：
  - **重命名**（改标题）、**分叉会话**（从当前会话分出新的子会话，并自动跳转过去）、**归档会话**（从列表隐藏）。操作后列表**即时刷新**。

### 对话页（原生）
- **流式输出**：agent 回复实时逐字出现，跟电脑上一样。
- **语音输入**：输入框旁 🎤 点一下说话识别；无识别服务的设备会提示改用键盘。
- **停止**：顶条「停止」可中断当前回合。
- **模型选择**：顶条 `◆ provider/model` → 底部弹层，模型与思考等级分开选。
- **消息体验**：长按可复制、带时间戳、Markdown 渲染（标题/表格/加粗/代码块/列表/图片）。
- **自适应折叠**：思考过程、工具参数、工具结果过长会自动折叠（点「展开 ▴」），不占屏；超长消息有「展开全文▴」。
- **跳到底部**：上翻列表时右下角出现「⬇ 跳到底部」按钮，点击一键回到最新消息并恢复自动滚动（在底部时自动隐藏）。
- **插话**：agent **运行中**时，排队消息可点 **⚡插话**，host 会在下一步边界尽快把该条注入当前轮次；agent 空闲时按钮置灰（仅运行中可插话，直接发新消息即可开始新一轮）。插话成功后该条从排队列表消失、尽快注入对话流。

### 通知（核心，息屏也能收）
| 通知 | 含义 | 你能做的事 |
|------|------|-----------|
| 回合完成，等待你的输入 | agent 跑完一轮 | 点按进对话页 |
| 审批请求：\<工具名\> | agent 请求做敏感操作 | **直接在通知上点「允许一次 / 拒绝」**，不用打开 App |
| Agent 提问 | agent 向你提问 | 点按进对话页答题 |

- 前台服务常驻（顶栏有「DSH 监听中」的常驻通知，属正常，勿清退）。
- 三类通知开关在「设置」里独立控制。

---

## 六、常见问题排查

**Q1：手机 App 显示「不可达」**
按顺序：① 手机 Tailscale 开关开了吗（状态栏要有钥匙图标）→ ② 手机能上网吗（先开浏览器试）→ ③ 家里电脑开机且 Tailscale 在跑吗 → ④ 电脑上 3080 活着吗（`netstat -ano | findstr ":3080"`，有 LISTENING = 正常）。

**Q2：收不到通知**
① 系统通知权限是否开启（设置 → 应用 → DSH Mobile → 通知）→ ② 电池优化是否把 App 杀了（去设置页加白）→ ③ Tailscale 是否在线 → ④ 三类通知开关是否被误关。

**Q3：App 显示已连接但会话列表空**
正常：列表默认隐藏空白会话和子代理会话，有真实对话的会话才会出现。下拉刷新即可。

**Q4：锁屏后连不上**
安卓省电机制会挂起后台 VPN，**解锁屏幕后重试**即可；可在系统设置里给 Tailscale App 开「无限制后台/电池不优化」。

**Q5：换新手机**
新手机装 Tailscale App → 登录 `<your-tailscale-account>` → 再装 DSH Mobile App，即可连同一个地址。

**Q6：装了 App 仍提示「不可达」，但浏览器能打开 `https://<PC-name>.tailnet.ts.net` 吗？**
若浏览器能开而 App 不能，通常是 App 的服务器地址被改过——到「设置 → 服务器地址」确认是 `https://<PC-name>.tailnet.ts.net`，点「测试连接」。

---

## 七、构建与测试（开发者）

### 前置工具
JDK 17、Android SDK（`platform-tools` + `android-35` + `build-tools;35.0.0`）、Gradle 8.9。可用 `tools/setup_build_env.ps1` 一键装到 `.toolchain/`。

> ⚠️ **必须从 ASCII 路径构建**：项目真实路径含中文（`<repo>\`），AGP 会拒绝中文路径。用 ASCII 联接目录 **`D:\dshm`**（指向本项目），并设 `android.overridePathCheck=true`（gradle.properties 已配置）、不写 `local.properties`。

### 构建命令
```powershell
$env:JAVA_HOME="D:\dshm\.toolchain\jdk17"
$env:ANDROID_HOME="D:\dshm\.toolchain\android-sdk"
& "D:\dshm\.toolchain\gradle-8.9\bin\gradle.bat" -p "D:\dshm" --no-daemon assembleRelease
```

- `assembleRelease`：发布版（R8 混淆 + 正式签名）。**日常交付只装这个。**
- `assembleDebug`：调试版（仅模拟器 UI 验证用，app 里连 `10.0.2.2`）。
- `testDebugUnitTest`：单测（当前 62/62 绿）。

### 产物（输出到项目下的标准路径）
```
<repo>\app\build\outputs\apk\release\DSH-Mobile-release-v<版本>.apk
```

> 版本号每次 assemble 自动 +1（`app/version.properties`，git 忽略）。你只需装 `release` 目录下的 `DSH-Mobile-release-v<版本>.apk`，**不要**装 debug 版（那是给模拟器验证用的）。

---

## 八、技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.0.20 |
| UI | Jetpack Compose（BOM 2024.09.03，Material3） |
| 网络 | OkHttp 4.12.0（REST + WebSocket 双通道） |
| 序列化 | kotlinx-serialization-json 1.7.3 |
| 存储 | DataStore Preferences 1.1.1 |
| Markdown 渲染 | jeziellago/compose-markdown 0.7.2（表格/图片/代码块） |
| 构建 | Gradle 8.9 / AGP 8.6.1 |
| SDK | minSdk 26 / targetSdk 35 / compileSdk 35 |
| 单测 | Kotlin test + MockWebServer |

---

## 说明

- **单机单用户设计**（无配对/多账户）；模型管理、会话配置以 PC 端 DSH 为权威源。
- `app/version.properties`、`keystore/`（release 签名与密码）均在 `.gitignore` 内，不随仓库泄露。
- 详细使用步骤另见 [USAGE.md](USAGE.md)；技术设计见 [docs/DESIGN.md](docs/DESIGN.md)；进度台账见 [PROGRESS.md](PROGRESS.md)。
