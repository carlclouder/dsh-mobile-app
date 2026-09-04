package dev.dshmobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy   // ScrollableState.scrollBy 扩展（jumpToEnd 补滚剩余高度用）
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dshmobile.service.NotificationStateMachine
import dev.dshmobile.ui.ConversationViewModel.UiMessage
import dev.jeziellago.compose.markdowntext.MarkdownText as LibMarkdown
import kotlinx.coroutines.launch

/**
 * 会话对话页（M5 原生）：顶部系统栏让位（statusBarsPadding）+ 原生顶条 + 消息列表 + 输入行。
 * 由 WebView 版改为原生 Compose 渲染（见 DESIGN.md §5.5 修订说明）。
 */
class ConversationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "会话"
        setContent {
            val vm: ConversationViewModel = viewModel(factory = ConversationViewModel.factory(sessionId))
            ConversationScreen(vm, title, onBack = { finish() })
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TITLE = "title"
    }
}

@Composable
private fun ConversationScreen(viewModel: ConversationViewModel, title: String, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsState()
    val running by viewModel.running.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // 跳到底部按钮显隐（上翻离开底部时显示；提前声明供 Box 使用）
    var showJumpToBottom by remember { mutableStateOf(false) }
    val jumpScope = rememberCoroutineScope()
    // 流式跟随开关（修复"流式输出期间无法上滑"）：用户拖动列表即停跟随（贴底逻辑在
    // 接近底部区间内每次刷新都会把视口拽回，与拖动打架）；点「跳到底部」或发送消息时恢复
    var followStream by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 页面底色提亮为 surface（与气泡 surfaceVariant 形成对比——默认主题下两者原先几乎同色，
            // 气泡边界不可见被感知为"留边过宽"；气泡内边距已收紧为 5dp）
            .background(MaterialTheme.colorScheme.surface)
            // 让出系统状态栏（顶部固定不被挤走）；导航栏与键盘 insets 取并集：
            // 键盘弹起时按 IME 高度垫高（adjustResize 下窗口不再整体平移——修复"键盘一弹顶条被挤出"），
            // 键盘收起时回落到导航栏高度，两态互斥不叠加。
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        // 原生顶条
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 需求：模型/推理等级选择器（chip → 底部弹出选择）
            ModelChip(viewModel)
            if (running) {
                TextButton(onClick = { viewModel.stop() }) { Text("停止") }
            }
        }
        HorizontalDivider()

        // 消息列表（Box 包裹：悬浮"跳到底部"按钮叠加在其上）
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 注：不传 key（默认索引键）——避免消息 key 重复导致 LazyColumn 崩溃（部分流式/回显状态下 key 会重复）
                items(messages) { msg -> MessageBubble(msg) }
                if (running) {
                    item(key = "thinking") { ThinkingIndicator() }
                }
            }
            // 跳到底部按钮：上翻离开底部时显示，点击滚到底部并恢复自动滚动
            if (showJumpToBottom) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        "⬇ 跳到底部",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable {
                                followStream = true   // 点跳到底部 = 恢复流式跟随
                                jumpScope.launch { listState.jumpToEnd(messages.size) }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 停靠区（统计/任务/审批/提问/排队）——动态限高 + 可滚动：
        // 修复"提问窗无法翻动/遮挡按钮/会话页面永久卡住"的布局根因。此前这组组件是外层 Column 的
        // 非加权固定子项且无高度上限：ask_user_question 载荷（多问题×多选项×补充框×提交钮）超高时，
        // weight(1f) 的消息列表被压到 0 高、输入行被顶出屏幕、提问卡超屏部分不可达，页面死锁。
        // 现取实时 maxHeight 的 45% 作上限（不写死 dp）：内容不足高时自然收缩无空白；超高时截断
        // 并可滚动翻看；输入行恒在屏底、消息列表恒有非零高度。
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.45f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 需求：上下文使用统计（输入/输出/缓存 token）
                TokenStatsLine(viewModel)
                // 需求：当前会话任务列表栏（上拉/展开，进行中带转圈）
                TodoBar(viewModel)
                // 需求：内联审批卡/提问卡（挂起审批与提问）
                ApprovalCards(viewModel)
                QuestionCards(viewModel)
                // 需求：排队消息（编辑/删除）
                QueuedMessages(viewModel)
            }
        }
        HorizontalDivider()

        // 输入行（仅消息发送 + 语音；插话已移入排队消息行）
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(8.dp))
            MicButton(onText = { text -> input = input + text })
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        followStream = true   // 用户发消息 = 期待跟随新回复
                        viewModel.send(text)
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) { Text("发送") }
        }
    }

    // 自动滚到底部（新消息进入）。首次进入直接瞬间跳到底部最新消息（避免从顶滚到底的动画刷屏）。
    // 实时跟随：流式期间占位消息(STREAMING_KEY)的 text 会持续增长但 messages.size 不变，
    // 所以除了 messages.size 还要以"最后一条消息的 text 长度"作为触发键——流式内容推进时能持续自动滚到底部。
    // 判断"用户是否接近底部"再决定是否自动滚，避免用户往上翻阅时被强行拉回。
    var scrolledOnce by remember { mutableStateOf(false) }
    // 首滚门控（滚底修复）：等历史加载完成再做首次定位——消息列表是多波异步填充的，
    // 少量实时消息先到时提前滚动会把 scrolledOnce 消耗掉，历史大列表到位后因 isNearBottom=false 永不滚动。
    val historyLoaded by viewModel.historyLoaded.collectAsState()
    val lastKey = messages.lastOrNull()?.key ?: ""
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    androidx.compose.runtime.LaunchedEffect(historyLoaded, messages.size, lastKey, lastTextLen, running) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!scrolledOnce) {
            if (!historyLoaded) return@LaunchedEffect
            listState.jumpToEnd(messages.size)   // 跳到内容真正底部（长消息也贴底，而非停在消息开头）
            scrolledOnce = true
        } else if (followStream && listState.isNearBottom(messages.size)) {
            // 流式跟随：贴真底（followStream 由用户拖动关闭——拖动期间不再拽回，见下方
            // interactionSource 收集器；点「跳到底部」或发送消息时恢复）。不再 animateScrollToItem
            // ——它把最后一条"顶边"对齐视口顶，长消息先被顶上去再补滚回来；流式高频刷新下动画序列
            // 常被打断在中途，视口停在长消息开头。改为：最后一项已在视口内 → 仅补滚剩余高度贴底；
            // 不可见（新增消息）→ 瞬跳+补滚。
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisibleIndex >= messages.size - 1) {
                listState.scrollRemainderToBottom()
            } else {
                listState.jumpToEnd(messages.size)
            }
        }
    }

    // 用户拖动即停流式跟随（修复"流式期间无法上滑"）：DragInteraction.Start 只来自真实指针拖动，
    // 程序化 scrollBy 不触发，不会误伤跟随逻辑自身
    androidx.compose.runtime.LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                followStream = false
            }
        }
    }

    // 跳到底部按钮（类似 WebUI）：上翻离开底部时显示「⬇ 跳到底部」，点它滚到底并恢复自动滚动。
    // 用 snapshotFlow 监听滚动位置，非底部时置 showJumpToBottom=true，回到底部自动隐藏。
    androidx.compose.runtime.LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val vi = listState.layoutInfo.visibleItemsInfo
            (vi.lastOrNull()?.index ?: 0) to messages.size
        }.collect { (lastVisible, size) ->
            showJumpToBottom = size > 0 && lastVisible < size - 3
        }
    }

    // 操作失败提示（插话/编辑/删除共用一条通道；steer-unavailable 等瞬时错误已在 VM 静默收敛，不会到这里）
    val actionError by viewModel.actionError.collectAsState()
    actionError?.let { msg ->
        val toastContext = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(msg) {
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearActionError()
        }
    }
}

