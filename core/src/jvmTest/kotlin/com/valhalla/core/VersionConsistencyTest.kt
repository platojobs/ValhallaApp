package com.valhalla.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 版本号一致性（v2.5.0 扩展依赖许可清单）：
 * gradle.properties 是唯一来源，Android/iOS 与关于页的 AppBuildInfo 都从它派生。
 * 该测试防止各处版本号漂移，也防止回退到硬编码。
 */
class VersionConsistencyTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "gradle.properties").exists() }
        ?: error("无法定位项目根目录（user.dir=${System.getProperty("user.dir")}）")

    private val gradleProperties: Map<String, String> by lazy {
        File(root, "gradle.properties").readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || !trimmed.contains('=')) null
                else trimmed.substringBefore('=').trim() to trimmed.substringAfter('=').trim()
            }
            .toMap()
    }

    private val versionName: String get() = gradleProperties["valhalla.versionName"].orEmpty()
    private val versionCode: String get() = gradleProperties["valhalla.versionCode"].orEmpty()

    @Test
    fun `gradle properties 提供 2_6_0 版本号`() {
        assertEquals("2.6.0", versionName)
        assertTrue(versionName.matches(Regex("""\d+\.\d+\.\d+""")), "versionName 应为 x.y.z：$versionName")
        assertEquals(26, versionCode.toIntOrNull())
    }

    @Test
    fun `关于页读取的 AppBuildInfo 与版本源一致`() {
        assertEquals(versionName, AppBuildInfo.VERSION_NAME)
        assertEquals(versionCode.toIntOrNull(), AppBuildInfo.VERSION_CODE)
        assertTrue(AppBuildInfo.APP_NAME.isNotBlank())
        assertTrue(AppBuildInfo.BUILD_CHANNEL.isNotBlank())
    }

    @Test
    fun `依赖许可清单由构建生成且只含真实依赖`() {
        val entries = AppDependencyInfo.entries
        assertTrue(entries.size >= 5, "依赖许可清单过少：${entries.size}")
        assertTrue(entries.all { it.name.isNotBlank() })
        assertTrue(entries.all { it.version.isNotBlank() })
        assertTrue(entries.all { it.license.isNotBlank() })
        assertTrue(entries.all { it.scope in setOf("runtime", "android", "test", "build") })
        val names = entries.map { it.name }
        assertEquals(names.size, names.distinct().size, "依赖名称不应重复")
        assertTrue(entries.any { it.name == "Kotlin" })
        assertTrue(entries.any { it.scope == "runtime" })
    }

    @Test
    fun `iOS xcconfig 与 gradle properties 一致`() {
        val config = File(root, "iosApp/iosApp/Version.xcconfig").readText()
        fun value(key: String): String? =
            Regex("""^$key\s*=\s*(\S+)""", RegexOption.MULTILINE).find(config)?.groupValues?.get(1)

        assertEquals(versionName, value("MARKETING_VERSION"))
        assertEquals(versionCode, value("CURRENT_PROJECT_VERSION"))
    }

    @Test
    fun `Info plist 引用 xcconfig 的版本变量而非硬编码`() {
        val plist = File(root, "iosApp/iosApp/Info.plist").readText()
        assertTrue(plist.contains("<string>\$(MARKETING_VERSION)</string>"))
        assertTrue(plist.contains("<string>\$(CURRENT_PROJECT_VERSION)</string>"))
        assertFalse(plist.contains("<string>2.6.0</string>"), "Info.plist 不应硬编码版本号")
    }

    @Test
    fun `Xcode 工程通过 baseConfigurationReference 接入 Version xcconfig`() {
        val project = File(root, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        assertTrue(project.contains("Version.xcconfig"), "Xcode 工程应引用 Version.xcconfig")
        assertTrue(
            project.lines().count { it.contains("baseConfigurationReference") && it.contains("Version.xcconfig") } >= 2,
            "Debug/Release 两套 target 配置都应接入 Version.xcconfig",
        )
    }

    @Test
    fun `Android 构建从同一版本键读取`() {
        val script = File(root, "composeApp/build.gradle.kts").readText()
        assertTrue(script.contains("gradleProperty(\"valhalla.versionName\")"))
        assertTrue(script.contains("gradleProperty(\"valhalla.versionCode\")"))
        assertTrue(script.contains("versionName = appVersionName"))
        assertTrue(script.contains("versionCode = appVersionCode"))
        assertFalse(script.contains("versionName = \"2.6.0\""), "Android 不应硬编码版本号")
    }
}
