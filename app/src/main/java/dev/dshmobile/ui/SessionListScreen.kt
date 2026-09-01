package dev.dshmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dshmobile.DshRepository
import dev.dshmobile.service.NotificationStateMachine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 会话列表页（设计 §5.4）：三态分组（跑动中/等你输入/空闲），
 * 隐藏 blank 与 subagent 会话，顶部连接状态条，手动刷新，FAB 新建会话。
 *
 * 分组规则（§3.3 时间语义校准）：
 * - 跑动中：running == true，绿点，显示"约 X 分钟"（本地翻转时刻近似）
 * - 等你输入：running == false 且 7 天内有提问（updatedAt 语义=最近人类 prompt 时间）
 * - 空闲：其余，灰点
 */
class SessionListViewModel : ViewModel() {

    /** 分组后的行模型。 */
    data class Row(
        val sessionId: String,
        val title: String,
        val cwd: String,
        val group: Group,
        val relativeText: String,
        val runningSinceMillis: Long?,
    )

    enum class Group { RUNNING, WAITING, IDLE }

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 当前选中的工作区 id（null=全部；需求 2.9.1 工作区过滤）。 */
    private val _selectedWorkspaceId = MutableStateFlow<String?>(null)
    val selectedWorkspaceId: StateFlow<String?> = _selectedWorkspaceId.asStateFlow()

    fun selectWorkspace(workspaceId: String?) { _selectedWorkspaceId.value = workspaceId }

    /** 状态机快照 → 分组行（过滤 blank/subagent，排序：组序 → 标题）。纯函数，可单测。
     *  updatedAt 单位 = 毫秒（服务端 Date.now() 原样透出，终审 P0-2 钉死）。
     *  需求 2.9.1：按选中工作区过滤（session.cwd == 工作区 path）。 */
    fun rowsFrom(
        snapshot: Map<String, NotificationStateMachine.SessionState>,
        nowMillis: Long,
        workspaces: List<dev.dshmobile.model.WorkspaceView>,
    ): List<Row> {
        val selected = _selectedWorkspaceId.value
        val selectedPaths = workspaces.filter { it.workspaceId == selected }.map { it.path }.toSet()
        val prompted = DshRepository.promptMarkedSessions.value
        return snapshot.entries
            .asSequence()
            // §5.4 过滤：blank 与 subagent 隐藏。两类豁免：①本机已成功发出过消息的 blank 会话
            //（本地事实，立即可见——不等宿主 turn/start 与推送帧）②running 清 blank 后自然转正。
            .filter { (!it.value.blank || it.key in prompted) && it.value.origin != "subagent" }
            .filter { row ->
                selected == null ||
                    selectedPaths.contains(row.value.cwd)   // 选中工作区时只看匹配 path 的会话
            }
            .map { (sessionId, state) ->
                val group = when {
                    state.running -> Group.RUNNING
                    state.updatedAt > 0 &&
                        (nowMillis - state.updatedAt) < SEVEN_DAYS_MILLIS -> Group.WAITING
                    else -> Group.IDLE
                }
                Row(
                    sessionId = sessionId,
                    title = state.title?.takeIf { it.isNotBlank() }
                        ?: state.cwd?.substringAfterLast('\\')?.substringAfterLast('/')
                        ?: "未命名会话",
                    cwd = state.cwd.orEmpty(),
                    group = group,
                    relativeText = relativeTime(state.updatedAt, nowMillis),
                    runningSinceMillis = state.localRunningSinceMillis,
                )
            }
            .sortedWith(compareBy({ it.group.ordinal }, { it.title }))
            .toList()
    }

    /** "X 前"文案（updatedAt 为毫秒时间戳；§3.3 语义=最近人类提问时间）。 */
    private fun relativeTime(updatedAtMillis: Double, nowMillis: Long): String {
        if (updatedAtMillis <= 0) return ""
        val diffSeconds = (nowMillis - updatedAtMillis) / 1000.0
        return when {
            diffSeconds < 60 -> "刚刚"
            diffSeconds < 3600 -> "${(diffSeconds / 60).toInt()} 分钟前"
            diffSeconds < 86400 -> "${(diffSeconds / 3600).toInt()} 小时前"
            else -> "${(diffSeconds / 86400).toInt()} 天前"
        }
    }

