package com.valhalla.feature.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 硬编码中文扫描。
 *
 * 只扫描用户可见层（`ui`、`theme` 与桌面壳），i18n 资源目录本身除外；
 * 注释、开发者日志与协议层诊断文本允许保留中文，但界面字符串不允许。
 */
class NoHardcodedChineseTest {

    private val scannedDirs = listOf(
        "feature/src/commonMain/kotlin/com/valhalla/feature/ui",
        "feature/src/commonMain/kotlin/com/valhalla/feature/theme",
        "composeApp/src/commonMain/kotlin",
    )

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `界面代码没有硬编码中文`() {
        val root = projectRoot()
        val offenders = mutableListOf<String>()

        scannedDirs.forEach { relative ->
            val dir = File(root, relative)
            if (!dir.exists()) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "/i18n/" !in it.path }
                .forEach { file ->
                    var inBlockComment = false
                    file.readLines().forEachIndexed { index, raw ->
                        var line = raw
                        if (inBlockComment) {
                            val end = line.indexOf("*/")
                            if (end >= 0) {
                                line = line.substring(end + 2)
                                inBlockComment = false
                            } else {
                                return@forEachIndexed
                            }
                        }
                        while (true) {
                            val start = line.indexOf("/*")
                            if (start < 0) break
                            val end = line.indexOf("*/", start + 2)
                            if (end < 0) {
                                line = line.substring(0, start)
                                inBlockComment = true
                                break
                            }
                            line = line.substring(0, start) + line.substring(end + 2)
                        }
                        line = line.substringBefore("//")
                        if (cjk.containsMatchIn(line)) {
                            offenders += "${file.relativeTo(root)}:${index + 1}: ${raw.trim()}"
                        }
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "界面代码存在硬编码中文，请改用 AppStrings：\n" + offenders.joinToString("\n"),
        )
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }
}
