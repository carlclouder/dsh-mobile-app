package dev.dshmobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.dshmobile.data.AppearancePreference

private fun color(argb: Long) = Color(argb)

internal enum class SkinSurface { BACKDROP, TOP, HERO, CARD, FEATURE, DOCK, PRIMARY, RAIL, USER }

internal data class DshSkin(
    val key: String,
    val dark: Boolean,
    val scheme: ColorScheme,
    val treatments: Map<SkinSurface, List<Color>>,
    val glow: Pair<Color, Color>,
    val running: Color,
    val waiting: Color,
    val idle: Color,
) {
    fun brush(surface: SkinSurface): Brush = Brush.linearGradient(treatments.getValue(surface))
}

private val Ice = DshSkin(
    key = AppearancePreference.ICE,
    dark = false,
    scheme = lightColorScheme(
        primary = color(0xFF344EA9), onPrimary = Color.White,
        primaryContainer = color(0xFFD7E5FF), onPrimaryContainer = color(0xFF213A7D),
        secondary = color(0xFF465C92), onSecondary = Color.White,
        secondaryContainer = color(0xFFD0DFFA), onSecondaryContainer = color(0xFF233C71),
        tertiary = color(0xFF435D95), onTertiary = Color.White,
        tertiaryContainer = color(0xFFD9E7FE), onTertiaryContainer = color(0xFF304D83),
        background = color(0xFFF1F5FF), onBackground = color(0xFF172D5D),
        surface = color(0xFFF8FBFF), onSurface = color(0xFF172D5D),
        surfaceVariant = color(0xFFE1EBFB), onSurfaceVariant = color(0xFF405780),
        surfaceContainerLowest = Color.White, surfaceContainerLow = color(0xFFF1F6FF),
        surfaceContainer = color(0xFFEAF1FE), surfaceContainerHigh = color(0xFFDFEAFC),
        surfaceContainerHighest = color(0xFFD5E2F9),
        outline = color(0xFF6079B0), outlineVariant = color(0xFFADC3E5),
        surfaceTint = color(0xFF3D5BBF),
    ),
    treatments = treatments(
        background = listOf(0xFFF6F7FE, 0xFFE4EDFB, 0xFFF0F3FF),
        top = listOf(0xFFFCFDFF, 0xFFDCE7FB, 0xFFCDD6F4),
        hero = listOf(0xFFF3F7FF, 0xFFC2D2F1, 0xFFDCE9FD),
        card = listOf(0xFFFDFEFF, 0xFFE1EBFD),
        feature = listOf(0xFFFFFFFF, 0xFFE2EDFF, 0xFFC4D4FF),
        dock = listOf(0xFFF8FBFF, 0xFFD3E1FA, 0xFFBECFEC),
        primary = listOf(0xFF556ACD, 0xFF3D5BBF, 0xFF3448A7),
        rail = listOf(0xFFFAFCFF, 0xFFCDDFFB, 0xFFADC4EC),
        user = listOf(0xFFF9FCFF, 0xFFD6E4FC),
    ),
    glow = color(0xDDB9D8FD) to color(0xAAC6E5EC),
    running = color(0xFF216D67), waiting = color(0xFF795021), idle = color(0xFF48648B),
)

private val Aurora = DshSkin(
    key = AppearancePreference.AURORA,
    dark = true,
    scheme = darkColorScheme(
        primary = color(0xFFA2FCE2), onPrimary = color(0xFF08282D),
        primaryContainer = color(0xFF315B68), onPrimaryContainer = color(0xFFBAFFEB),
        secondary = color(0xFFB8CFE7), onSecondary = color(0xFF152A40),
        secondaryContainer = color(0xFF35516E), onSecondaryContainer = color(0xFFE6F7FF),
        tertiary = color(0xFFD3D3FF), onTertiary = color(0xFF262951),
        tertiaryContainer = color(0xFF3B4584), onTertiaryContainer = color(0xFFF3F1FF),
        background = color(0xFF0C1421), onBackground = color(0xFFEDF7FE),
        surface = color(0xFF192A40), onSurface = color(0xFFEDF7FE),
        surfaceVariant = color(0xFF263A55), onSurfaceVariant = color(0xFFC8DEEF),
        surfaceContainerLowest = color(0xFF101929), surfaceContainerLow = color(0xFF152338),
        surfaceContainer = color(0xFF20344B), surfaceContainerHigh = color(0xFF294258),
        surfaceContainerHighest = color(0xFF345370),
        outline = color(0xFF89AAC8), outlineVariant = color(0xFF53738E),
        surfaceTint = color(0xFF78E6D6),
    ),
    treatments = treatments(
        background = listOf(0xFF121B2E, 0xFF0C1421, 0xFF101C30),
        top = listOf(0xFF18283B, 0xFF1A2C48),
        hero = listOf(0xFF295C6C, 0xFF192844, 0xFF343E87),
        card = listOf(0xFF304962, 0xFF22374E, 0xFF1D3049),
        feature = listOf(0xFF34556D, 0xFF1D334B, 0xFF3B4A90),
        dock = listOf(0xFF2B435D, 0xFF142136),
        primary = listOf(0xFFA2FCE2, 0xFF67E6D0, 0xFF43C9BB),
        rail = listOf(0xFF385B75, 0xFF315274),
        user = listOf(0xFF5154A7, 0xFF303C86),
    ),
    glow = color(0xA6305989) to color(0x78183E45),
    running = color(0xFF82F2CE), waiting = color(0xFFF3BE79), idle = color(0xFFB1C5DC),
)

