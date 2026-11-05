package com.valhalla.feature.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 状态色对比度核对（WCAG 2.1 AA，正文小字要求 ≥ 4.5:1）。
 *
 * 背景取 Material3 亮/暗方案的 `background`，状态色通过 `statusColors()` 选择。
 * 这是纯色计算，不依赖真机；实际渲染还受透明度/主题叠加影响，仍需真机目测。
 */
class StatusColorContrastTest {

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        val lighter = max(a, b)
        val darker = min(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `浅色主题状态色对背景达到 AA`() {
        val colors = DesignTokens.lightStatusColors
        val background = DesignTokens.lightScheme.background
        listOf(
            "success" to colors.success,
            "warning" to colors.warning,
            "error" to colors.error,
            "inProgress" to colors.inProgress,
        ).forEach { (name, color) ->
            assertTrue(contrast(color, background) >= 4.5, "$name 浅色对比度不足 4.5:1")
        }
    }

    @Test
    fun `深色主题状态色对背景达到 AA`() {
        val colors = DesignTokens.darkStatusColors
        val background = DesignTokens.darkScheme.background
        listOf(
            "success" to colors.success,
            "warning" to colors.warning,
            "error" to colors.error,
            "inProgress" to colors.inProgress,
        ).forEach { (name, color) ->
            assertTrue(contrast(color, background) >= 4.5, "$name 深色对比度不足 4.5:1")
        }
    }
}