    /** "约 X 分钟"运行时长（§3.3：本地翻转时刻近似，UI 标"约"）。 */
    fun runningForText(sinceMillis: Long?, nowMillis: Long): String {
        if (sinceMillis == null) return ""
        val minutes = (nowMillis - sinceMillis) / 60_000
        return when {
            minutes < 1 -> "刚启动"
            minutes < 60 -> "约 $minutes 分钟"
            else -> "约 ${minutes / 60} 小时 ${minutes % 60} 分"
        }
    }

    /** 新建会话（§5.4：选 workspace → session.create(cwd)）。成功后触发列表刷新（requestManualRefresh + tick）并回调新 sessionId（供跳转）。 */
    fun createSession(cwd: String, onCreated: (String?) -> Unit) {
        viewModelScope.launch {
            _creating.value = true
            try {
                when (val r = DshRepository.apiClient.sessionCreate(cwd)) {
                    is dev.dshmobile.model.ApiResult.Ok -> {
                        DshRepository.requestManualRefresh()
                        _operationTick.value++
                        onCreated(r.value)
                    }
                    else -> onCreated(null)
                }
            } finally {
                _creating.value = false
            }
        }
    }

    /** 下拉刷新（§5.4）：请求服务重跑 baseline；等快照变化或 3s 超时兜底收起指示器。 */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        val before = DshRepository.sessions.value
        DshRepository.requestManualRefresh()
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    DshRepository.sessions.first { it !== before }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    // ---- 会话操作（需求 5.4.1：重命名/分叉/归档）----

    /** 最近一次操作失败的错误提示（null=无）。UI 以此弹 Toast。 */
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()

    fun clearOperationError() { _operationError.value = null }

    /** 操作生效计数器：重命名/分叉/归档成功后 +1，用作列表 rows 的 remember key 强制即时重算（不依赖异步 baseline）。 */
    private val _operationTick = MutableStateFlow(0)
    val operationTick: StateFlow<Int> = _operationTick.asStateFlow()

