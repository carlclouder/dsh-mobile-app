package dev.dshmobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import dev.dshmobile.data.AppearancePreference
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshmobile.DshRepository
import dev.dshmobile.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * 设置页（设计 §5.6）：服务器地址（可改+测试连接）/ 三个通知开关 /
 * 通知权限状态行 / 电池优化引导 / 版本信息。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toggles by DshRepository.settingsStore.togglesFlow.collectAsState(
        initial = dev.dshmobile.data.NotificationToggles(),
    )
    var addressInput by remember { mutableStateOf("") }
    var addressLoaded by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }

    // 已存地址回填（一次性）
    LaunchedEffect(Unit) {
        addressInput = DshRepository.settingsStore.baseUrlOnce()
        addressLoaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        val skin = LocalDshSkin.current
        Column(
            Modifier.fillMaxWidth()
                .skinPanel(if (skin.dark) SkinSurface.HERO else SkinSurface.TOP, RoundedCornerShape(0.dp))
                .padding(horizontal = 18.dp, vertical = if (skin.dark) 15.dp else 11.dp),
        ) {
            if (skin.dark) {
                Text("DSH MOBILE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("设置", style = if (skin.dark) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        }

        SettingsSection("服务器", SkinSurface.FEATURE) {
            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = { Text("服务器地址") },
                placeholder = { Text(SettingsStore.DEFAULT_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = addressLoaded,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            // 换址统一入口：存储 + 客户端（关闭存量流，重连回路收拢新址）
                            DshRepository.applyBaseUrl(addressInput)
                            probeResult = null
                        }
                    },
                ) { Text("保存") }
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            probing = true
                            probeResult = null
                            // 先按输入框地址探测（未保存也能试）：临时客户端实例
                            val candidate = addressInput.trim().ifBlank { SettingsStore.DEFAULT_BASE_URL }
                            val ok = dev.dshmobile.network.DshApiClient(candidate).probeConnectivity()
                            probeResult = if (ok) "✅ 连接成功" else "❌ 连不上——请检查手机 Tailscale VPN 是否在线、PC 上 dsh web 是否在跑"
                            probing = false
                        }
                    },
                    enabled = !probing,
                ) { Text("测试连接") }
                if (probing) {
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            probeResult?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, fontSize = 13.sp)
            }
        }

        SettingsSection("通知") {
            ToggleRow(
                label = "回合完成通知",
                checked = toggles.turnComplete,
                onChecked = { scope.launch { DshRepository.settingsStore.setToggleTurnComplete(it) } },
            )
            ToggleRow(
                label = "审批请求通知",
                checked = toggles.approval,
                onChecked = { scope.launch { DshRepository.settingsStore.setToggleApproval(it) } },
            )
            ToggleRow(
                label = "Agent 提问通知",
                checked = toggles.question,
                onChecked = { scope.launch { DshRepository.settingsStore.setToggleQuestion(it) } },
            )

            // 通知权限状态行（Android 13+ 未授权时给"去开启"跳转，§5.6 P1）
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "系统通知权限未开启（收不到任何提醒）",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("去开启") }
                }
            }
        }

        SettingsSection("电池优化") {
            Text(
                "为保证息屏也能收到通知，建议把本应用设为不受电池优化限制",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text("去系统设置") }
        }

        SettingsSection("外观") {
            val themeKey by DshRepository.settingsStore.themeKeyFlow.collectAsState(
                initial = AppearancePreference.DEFAULT,
            )
            Column(Modifier.selectableGroup()) {
                ThemeOption("极光舱", AppearancePreference.AURORA, themeKey) {
                    scope.launch { DshRepository.settingsStore.setThemeKey(it) }
                }
                ThemeOption("冰蓝光幕", AppearancePreference.ICE, themeKey) {
                    scope.launch { DshRepository.settingsStore.setThemeKey(it) }
                }
            }
        }

        SettingsSection("关于") {
            // 关键信息区：仅通用信息。绝对禁止出现任何个人信息/路径/私人服务器地址
            //（本应用可能公开发布/开源，私人域名与盘符路径一律不得写入 UI 或源码常量）。
            // SelectionContainer：关于文字可长按选中复制（用户反馈）。
            val connectionState by DshRepository.connectionState.collectAsState()
            val connectionText = when (connectionState) {
                DshRepository.ConnectionState.CONNECTED -> "✅ 已连接"
                DshRepository.ConnectionState.RECONNECTING -> "🔄 重连中"
                DshRepository.ConnectionState.UNREACHABLE -> "❌ 不可达（查网络与服务器）"
                DshRepository.ConnectionState.UNKNOWN -> "⏳ 未探测"
            }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column {
                    AboutLine("DSH Mobile v${dev.dshmobile.BuildConfig.VERSION_NAME}（构建 ${dev.dshmobile.BuildConfig.VERSION_CODE} · ${if (dev.dshmobile.BuildConfig.DEBUG) "debug" else "release"}）")
                    AboutLine("远程访问 PC 端 DeepSeek Harness（DSH）的安卓客户端，经加密隧道直连自己的电脑，不暴露公网。")
                    AboutLine("连接状态：$connectionText（服务器地址见上方设置项）")
                    AboutLine("核心功能：会话列表三态分组（跑动中/等你输入/空闲）· 流式对话与插话 · 模型/思考等级切换 · 审批与提问应答 · 息屏通知与通知栏直达操作 · 排队消息管理。")
                    AboutLine("技术栈：Kotlin + Jetpack Compose（Material3）· OkHttp（REST + WebSocket 双通道）· kotlinx-serialization · DataStore。")
                    AboutLine("连不上排查：① 网络与 VPN 在线 → ② PC 端 DSH 服务已启动 → ③ 本页改地址后点「测试连接」。")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    label: String,
    surface: SkinSurface = SkinSurface.CARD,
    content: @Composable () -> Unit,
) {
    val skin = LocalDshSkin.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        val useRack = !skin.dark && maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.2f
        val contentCard: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.skinPanel(surface).padding(horizontal = 11.dp, vertical = 8.dp)) {
                content()
            }
        }
        if (useRack) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    label,
                    Modifier.width(66.dp).padding(top = 11.dp, start = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                contentCard(Modifier.weight(1f))
            }
        } else {
            Column {
                Text(
                    label,
                    Modifier.padding(start = 2.dp, top = 3.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                contentCard(Modifier.fillMaxWidth())
            }
        }
    }
}

/** 关于区信息行：小字灰，行距紧凑，长文案自动换行。 */
@Composable
private fun AboutLine(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ThemeOption(label: String, key: String, current: String, onSelect: (String) -> Unit) {
    val selected = current == key
    val base = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    val treatment = if (selected) base.skinPanel(SkinSurface.RAIL, MaterialTheme.shapes.small) else base
    Row(
        modifier = treatment.selectable(
            selected = selected, role = Role.RadioButton, onClick = { onSelect(key) },
        ).heightIn(min = 48.dp).padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
