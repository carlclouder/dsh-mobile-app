package dev.dshmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题（皮肤）系统：默认 / 护眼蓝 / 护眼绿。
 * 通过颜色柔和、低对比度保护视力；背景色偏冷/偏暖绿。
 */
object AppThemes {

    /** 默认（Material3 基线浅色）。 */
    val DEFAULT = lightColorScheme()

    /** 护眼蓝：柔和蓝背景、低饱和、低刺激。 */
    val EYE_BLUE = lightColorScheme(
        primary = Color(0xFF1A5CBF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E6FF),
        onPrimaryContainer = Color(0xFF0A3B7A),
        secondary = Color(0xFF4A6FA5),
        background = Color(0xFFEAF3FC),
        onBackground = Color(0xFF1A2B3B),
        surface = Color(0xFFF2F8FF),
        onSurface = Color(0xFF1A2B3B),
        surfaceVariant = Color(0xFFDCE9F7),
        onSurfaceVariant = Color(0xFF3C4A5C),
        errorContainer = Color(0xFFFCE9E9),
    )

    /** 护眼绿：柔和绿背景、低饱和、舒缓。 */
    val EYE_GREEN = lightColorScheme(
        primary = Color(0xFF2E7D32),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC8E6C9),
        onPrimaryContainer = Color(0xFF1B5E20),
        secondary = Color(0xFF52796F),
        background = Color(0xFFEAF4EA),
        onBackground = Color(0xFF1E2D22),
        surface = Color(0xFFF0F8F0),
        onSurface = Color(0xFF1E2D22),
        surfaceVariant = Color(0xFFD8EAD8),
        onSurfaceVariant = Color(0xFF36483A),
        errorContainer = Color(0xFFFCE9E9),
    )

    /** 主题键 → 配色（default/eyeblue/eyegreen）。 */
    fun schemeFor(key: String) = when (key) {
        "eyeblue" -> EYE_BLUE
        "eyegreen" -> EYE_GREEN
        else -> DEFAULT
    }
}

/** 全局主题应用：按设置的主题键选择配色。 */
@Composable
fun DshTheme(themeKey: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppThemes.schemeFor(themeKey),
        content = content,
    )
}