    /** 重命名会话（§5.4.1）：调 session.rename → 成功：触发 manualRefresh + tick++（强制列表即时重算）。 */
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.sessionRename(sessionId, title)) {
                is dev.dshmobile.model.ApiResult.Ok -> {
                    DshRepository.requestManualRefresh()
                    _operationTick.value++
                }
                is dev.dshmobile.model.ApiResult.BizError -> _operationError.value = "重命名失败：${r.code}"
                is dev.dshmobile.model.ApiResult.NetError -> _operationError.value = "重命名失败：请检查连接"
            }
        }
    }

    /** 分叉会话（§5.4.1）：调 session.fork → 成功：回调新 id（跳转）+ manualRefresh + tick++。 */
    fun forkSession(sessionId: String, onForked: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.sessionFork(sessionId)) {
                is dev.dshmobile.model.ApiResult.Ok -> {
                    onForked(r.value)
                    DshRepository.requestManualRefresh()
                    _operationTick.value++
                }
                is dev.dshmobile.model.ApiResult.BizError -> _operationError.value = "分叉失败：${r.code}"
                is dev.dshmobile.model.ApiResult.NetError -> _operationError.value = "分叉失败：请检查连接"
            }
        }
    }

    /** 归档会话（§5.4.1）：调 workspace.archiveSession → 成功后刷新列表（该会话从当前列表移除）。 */
    fun archiveSession(sessionId: String) {
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.workspaceArchiveSession(sessionId)) {
                is dev.dshmobile.model.ApiResult.Ok -> {
                    DshRepository.requestManualRefresh()
                    _operationTick.value++
                }
                is dev.dshmobile.model.ApiResult.BizError -> _operationError.value = "归档失败：${r.code}"
                is dev.dshmobile.model.ApiResult.NetError -> _operationError.value = "归档失败：请检查连接"
            }
        }
    }

    companion object {
        /** 7 天窗口（毫秒；updatedAt 为毫秒，终审 P0-2）。 */
        private const val SEVEN_DAYS_MILLIS = 7.0 * 86400 * 1000
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(viewModel: SessionListViewModel = viewModel()) {
    val sessions by DshRepository.sessions.collectAsState()
    val connection by DshRepository.connectionState.collectAsState()
    val workspaces by DshRepository.workspaces.collectAsState()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showCreateSheet by remember { mutableStateOf(false) }

    // 心跳刷新相对时间文案（每 30s；相对时间不需要秒级精度）
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val operationTick = viewModel.operationTick.collectAsState().value
    // 本机已发过消息的会话集合（blank 豁免依据）：变化时重算行——发送受理后返回列表立即生效
    val promptedSessions = DshRepository.promptMarkedSessions.collectAsState().value
    val rows = remember(sessions, nowMillis, workspaces, viewModel.selectedWorkspaceId.collectAsState().value, operationTick, promptedSessions) {
        viewModel.rowsFrom(sessions, nowMillis, workspaces)
    }
    val refreshing by viewModel.refreshing.collectAsState()
    val selectedWs by viewModel.selectedWorkspaceId.collectAsState()
    var showWsfilter by remember { mutableStateOf(true) }   // 可折叠工作区过滤条（需求 2.9.2）
    val context = LocalContext.current
    // 会话操作（需求 5.4.1）：目标会话 + 当前弹的对话框类型
    var menuTarget by remember { mutableStateOf<SessionListViewModel.Row?>(null) }
    var renameTarget by remember { mutableStateOf<SessionListViewModel.Row?>(null) }
    var forkTarget by remember { mutableStateOf<SessionListViewModel.Row?>(null) }
    var archiveTarget by remember { mutableStateOf<SessionListViewModel.Row?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建会话")
            }
        },
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionBanner(connection)
                // 需求 2.9.1/2.9.2：工作区过滤条（可折叠，折叠后不占空间避免干扰）
                WorkspaceFilterBar(
                    workspaces = workspaces,
                    selected = selectedWs,
                    expanded = showWsfilter,
                    onToggle = { showWsfilter = !showWsfilter },
                    onSelect = { viewModel.selectWorkspace(it) },
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    SessionListViewModel.Group.entries.forEach { group ->
                        val groupRows = rows.filter { it.group == group }
                        if (groupRows.isEmpty()) return@forEach
                        item(key = "header-${group.name}") {
                            GroupHeader(groupLabel(group), groupRows.size)
                        }
                        items(groupRows, key = { it.sessionId }) { row ->
                            SessionRow(row, nowMillis, viewModel, context, onMenuOpen = { menuTarget = row })
                        }
                    }
                    if (rows.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("暂无会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
            NewSessionSheetContent(
                workspaces = workspaces,
                onCreate = { cwd ->
                    showCreateSheet = false
                    viewModel.createSession(cwd) { newId ->
                        if (newId == null) {
                            // 终审 P2-10：失败必须给用户反馈（不得静默）
                            android.widget.Toast.makeText(
                                context, "新建会话失败，请检查连接", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            // 创建成功：自动进入新会话对话页
                            context.startActivity(
                                android.content.Intent(context, ConversationActivity::class.java)
                                    .putExtra(ConversationActivity.EXTRA_SESSION_ID, newId)
                            )
                        }
                    }
                },
            )
        }
    }

    // 会话操作菜单（需求 5.4.1：重命名/分叉/归档）
    menuTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { menuTarget = null }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("会话操作", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                // 重命名
                androidx.compose.material3.HorizontalDivider()
                TextButton(onClick = { menuTarget = null; renameTarget = target }, modifier = Modifier.fillMaxWidth()) {
                    Text("重命名", modifier = Modifier.fillMaxWidth(), fontSize = 15.sp)
                }
                // 分叉
                TextButton(onClick = { menuTarget = null; forkTarget = target }, modifier = Modifier.fillMaxWidth()) {
                    Text("分叉会话", modifier = Modifier.fillMaxWidth(), fontSize = 15.sp)
                }
                // 归档
                TextButton(onClick = { menuTarget = null; archiveTarget = target }, modifier = Modifier.fillMaxWidth()) {
                    Text("归档会话", modifier = Modifier.fillMaxWidth(), fontSize = 15.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 重命名对话框
    renameTarget?.let { target ->
        var newTitle by remember(target.sessionId) { mutableStateOf(target.title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = { OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, maxLines = 2) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(target.sessionId, newTitle.trim())
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // 分叉确认对话框
    forkTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { forkTarget = null },
            title = { Text("分叉会话") },
            text = { Text("从当前会话「${target.title}」分叉出一个新的子会话？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forkSession(target.sessionId) { newId ->
                        context.startActivity(
                            android.content.Intent(context, ConversationActivity::class.java)
                                .putExtra(ConversationActivity.EXTRA_SESSION_ID, newId)
                        )
                    }
                    forkTarget = null
                }) { Text("分叉") }
            },
            dismissButton = { TextButton(onClick = { forkTarget = null }) { Text("取消") } },
        )
    }

    // 归档确认对话框
    archiveTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档会话") },
            text = { Text("归档「${target.title}」？归档后从当前列表隐藏，可在归档区查看。") },
            confirmButton = {
                TextButton(onClick = { viewModel.archiveSession(target.sessionId); archiveTarget = null }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
        )
    }

    // 操作失败提示
    val opError by viewModel.operationError.collectAsState()
    opError?.let { msg ->
        androidx.compose.runtime.LaunchedEffect(msg) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearOperationError()
        }
    }
}

private fun groupLabel(group: SessionListViewModel.Group): String = when (group) {
    SessionListViewModel.Group.RUNNING -> "跑动中"
    SessionListViewModel.Group.WAITING -> "等你输入"
    SessionListViewModel.Group.IDLE -> "空闲"
}

/** 工作区过滤条（需求 2.9.1/2.9.2）：可折叠 chips，选中过滤会话；折叠后只留一行标题不占空间。 */
@Composable
private fun WorkspaceFilterBar(
    workspaces: List<dev.dshmobile.model.WorkspaceView>,
    selected: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    // 折叠态：一个"工作区"切换按钮（折叠后不占空间，需求 2.9.2）
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggle) {
            Text(if (expanded) "工作区 ▾" else "工作区 ▴", fontSize = 13.sp)
        }
    }
    if (expanded) {
        // 工作区 chips（多工作区时可横向滚动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("全部", selected == null) { onSelect(null) }
            workspaces.forEach { ws ->
                FilterChip(ws.title, selected == ws.workspaceId) { onSelect(ws.workspaceId) }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 13.sp) },
    )
}

