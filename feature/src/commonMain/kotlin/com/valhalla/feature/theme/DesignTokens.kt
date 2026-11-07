package com.valhalla.feature.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌：间距、圆角、状态色。
 *
 * 页面里不再直接写 `16.dp` 之类的魔法数字，统一从这里取；后续做设计调整只改一处。
 */
object DesignTokens {
    object Spacing {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Radius {
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
    }

    object Sizes {
        val progressBarHeight = 6.dp
        val dividerThickness = 1.dp
        val cardElevation = 0.dp
    }

    /** 成功/警告/错误/进行中四类状态色，亮暗各一套。 */
    @Immutable
    data class StatusColors(
        val success: Color,
        val warning: Color,
        val error: Color,
        val inProgress: Color,
    )

    val lightStatusColors =
        StatusColors(
            success = Color(0xFF1B7F3B),
            // 原 #B26A00 与浅色背景对比度 4.16:1，低于 WCAG AA 4.5:1，调深至 6.28:1。
            warning = Color(0xFF8A5200),
            error = Color(0xFFB3261E),
            inProgress = Color(0xFF1B5E9E),
        )

    val darkStatusColors =
        StatusColors(
            success = Color(0xFF6DD58C),
            warning = Color(0xFFFFB74D),
            error = Color(0xFFFF897D),
            inProgress = Color(0xFF9EC9FF),
        )

    private val LightPalette =
        lightColorScheme(
            primary = Color(0xFF1B5E9E),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFF4E6272),
            surfaceVariant = Color(0xFFE3E8EE),
        )

    private val DarkPalette =
        darkColorScheme(
            primary = Color(0xFF9EC9FF),
            onPrimary = Color(0xFF002F51),
            secondary = Color(0xFFB6C8D6),
            surfaceVariant = Color(0xFF2A3138),
        )

    val lightScheme get() = LightPalette
    val darkScheme get() = DarkPalette
}

/** 主题偏好：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** 纯函数：给定偏好与系统当前是否深色，解析最终是否使用深色主题。 */
fun resolveDarkTheme(
    mode: ThemeMode,
    systemDark: Boolean,
): Boolean =
    when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/** 当前主题下的状态色，页面统一从这里取，避免各页面自行定义红/绿。 */
@Composable
fun statusColors(): DesignTokens.StatusColors =
    if (MaterialTheme.colorScheme.background.luminanceIsDark()) {
        DesignTokens.darkStatusColors
    } else {
        DesignTokens.lightStatusColors
    }

private fun Color.luminanceIsDark(): Boolean {
    val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    return luminance < 0.5
}

/** 应用主题：按偏好或系统解析亮暗，套用统一的颜色方案。 */
@Composable
fun ValhallaTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = resolveDarkTheme(mode, systemDark)
    MaterialTheme(
        colorScheme = if (dark) DesignTokens.darkScheme else DesignTokens.lightScheme,
        content = content,
    )
}