/** 是否接近列表底部（最后可见项 >= size-3 视为接近底部；用于决定是否自动滚/显示跳底按钮）。 */
private fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(size: Int): Boolean {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    return lastVisible >= size - 3
}

/**
 * 瞬间滚到列表内容的真正底部：先 scrollToItem 到最后一项（只把该项顶边对齐视口顶），
 * 再补滚最后一项超出视口下缘的剩余高度——最后一条是长消息（高于视口）时才能看到最新内容，
 * 而不是停在消息开头（"进会话不滚底"的确定性根因）。
 * 剩余量按 Compose 官方实现语义计算（滚到底 ⇔ 最后项底边 = viewportEndOffset − afterContentPadding，
 * 经 androidx LazyListMeasure.kt 实现源码验证精确贴底；scrollBy 在可滚动边界自动截断，
 * 内容不足一屏时 remaining≤0 不滚）。
 * 注意：公式假设非 reverseLayout 的常规列表（本项目 LazyColumn 即如此）；未来若改 reverseLayout 需重推方向。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.jumpToEnd(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1)   // 同步 forceRemeasure，返回时目标项必已入 visibleItemsInfo
    scrollRemainderToBottom()
}

/** 从当前位置把"最后一项超出视口下缘"的剩余高度补滚掉；最多收敛 3 次（防布局中途变化导致的小残差）。 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollRemainderToBottom() {
    repeat(3) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        val remaining = (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
        if (remaining <= 0) return   // 已贴底（或内容不足一屏）
        scrollBy(remaining.toFloat())
    }
}

@Composable
private fun MessageBubble(msg: UiMessage) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        when (msg.role) {
            ConversationViewModel.Role.USER -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 9.dp, vertical = 2.dp)) {
                        CollapsibleMarkdownBody(msg.text, fontSizeSp = 15f)
                        TimestampLine(msg.timeMillis)
                    }
                }
            }
            ConversationViewModel.Role.ASSISTANT -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 助手消息保留气泡（内容收在条内）。留边过宽的真正根因是默认主题下
                // surfaceVariant(气泡) 与页面底色几乎同色（气泡边界不可见被感知成空白）——
                // 现把页面底色调亮为 surface（见根布局 background），气泡与页面形成清晰对比。
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 9.dp, vertical = 2.dp)) {
                        msg.reasoning?.takeIf { it.isNotBlank() }?.let {
                            // 流式占位消息 → 折叠行走"钉尾滚动摘要"态（对齐 WebUI ReasoningRow）；
                            // 落定/历史消息保持原静态文案
                            CollapsibleThinking(
                                it,
                                streaming = msg.key == ConversationViewModel.STREAMING_KEY,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        // 流式占位消息用纯文本渲染（修复"表格闪烁/错乱"）：AndroidView TextView
                        // 每 80ms 全量重排 markdown，表格在半成品语法期反复错乱重绘。落定
                        // （settled AssistantMessage 替换占位）后一次性渲染完整 markdown。
                        // 空正文跳过渲染：空 TextView 仍占一行高度，是"思考行与工具卡之间大空隙"的来源。
                        if (msg.key == ConversationViewModel.STREAMING_KEY) {
                            if (msg.text.isNotEmpty()) {
                                Text(msg.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            if (msg.text.isNotBlank()) {
                                CollapsibleMarkdownBody(msg.text)
                            }
                        }
                        if (msg.toolCalls.isNotEmpty()) {
                            if (msg.text.isNotBlank() || msg.reasoning?.isNotBlank() == true) {
                                Spacer(Modifier.height(4.dp))
                            }
                            msg.toolCalls.forEach { tc -> ToolCallCard(tc) }
                        }
                        // 工具结果（已合并进本助手消息）：对齐 WebUI 紧凑渲染——默认单行（宿主
                        // view.title 或首行截断），点按展开全文
                        msg.toolResults.forEach { result -> CollapsibleToolResult(result, msg.toolError) }
                        TimestampLine(msg.timeMillis)
                    }
                }
            }
            ConversationViewModel.Role.TOOL -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CollapsibleToolResult(
                    ConversationViewModel.ToolResultDisplay(msg.text, msg.toolViewTitle),
                    msg.toolError,
                )
            }
            // 上下文注入/召回：只显示一行来源摘要，正文默认折叠（修复注入全文刷屏）
            ConversationViewModel.Role.CONTEXT -> ContextInjectionRow(msg)
            // 模型/代理调用错误（额度用完/API失败）：红色气泡，让用户知道是调用失败而非消息没发出
            ConversationViewModel.Role.ERROR -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("⚠ 模型调用错误", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(3.dp))
                        MarkdownText(msg.text, fontSizeSp = 13f)
                        TimestampLine(msg.timeMillis)
                    }
                }
            }
        }
    }
}

/**
 * 上下文注入/召回单行条（对齐 WebUI ContextInjectionRow：默认折叠一行，点按展开正文）。
 * 一行 = 图标 + 「上下文注入/跨会话召回」+ 来源名（文件路径/插件名等，单行省略）。
 */