private fun treatments(
    background: List<Long>, top: List<Long>, hero: List<Long>, card: List<Long>,
    feature: List<Long>, dock: List<Long>, primary: List<Long>, rail: List<Long>, user: List<Long>,
): Map<SkinSurface, List<Color>> = mapOf(
    SkinSurface.BACKDROP to background, SkinSurface.TOP to top, SkinSurface.HERO to hero,
    SkinSurface.CARD to card, SkinSurface.FEATURE to feature, SkinSurface.DOCK to dock,
    SkinSurface.PRIMARY to primary, SkinSurface.RAIL to rail, SkinSurface.USER to user,
).mapValues { (_, values) -> values.map(::color) }

internal val LocalDshSkin = staticCompositionLocalOf { Ice }

internal fun Modifier.skinPanel(surface: SkinSurface, shape: Shape? = null): Modifier = composed {
    val skin = LocalDshSkin.current
    val panelShape = shape ?: MaterialTheme.shapes.medium
    val start = if (skin.dark) color(0x87D7F4FF) else Color.White
    val end = skin.scheme.outlineVariant
    clip(panelShape)
        .background(skin.brush(surface), panelShape)
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(start, end))), panelShape)
}

internal fun Modifier.skinBackdrop(): Modifier = composed {
    val skin = LocalDshSkin.current
    drawWithCache {
        val background = skin.brush(SkinSurface.BACKDROP)
        val top = Brush.radialGradient(
            listOf(skin.glow.first, Color.Transparent),
            center = Offset(size.width * 0.93f, size.height * 0.06f),
            radius = (maxOf(size.width, size.height) * 0.62f).coerceAtLeast(1f),
        )
        val bottom = Brush.radialGradient(
            listOf(skin.glow.second, Color.Transparent),
            center = Offset(size.width * 0.13f, size.height * 0.96f),
            radius = (maxOf(size.width, size.height) * 0.58f).coerceAtLeast(1f),
        )
        onDrawBehind {
            drawRect(background)
            drawRect(top)
            drawRect(bottom)
        }
    }
}

/** Finite decoration only. Compose applies the system motion-duration scale to these specs. */
internal object SkinMotion {
    const val PRESS_MS = 130
    const val CONVERSATION_ENTER_MS = 370
    const val SHEET_CONTENT_ENTER_MS = 320
    const val SHEET_CONTENT_EXIT_MS = 280
}

/** A short highlight layered over the same Android touch/ripple and click handler. */
internal fun Modifier.skinPress(interactions: InteractionSource): Modifier = composed {
    val isPressed by interactions.collectIsPressedAsState()
    val lift by animateFloatAsState(
        targetValue = if (isPressed) 0.10f else 0f,
        animationSpec = tween(SkinMotion.PRESS_MS, easing = FastOutSlowInEasing),
        label = "DSH session press light",
    )
    val light = if (LocalDshSkin.current.dark) Color.White else MaterialTheme.colorScheme.primary
    drawWithContent {
        drawContent()
        if (lift > 0f) drawRect(light.copy(alpha = lift))
    }
}

/** Entrance runs on the visual layer after the existing Activity starts, never before a callback. */
internal fun Modifier.skinConversationEntrance(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(SkinMotion.CONVERSATION_ENTER_MS, easing = FastOutSlowInEasing),
        )
    }
    graphicsLayer {
        val remaining = 1f - progress.value
        alpha = 1f - 0.08f * remaining
        translationX = 10.dp.toPx() * remaining
    }
}

/** Animate only the sheet surface; Material 3 owns position, gestures, and dismissal. */
@Composable
internal fun skinSheetContentAlpha(visibleTarget: Boolean) = animateFloatAsState(
    targetValue = if (visibleTarget) 1f else 0.86f,
    animationSpec = tween(
        if (visibleTarget) SkinMotion.SHEET_CONTENT_ENTER_MS else SkinMotion.SHEET_CONTENT_EXIT_MS,
        easing = FastOutSlowInEasing,
    ),
    label = "DSH model sheet surface",
)

private val DshShapes = Shapes(
    small = RoundedCornerShape(9.dp), medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)
private val BaseTypography = Typography()
private val DshTypography = BaseTypography.copy(
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun DshTheme(themeKey: String, content: @Composable () -> Unit) {
    val skin = if (themeKey == AppearancePreference.AURORA) Aurora else Ice
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? android.app.Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !skin.dark
                    isAppearanceLightNavigationBars = !skin.dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalDshSkin provides skin) {
        MaterialTheme(
            colorScheme = skin.scheme, shapes = DshShapes, typography = DshTypography,
        ) {
            CompositionLocalProvider(LocalContentColor provides skin.scheme.onBackground) {
                Box(Modifier.fillMaxSize().skinBackdrop()) { content() }
            }
        }
    }
}
