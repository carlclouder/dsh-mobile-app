package dev.dshmobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.dshmobile.service.EventStreamService
import dev.dshmobile.ui.SettingsScreen
import dev.dshmobile.ui.SessionListScreen

/**
 * 主入口（设计 §5.6）：POST_NOTIFICATIONS 运行时权限流程 + 启动前台监听服务 + 底部导航。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // P1：Android 13+ 通知权限（未授予则所有通知静默不显示，前台服务照跑）
        requestNotificationPermissionIfNeeded()

        // 启动前台监听服务（specialUse）
        startForegroundService(Intent(this, EventStreamService::class.java))

        // 返回列表主动对账（协作升级定稿·主修）：从会话页返回/应用回前台时重拉 session.list，
        // blank/running 与宿主立即对齐——不依赖 host/session-status 推送帧恰好到达（慢链路上
        // running=true 帧可能丢/迟，丢帧时新会话会一直隐藏，即"新建发送后返回列表不刷新"）。
        // 必须挂 Activity 生命周期：列表 Composable 在返回时不重建，LaunchedEffect(Unit) 不会重跑。
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(
                source: androidx.lifecycle.LifecycleOwner,
                event: androidx.lifecycle.Lifecycle.Event,
            ) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    DshRepository.requestManualRefresh()
                    // 未连接时踢立即重连（修复"App 先于宿主启动，退避中等恢复、需重启 App"）：
                    // 用户打开 App 的瞬间就是最该重试的时刻，不等退避计时走完
                    if (DshRepository.connectionState.value != DshRepository.ConnectionState.CONNECTED) {
                        DshRepository.requestStreamKick()
                    }
                }
            }
        })

        setContent {
            DshMobileApp()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果在 App 内以横幅呈现（SettingsScreen 权限行） */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** 底部导航壳：会话列表 / 设置。 */
@Composable
fun DshMobileApp() {
    var currentTab by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    // 主题（皮肤）：默认/护眼蓝/护眼绿
    val themeKey by DshRepository.settingsStore.themeKeyFlow.collectAsState(
        initial = dev.dshmobile.data.SettingsStore.THEME_DEFAULT,
    )

    dev.dshmobile.ui.DshTheme(themeKey) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("会话") },
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") },
                    )
                }
            },
        ) { padding ->
            androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                0 -> SessionListScreen()
                else -> SettingsScreen()
            }
        }
        // 权限被拒后的首次引导（Snackbar + 去系统设置）
        val context = LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                val result = snackbarHostState.showSnackbar(
                    message = "通知已禁用，收不到会话提醒",
                    actionLabel = "去开启",
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
        }
        }
    }
}
