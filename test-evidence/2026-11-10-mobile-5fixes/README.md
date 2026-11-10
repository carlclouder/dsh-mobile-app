# 2026-11-10 手机端 5 条反馈修复验收证据

设备：Android 模拟器 `emulator-5554`（android-35 x86_64，DSH-Mobile debug 0.1.213 / versionCode 214）
服务：本机 dsh web 3080，经 auth-gateway 免令牌入口 `http://10.0.2.2:3081`

## 逐条对应

| 用户反馈 | 修复 | 证据 |
|---|---|---|
| ① 大量已归档会话突然重现 | 归档集合落 `dsh_event_stream` prefs，冷启动先注入、流就绪后覆盖 | 持久化内容（30 条，与宿主 `workspace/follow` baseline 一致）见下方命令；App 列表不再出现这 30 条 |
| ② 长会话插话不显示、误认为没插入 | 排队状态改读 `inbox` 投影；插话后项保留并标「已插话 · 待注入」 | `s16.png`（排队态）→ `s17.png`（已插话态）；`steer-queue.gif` 为两态切换 |
| ③ 新建会话返回列表消失 | 本机新建记录持久化 + 列表按最近活动排序 | `s26.png`：新建后返回，列表顶部「AI任务 · 刚刚」；`s27.png`：杀进程重启后仍在 |
| ④ 模型按钮无模型信息 | 目录改走 `session/modelCatalog`，选择读 `modelSelection` 投影，新会话用默认项兜底 | `s10.png`：DeepSeek / DeepSeek Account 分组、思考等级；新会话顶栏显示 `official/deepseek-f` |
| ⑤ 插话后不显示插入列表 | 同 ②，排队区可见 | 同 `s16.png` / `s17.png` |

## 归档集合落盘核对

```
adb shell run-as dev.dshmobile cat /data/data/dev.dshmobile/shared_prefs/dsh_event_stream.xml
# archived_session_ids 共 30 条（换行分隔）
```

宿主侧 baseline 实测：`workspace/follow` → `items=6 archived=30`。

## 单测与构建

- `testDebugUnitTest`：15 suites / 222 tests / 0 failures / 0 errors / 0 skipped
- `assembleDebug`：`DSH-Mobile-debug-v0.1.213.apk`

## 未覆盖

- 真机（非模拟器）未验收：通知、震动、息屏、语音入口均未实测。
- 归档会话在**流中断期间**的隐藏依赖本地缓存，若用户在另一设备新归档，本机需下一次 baseline 才同步。
- 服务端一次性令牌下的直连（不带 auth-gateway）仍会 401，属宿主认证机制，不在本次客户端修复范围。
