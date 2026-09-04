package dev.dshmobile.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collect

/**
 * 思考折叠行的流式态组件（对齐 WebUI dsh-client-ui-conversation 的 ReasoningRow）：
 *
 * - 折叠摘要取"最新一行"（thinkingLatestLine），单行横向钉尾滚动——文本每变长一次就滚到最右，
 *   视觉上文字持续从右向左推进；文字停住不动 = 思考无新产出（可能卡住/在调工具），一眼可辨。
 * - 行上叠一条渐变光带 2.6s 无限横扫（对齐 WebUI sweep 动画），提示"回合仍在运行"；
 *   系统开了"减少动画"（animator 时长缩放=0）时不播光带，仅保留钉尾滚动。
 * - 点按整行回调 onExpand（由调用方把折叠态切到展开全文）。
 *
 * 仅流式中使用（调用方以 msg.key == STREAMING_KEY 判定）；落定/历史消息走原有静态渲染。
 */

/** 取思考文本的最新一行：trimEnd 后取最后一个 '\n' 之后的片段（无换行取全文）。逐字对齐 WebUI latestLine。 */
internal fun thinkingLatestLine(content: String): String {
    val visible = content.trimEnd()
    val newline = visible.lastIndexOf('\n')
    return if (newline == -1) visible else visible.substring(newline + 1)
}

/**
 * 扫光光带宽度（px）：行宽 × 0.35，夹在 [60dp, 160dp] 换算像素之间。
 * 宽度随行自适应（不写死）；dp→px 用屏幕密度 density（非字体缩放 fontScale——fontScale 只管 sp）。
 * rowWidthPx=0（未测量）时兜底到下限，保证光带可见。
 */
internal fun thinkingSweepBandWidthPx(rowWidthPx: Float, density: Float): Float {
    val target = rowWidthPx * 0.35f
    val min = 60f * density
    val max = 160f * density
    return target.coerceIn(min, max)
}

/** 流式思考折叠行：💭 思考中 + 单行钉尾滚动摘要 + 扫光动画；点按回调交调用方展开全文。 */
@Composable
internal fun ThinkingStreamRow(content: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val summary = thinkingLatestLine(content)

    // "减少动画"判定：系统 animator 时长缩放为 0 = 用户要求弱化动画 → 不播扫光。
    // 组合时读一次；用户中途改系统设置需重进会话生效（低频场景，不做广播监听）。
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    // 扫光进度 0→1 无限循环（2.6s 对齐 WebUI；LinearEasing 与 WebUI ease-out 的差异为已声明偏差：
    // 线性在循环重启时刻速度更均匀）。动画值只在 drawWithContent（绘制阶段）读取，不触发重组。
    val sweepProgress: State<Float>? = if (reducedMotion) {
        null
    } else {
        val transition = rememberInfiniteTransition(label = "thinkingSweep")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "thinkingSweepProgress",
        )
    }
    // 光带颜色在组合期解析（draw 阶段不能读 MaterialTheme）：用页面底色 60% 透明近似 WebUI 的 bg-base 混合
    val sweepColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(
                if (sweepProgress != null) {
                    Modifier.drawWithContent {
                        drawContent()
                        // 光带宽随行宽自适应（评审修正：dp→px 用 density）；x 从左缘外扫到右缘外
                        val bandWidth = thinkingSweepBandWidthPx(size.width, density)
                        val x = (size.width + bandWidth) * sweepProgress.value - bandWidth
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.5f to sweepColor,
                                1f to Color.Transparent,
                                startX = x,
                                endX = x + bandWidth,
                            ),
                            topLeft = Offset(x, 0f),
                            size = Size(bandWidth, size.height),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onExpand)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "💭 思考中",
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ThinkingStreamSummary(summary, modifier = Modifier.weight(1f))
    }
}

/**
 * 单行钉尾滚动摘要（对齐 WebUI summary + scrollLeft = scrollWidth - clientWidth）：
 * horizontalScroll 提供"固定视口 + 超宽内容"的滚动范围；enabled=false 对齐 WebUI overflow:hidden
 * 不响应手势（钉尾会覆盖手动位置，回看开头走点按展开）。softWrap=false 保证单行全文宽度参与测量。
 */
@Composable
private fun ThinkingStreamSummary(summary: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    // 钉尾：maxValue 是随布局更新的可观察状态——摘要变长→文本变宽→maxValue 更新→collect 钉到最右。
    // 单层机制：effect 挂 scrollState 常驻不重启，maxValue 变化即覆盖"摘要变化"的全部钉尾时机。
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }
            .collect { maxValue -> scrollState.scrollTo(maxValue) }
    }
    Box(modifier) {
        Text(
            text = summary,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.horizontalScroll(scrollState, enabled = false),
        )
    }
}