@Composable
private fun ContextInjectionRow(msg: UiMessage) {
    var expanded by remember { mutableStateOf(false) }
    val isRecall = msg.contextRole == "recall"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isRecall) "🔄" else "📥", fontSize = 12.sp, lineHeight = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isRecall) "跨会话召回" else "上下文注入",
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                msg.contextLabel?.let { label ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· $label",
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // 点开展开注入正文（再点收起）；无正文则永远只有一行
        if (expanded && msg.text.isNotBlank()) {
            CollapsibleMarkdownBody(msg.text, fontSizeSp = 12f)
        }
    }
}

/** 工具调用卡片（WebUI 紧凑对齐）：默认单行"🔧 名字 · 参数摘要"，点按展开完整参数。 */
@Composable
private fun ToolCallCard(tc: dev.dshmobile.model.ConversationEvent.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧", fontSize = 12.sp, lineHeight = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(tc.name, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                // 单行参数摘要（截断省略），展开后由下方完整参数替代
                if (!expanded) {
                    Text(
                        toolCallSummary(tc),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("收起 ▾", fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                tc.arguments?.takeIf { it.isNotBlank() }?.let { args ->
                    Spacer(Modifier.height(4.dp))
                    Text(args, fontSize = 11.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 工具调用单行摘要：优先取参数 JSON 的第一个字符串值（如 pwsh 的 command、edit 的 file_path），
 * 取不到则原串折叠空白后截断。宿主 view.title 关联留待后续（call 视图与 assistant 消息内
 * tool-call 块的 id 配对），当前本地兜底已满足"单行极短"对齐。
 */
private fun toolCallSummary(tc: dev.dshmobile.model.ConversationEvent.ToolCall): String {
    val args = tc.arguments?.takeIf { it.isNotBlank() } ?: return ""
    val firstStringValue = Regex("\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(args)?.groupValues?.getOrNull(1)
    val base = firstStringValue ?: args.replace(Regex("\\s+"), " ")
    return if (base.length > 90) base.take(90) + "…" else base
}

/** 消息正文（自实现 MarkdownText 渲染）：超长正文折叠（>4000 字符缩写+可展开，与思考/工具结果一致），避免超长单条卡渲染。 */
@Composable
private fun CollapsibleMarkdownBody(text: String, fontSizeSp: Float = 15f) {
    val isLong = text.length > 4000
    var expanded by remember { mutableStateOf(false) }
    if (!isLong) {
        MarkdownText(text, fontSizeSp)
        return
    }
    Column {
        if (expanded) {
            MarkdownText(text, fontSizeSp)
        } else {
            // 折叠态：只渲染前 1000 字符，超出部分用展开钮补齐（避免首帧组合上千个 Text 节点卡死）
            MarkdownText(text.take(1000), fontSizeSp)
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起 ▾" else "展开全文 ▴", fontSize = 11.sp)
        }
    }
}

/**
 * 思考过程（紧凑单行版，修复"宽边框"观感）：不再用嵌套卡片（框中框双层 padding + 标题行 +
 * 两行预览，glm 每条回复都带思考过程，等于每条消息顶上垫一大块空白）。默认一行灰字提示，
 * 点按展开全文，再点收起。
 *
 * 流式中（streaming=true，仅 STREAMING_KEY 占位消息）：折叠态切到 ThinkingStreamRow——
 * 单行"最新末尾思考语句"钉尾横向滚动 + 扫光动画（对齐 WebUI ReasoningRow），
 * 让用户区分"在思考"（文字持续推进）与"卡住"（文字停住）。落定后占位被 settled 替换，自动回退本静态渲染。
 */
@Composable
private fun CollapsibleThinking(content: String, streaming: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    if (!expanded && streaming) {
        ThinkingStreamRow(content, onExpand = { expanded = true })
        return
    }
    Text(
        if (expanded) content else "💭 思考过程 · 点按展开",
        fontSize = 11.sp,
        lineHeight = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 1.dp),
    )
}

/**
 * 工具结果卡（WebUI 紧凑对齐）：默认单行"🔧/⚠ 工具结果 · 摘要"，点按展开全文。
 * 摘要优先宿主 view.title（逐工具单行摘要），无则取正文首行截断。空内容不渲染。
 */
@Composable
private fun CollapsibleToolResult(display: ConversationViewModel.ToolResultDisplay, isError: Boolean) {
    // 兜底：解析层已修类型化块提取，此处再挡真正空输出的结果（content:[] 等）——空卡无信息量
    if (display.text.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        onClick = { expanded = !expanded },
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isError) "⚠ 工具结果" else "🔧 工具结果", fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                // 单行摘要：宿主 view.title 优先，兜底正文首行
                if (!expanded) {
                    Text(
                        resultOneLine(display),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    Text("收起 ▾", fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                MarkdownText(display.text, fontSizeSp = 13f)
            }
        }
    }
}

/** 结果单行摘要：view.title 优先；兜底取正文第一个非空行，折叠空白并截断。 */
private fun resultOneLine(display: ConversationViewModel.ToolResultDisplay): String {
    display.viewTitle?.let { return it }
    val firstLine = display.text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    val collapsed = firstLine.trim().replace(Regex("\\s+"), " ")
    return if (collapsed.length > 90) collapsed.take(90) + "…" else collapsed
}

/** 当前会话任务列表栏（需求：类似 Web UI 的当前会话任务列表——可展开/收起；进行中项前带转圈）。 */
@Composable
private fun TodoBar(viewModel: ConversationViewModel) {
    val todos by viewModel.todos.collectAsState()
    if (todos.isEmpty()) return
    val inProgress = todos.count { it.status == "in_progress" }
    val pending = todos.count { it.status == "pending" }
    val done = todos.count { it.status == "done" || it.status == "completed" }
    // 默认收缩：进入会话时任务上拉列表折叠，点「展开 ▴」才显示明细
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("任务", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("$inProgress 进行中 · $pending 待处理", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ▾" else "展开 ▴", fontSize = 12.sp)
            }
        }
        if (expanded) {
            todos.forEach { item ->
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TodoStatusIndicator(item.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.content, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (item.status == "done" || item.status == "completed")
                            MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (item.status == "done" || item.status == "completed")
                            TextDecoration.LineThrough else null,
                    )
                }
            }
            // 全部完成时给个小结
            if (done > 0 && pending == 0 && inProgress == 0) {
                Spacer(Modifier.height(2.dp))
                Text("已完成 $done 项", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 任务状态指示：进行中=转圈；待处理=空心圆；完成=实心勾。 */
@Composable
private fun TodoStatusIndicator(status: String) {
    when (status) {
        "in_progress" -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        "done", "completed" -> Box(
            modifier = Modifier.size(14.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("✓", fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimary) }
        else -> Box(
            modifier = Modifier.size(14.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/** 挂起审批列表（需求：内联审批卡）。 */
@Composable
private fun ApprovalCards(viewModel: ConversationViewModel) {
    val approvals by viewModel.approvals.collectAsState()
    if (approvals.isEmpty()) return
    Column(modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        approvals.forEach { info -> ApprovalCard(viewModel, info) }
    }
}

/** 内联审批卡：工具名 + 原因 + 允许一次/拒绝（免开通知即可应答）。 */
@Composable
private fun ApprovalCard(viewModel: ConversationViewModel, info: NotificationStateMachine.ApprovalInfo) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("🔐 工具审批", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("工具：${info.toolName}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            info.reason?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.respondApproval(info.rpcId, info.approvalId, true) },
                    modifier = Modifier.weight(1f),
                ) { Text("允许一次") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.respondApproval(info.rpcId, info.approvalId, false) }) { Text("拒绝") }
            }
        }
    }
}

/** 挂起提问列表（需求：内联提问卡，可作答）。 */
@Composable
private fun QuestionCards(viewModel: ConversationViewModel) {
    val questions by viewModel.questions.collectAsState()
    if (questions.isEmpty()) return
    Column(modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        questions.forEach { info -> QuestionCard(viewModel, info) }
    }
}

/** 内联提问卡：问题 + 选项（单选/多选）+ 提交回答（respondQuestion）。 */
@Composable
private fun QuestionCard(viewModel: ConversationViewModel, info: NotificationStateMachine.QuestionInfo) {
    var selections by remember(info.rpcId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // 补充说明按题分存（修复：原先多问题共用一个 custom，同一段文字会重复提交给每道题——审计 Top3-② 后半项）
    var customs by remember(info.rpcId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("❓ Agent 提问", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            info.questions.forEach { q ->
                Spacer(Modifier.height(6.dp))
                Text(q.question, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // 选项（单选/多选：多选可多勾，单选点选替换）
                q.options.forEach { opt ->
                    val selected = selections[q.id]?.contains(opt.label) == true
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selections = if (q.multiSelect == true) {
                                val cur = selections[q.id] ?: emptySet()
                                selections + (q.id to (if (selected) cur - opt.label else cur + opt.label))
                            } else {
                                selections + (q.id to setOf(opt.label))
                            }
                        },
                        label = { Text(opt.label, fontSize = 13.sp) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                // 可自定义补充（有选项也可补一句；按题独立存储互不串写）
                OutlinedTextField(
                    value = customs[q.id] ?: "",
                    onValueChange = { customs = customs + (q.id to it) },
                    placeholder = { Text("补充说明…") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 4.dp),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val answers = info.questions.map { q ->
                        dev.dshmobile.model.QuestionAnswerItem(
                            id = q.id,
                            selected = selections[q.id]?.toList() ?: emptyList(),
                            custom = customs[q.id]?.takeIf { it.isNotBlank() },
                        )
                    }
                    viewModel.respondQuestion(info.rpcId, answers)
                },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) { Text("提交回答") }
        }
    }
}

/** 消息时间戳（小字灰）。 */
@Composable
private fun TimestampLine(timeMillis: Long) {    if (timeMillis <= 0) return
    val formatted = remember(timeMillis) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeMillis))
    }
    Text(formatted, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(top = 1.dp))
}

/**
 * Markdown 渲染（封装 jeziellago/compose-markdown 库，支持表格/图片/链接/代码/列表等）。
 * 包装为带 fontSizeSp 的便捷重载，供消息气泡/工具结果统一调用。
 */
@Composable
private fun MarkdownText(markdown: String, fontSizeSp: Float = 15f) {
    LibMarkdown(
        markdown = markdown,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        style = androidx.compose.ui.text.TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = fontSizeSp.sp,
        ),
        isTextSelectable = false,
        // 紧凑排版（用户反馈"内部行间距/顶底留边太大"）：库内部是 AndroidView 包 TextView，
        // Compose 的 TextStyle.lineHeight 传不进 TextView，必须在 afterSetMarkdown 回调里
        // 直接调 TextView：①行距倍数 1.25（主流 IM 观感区间 1.25~1.3）；②关掉 includeFontPadding
        // 削掉 TextView 默认的顶部字体空隙（顶底留边的元凶，约 2~3dp）。
        // isTextSelectable=false（原 true）：可选中的 TextView 走编辑器模式会消费全部触摸，
        // 导致气泡区域手指滑动无法滚动消息列表（实测整屏被表格气泡堵死翻不动）；
        // 关掉后库自动改用 LinkMovementMethod——链接仍可点，垂直滑动放行给 LazyColumn。
        // afterSetMarkdown 紧凑化收尾（markwon 渲染产物是不可变 SpannedString，需复制后处理再回写）：
        // ① 行距 1.25 倍；② includeFontPadding=false 削顶部字体空隙；
        // ③ 移除 markwon 的 HeadingSpan（其内部 MarkwonTheme.applyHeadingTextStyle 把标题放大到
        //    1.6~2 倍并画分隔线，聊天气泡里爆版面），改用 加粗+1.15 倍 保留标题层级。
        afterSetMarkdown = { textView ->
            textView.setLineSpacing(0f, 1.25f)
            textView.includeFontPadding = false
            val orig = textView.text
            if (orig != null && orig.isNotEmpty()) {
                val s = android.text.SpannableStringBuilder(orig)
                var changed = false
                // markwon-core 是 jeziellago 的传递依赖（编译期不可见），按类名匹配 HeadingSpan
                for (span in s.getSpans(0, s.length, java.lang.Object::class.java)) {
                    if (span.javaClass.simpleName != "HeadingSpan") continue
                    val start = s.getSpanStart(span)
                    val end = s.getSpanEnd(span)
                    s.removeSpan(span)
                    s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(android.text.style.RelativeSizeSpan(1.15f), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    changed = true
                }
                if (changed) textView.text = s
            }
        },
    )
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text("正在思考…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 语音输入麦克风按钮：按一次请求 RECORD_AUDIO 权限并启动识别，结果文本回填输入框（不自动发送）。
 * 无识别服务（模拟器/google_apis 个别镜像没有）时 Toast 提示降级；识别器随 Composable 生命周期创建/销毁。
 */
@Composable
private fun MicButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }

    // 识别器懒建：仅在可用时创建，随 Composable 销毁释放
    val recognizerHolder = remember {
        val r = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) { null }
        mutableStateOf(r)
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { recognizerHolder.value?.destroy() } }
    }

    /**
     * 降级通道：RecognizerIntent 识别活动（由厂商自带/用户安装的语音应用接管，如讯飞、
     * 输入法厂商的识别器——国产无谷歌服务 ROM 上这是真正可用的路径）。
     * 无任何应用可处理时给最终指引。
     */
    val speechActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        listening = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onText(text)
        }
    }

    fun fallbackToSpeechActivity(reason: String) {
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved != null) {
            runCatching { speechActivityLauncher.launch(intent) }
                .onFailure { Toast.makeText(context, "语音识别启动失败：$reason；请用键盘麦克风语音输入", Toast.LENGTH_LONG).show() }
        } else {
            Toast.makeText(
                context,
                "本机无语音识别服务（$reason）：请用键盘麦克风语音输入（输入法自带），或安装讯飞语音+等识别应用",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onText(text)
                listening = false
            }
            override fun onError(error: Int) {
                listening = false
                // 附错误码便于真机远程确诊（不同 ROM 行为差异大）
                val codeName = when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANG_UNAVAILABLE"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NET_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                    else -> "$error"
                }
                when (error) {
                    // 服务通道不可用（国产 ROM 常见：无谷歌识别服务/语言包）→ 降级走"识别活动"通道
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    -> fallbackToSpeechActivity("服务不可用($codeName)")
                    else -> Toast.makeText(context, "语音识别失败（$codeName）", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }
    }

    fun startRecognition() {
        // 服务通道优先（有谷歌服务的设备弹内置听写窗）；无服务/启动失败自动降级识别活动通道
        val recognizer = recognizerHolder.value ?: run {
            fallbackToSpeechActivity("无系统识别服务")
            return
        }
        listening = true
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(listener)
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            listening = false
            fallbackToSpeechActivity("服务启动异常")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // 修复：权限授予后自动开始识别（此前只提示，用户需再点一次）
            startRecognition()
        } else {
            Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    IconButton(
        onClick = {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@IconButton
            }
            startRecognition()
        },
    ) {
        Text(
            if (listening) "🔴" else "🎤",
            fontSize = 20.sp,
            modifier = Modifier.padding(2.dp),
        )
    }
}

/** 模型/推理等级选择 chip（点击 → 底部弹出选择器）；未加载时显示"模型"。 */
@Composable
private fun ModelChip(viewModel: ConversationViewModel) {
    val current by viewModel.currentModel.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    val label = current?.let { "${it.provider.split('-').lastOrNull() ?: it.provider}/${it.model.take(10)}" } ?: "模型"
    TextButton(onClick = { showPicker = true }) {
        Text("◆ $label", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showPicker) {
        ModelPickerSheet(viewModel, onDismiss = { showPicker = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(viewModel: ConversationViewModel, onDismiss: () -> Unit) {
    val models by viewModel.models.collectAsState()
    val current by viewModel.currentModel.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .padding(16.dp)
                // 可滚动：多供应商/多模型时全部可达
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Text("选择模型", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            // 1) 模型区：供应商分组 → 模型；点模型即选中 provider+model，思考等级重置为该模型默认
            if (models?.groups.isNullOrEmpty()) {
                Text("模型目录为空", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                models!!.groups.forEach { group ->
                    Text(group.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    group.models.forEach { model ->
                        val isModel = current?.provider == group.id && current?.model == model.id
                        androidx.compose.material3.FilterChip(
                            selected = isModel,
                            onClick = {
                                viewModel.selectModel(group.id, model.id, model.reasoning?.defaultEffort)
                            },
                            label = { Text(model.name, fontSize = 14.sp) },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            // 2) 思考等级区：单独选中当前模型的等级（模仿 WebUI 模型/思考等级分开选）
            val efforts = reasoningEfforts(models, current?.provider, current?.model)
            Spacer(Modifier.height(16.dp))
            Text("思考等级", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (efforts.isEmpty()) {
                Text("该模型不支持思考等级", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                efforts.forEach { effort ->
                    val isEffort = current?.reasoningEffort == effort.id
                    androidx.compose.material3.FilterChip(
                        selected = isEffort,
                        onClick = {
                            val cur = current
                            if (cur != null) viewModel.selectModel(cur.provider, cur.model, effort.id)
                        },
                        label = { Text(effort.name, fontSize = 13.sp) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 取出指定 supplier 下某个模型支持的思考等级列表（无则空）。 */
private fun reasoningEfforts(
    directory: dev.dshmobile.model.ModelDirectory?,
    provider: String?,
    model: String?,
): List<dev.dshmobile.model.ModelReasoningEffort> {
    if (directory == null || provider == null || model == null) return emptyList()
    return directory.groups.firstOrNull { it.id == provider }
        ?.models?.firstOrNull { it.id == model }
        ?.reasoning?.efforts ?: emptyList()
}

/** 会话统计信息栏（需求：轮/步、LLM/工具耗时、首token、tok/s、缓存命中、输入量）。 */
@Composable
private fun TokenStatsLine(viewModel: ConversationViewModel) {
    val stats by viewModel.sessionStats.collectAsState()
    val tk by viewModel.tokenStats.collectAsState()
    val st = stats
    // 全空则隐藏
    val hasAny = (st != null) || (tk.input + tk.output + tk.cache > 0)
    if (!hasAny) return
    val parts = mutableListOf<String>()
    st?.let {
        if (it.turns > 0 || it.steps > 0) parts += "${it.turns} 轮 · ${it.steps} 步"
        if (it.llmMs > 0 || it.toolMs > 0) parts += "LLM ${fmtMs(it.llmMs)} · 工具 ${fmtMs(it.toolMs)}"
        if (it.ttftSteps > 0) {
            val avgFirst = it.ttftMs / it.ttftSteps
            val tokPerSec = if (it.decodeMs > 0) (it.decodeTokens * 1000 / it.decodeMs).toInt() else 0
            parts += "首token ${fmtSec(avgFirst)} · ${tokPerSec} tok/s"
        }
    }
    // 缓存命中% + 输入量（由 usage 推导；窗口内近似，clamp 防负）
    val total = tk.cache + tk.input
    if (total > 0) {
        val cacheHit = ((tk.cache.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        parts += "缓存命中 ${cacheHit}% · 输入 ${fmtTok(tk.input)}"
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  |  "),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

private fun fmtMs(ms: Long): String {
    val totalMin = ms / 60_000
    val sec = (ms % 60_000) / 1000
    return if (totalMin >= 60) "${totalMin / 60}h${totalMin % 60}m${sec}s" else "${totalMin}m${sec}s"
}

private fun fmtSec(ms: Long): String = if (ms < 1000) "${(ms / 100.0).toInt() / 10.0}s" else "${(ms / 1000.0).let { String.format(java.util.Locale.US, "%.1f", it) }}s"

private fun fmtTok(tokens: Int): String = when {
    tokens >= 1000_000 -> "${tokens / 1000_000}M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> "$tokens"
}

/** 排队消息卡片（需求：编辑/删除排队消息；插话=提交后进入排队时才出现，与会话级 steer 同在下发）。 */
@Composable
private fun QueuedMessages(viewModel: ConversationViewModel) {
    val queued by viewModel.queuedItems.collectAsState()
    // 插话仅 agent 运行中可用（对齐 WebUI：!running 时按钮禁用 + 提示语）
    val running by viewModel.running.collectAsState()
    // 插话中的项（修复"插话后可连点重复发送"）：发出即禁用按钮，回显移除后自动恢复
    val steeringIds by viewModel.steeringItemIds.collectAsState()
    if (queued.isEmpty()) return
    var editing by remember { mutableStateOf<dev.dshmobile.model.QueueItem?>(null) }
    // 默认折叠：排队列表只显标题+数量，展开才列明细，避免占满屏幕
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("排队中（${queued.size}）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (!running) {
                Spacer(Modifier.width(8.dp))
                Text("仅运行中可插话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ▾" else "展开 ▴", fontSize = 12.sp)
            }
        }
        if (expanded) {
            queued.forEach { item ->
                Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // 消息文本完整一行（不挤占按钮空间，避免被挤出屏幕）
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                        Text(item.text, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                    // 按钮独立一行，紧凑排列（三个按钮自适应放一行，不抢文本横向空间）
                    Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(start = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { editing = item },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("✎", fontSize = 13.sp) }
                        TextButton(onClick = { viewModel.deleteQueued(item.id) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text("🗑", fontSize = 13.sp) }
                        // 插话（对齐 WebUI 每行"插话发送"）：把该排队项就地提升为 steer，host 在下一步边界消费；
                        // 仅运行中可点；插话中（已发出待回显）再点忽略并禁用——防止误连点发出两条同样的
                        val itemSteering = item.id in steeringIds
                        TextButton(
                            onClick = { viewModel.steer(item.id, item.text) },
                            enabled = running && !itemSteering,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text(if (itemSteering) "已插话…" else "⚡插话", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    // 编辑对话框（Compose AlertDialog）
    editing?.let { item ->
        var text by remember { mutableStateOf(item.text) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑排队消息") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 3) },
            confirmButton = {
                TextButton(onClick = { viewModel.editQueued(item.id, text); editing = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}