@Composable
private fun ConnectionBanner(state: DshRepository.ConnectionState) {
    val (text, color) = when (state) {
        DshRepository.ConnectionState.CONNECTED -> "已连接" to Color(0xFF2E7D32)
        DshRepository.ConnectionState.RECONNECTING -> "重连中…" to Color(0xFFF9A825)
        DshRepository.ConnectionState.UNREACHABLE ->
            "不可达——请检查 Tailscale VPN 是否在线" to Color(0xFFC62828)
        DshRepository.ConnectionState.UNKNOWN -> "连接中…" to Color(0xFF9E9E9E)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun GroupHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionRow(
    row: SessionListViewModel.Row,
    nowMillis: Long,
    viewModel: SessionListViewModel,
    context: android.content.Context,
    onMenuOpen: () -> Unit,
) {
    val dotColor = when (row.group) {
        SessionListViewModel.Group.RUNNING -> Color(0xFF2E7D32)
        SessionListViewModel.Group.WAITING -> Color(0xFFF9A825)
        SessionListViewModel.Group.IDLE -> Color(0xFF9E9E9E)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    android.content.Intent(context, ConversationActivity::class.java)
                        .putExtra(ConversationActivity.EXTRA_SESSION_ID, row.sessionId)
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),   // 行高≈64dp 排版原则（§5.4）
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).background(dotColor, CircleShape))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.cwd,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (row.group == SessionListViewModel.Group.RUNNING)
                viewModel.runningForText(row.runningSinceMillis, nowMillis)
            else row.relativeText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 会话操作菜单入口（需求 5.4.1：重命名/分叉/归档）
        IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "会话操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewSessionSheetContent(
    workspaces: List<dev.dshmobile.model.WorkspaceView>,
    onCreate: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("新建会话", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text("选择工作区", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        if (workspaces.isEmpty()) {
            Text("（服务未连接，稍后重试）", fontSize = 13.sp)
        }
        workspaces.forEach { workspace ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreate(workspace.workspaceId) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(workspace.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(workspace.path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
