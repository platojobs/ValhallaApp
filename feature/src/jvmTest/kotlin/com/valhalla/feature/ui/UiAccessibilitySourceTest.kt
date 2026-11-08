package com.valhalla.feature.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 无障碍与排版的源码级约束。
 *
 * 这些检查不替代真机读屏测试，但能拦住最常见的退化：
 * 绝对左右对齐（RTL 破版）、进度指示器缺语义、弹窗不可返回、单行截断大字体文本。
 */
class UiAccessibilitySourceTest {
    private val scannedDirs =
        listOf(
            "feature/src/commonMain/kotlin/com/valhalla/feature/ui",
            "composeApp/src/commonMain/kotlin",
        )

    private fun uiSources(): List<File> {
        val root = projectRoot()
        return scannedDirs.flatMap { relative ->
            val dir = File(root, relative)
            if (!dir.exists()) {
                emptyList()
            } else {
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
        }
    }

    @Test
    fun `界面代码没有绝对左右对齐`() {
        val forbidden =
            listOf(
                "TextAlign.Left",
                "TextAlign.Right",
                "Alignment.Left",
                "Alignment.Right",
                "padding(left",
                "padding(right",
                "paddingStart",
                "paddingEnd",
            )
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (forbidden.any { it in line }) {
                    offenders += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "发现绝对左右标记：\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `进度指示器带进度语义与可读描述`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val isIndicator =
                    "LinearProgressIndicator(" in line ||
                        "CircularProgressIndicator(" in line
                if (!isIndicator) return@forEachIndexed
                val window = lines.subList(maxOf(0, index - 2), minOf(lines.size, index + 12)).joinToString("\n")
                if ("progressBarRangeInfo" !in window) {
                    offenders += "${file.path}:${index + 1} 缺少 progressBarRangeInfo"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "进度指示器缺少语义：\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `弹窗都能通过返回键关闭`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("AlertDialog(")) return@forEachIndexed
                val window = lines.subList(index, minOf(lines.size, index + 15)).joinToString("\n")
                if ("onDismissRequest" !in window) {
                    offenders += "${file.path}:${index + 1} 缺少 onDismissRequest"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "弹窗无法返回：\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `单行文本同时声明溢出策略`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!Regex("""maxLines\s*=\s*\d+""").containsMatchIn(line)) return@forEachIndexed
                val window = lines.subList(index, minOf(lines.size, index + 6)).joinToString("\n")
                if ("overflow" !in window) {
                    offenders += "${file.path}:${index + 1} maxLines 未声明 overflow"
                }
            }
        }
        assertTrue(offenders.isEmpty(), "单行文本可能截断大字体内容：\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `四态面板集中声明语义`() {
        val file = File(projectRoot(), "feature/src/commonMain/kotlin/com/valhalla/feature/ui/ScreenState.kt")
        assertTrue(file.isFile, "ScreenState.kt 不存在")
        val text = file.readText()
        assertTrue("liveRegion = LiveRegionMode" in text, "四态面板缺少 liveRegion")
        assertTrue("progressBarRangeInfo" in text, "加载态缺少 progressBarRangeInfo")
        assertTrue("contentDescription" in text, "四态面板缺少 contentDescription")
    }

    private fun projectRoot(): File {
        var current: File? = File(System.getProperty("user.dir"))
        while (current != null && !File(current, "settings.gradle.kts").isFile) {
            current = current.parentFile
        }
        return current ?: error("找不到工程根目录")
    }
}
