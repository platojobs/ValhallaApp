package com.valhalla.feature.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringsCompletenessTest {
    private val placeholder = Regex("\\{([a-zA-Z0-9_]+)}")
    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `枚举与登记表完全一致`() {
        assertEquals(StringKey.entries.toSet(), AppStrings.registeredKeys())
    }

    @Test
    fun `没有空文案`() {
        StringKey.entries.forEach { key ->
            val entry = AppStrings.entry(key)!!
            assertTrue(entry.zh.isNotBlank(), "中文为空：$key")
            assertTrue(entry.en.isNotBlank(), "英文为空：$key")
        }
    }

    @Test
    fun `中英占位符集合一致`() {
        StringKey.entries.forEach { key ->
            val entry = AppStrings.entry(key)!!
            val zh = placeholder.findAll(entry.zh).map { it.groupValues[1] }.toSet()
            val en = placeholder.findAll(entry.en).map { it.groupValues[1] }.toSet()
            assertEquals(zh, en, "占位符不一致：$key")
        }
    }

    @Test
    fun `英文文案不含中文`() {
        StringKey.entries
            .filter { it != StringKey.LANGUAGE_CHINESE } // 语言选项按惯例显示「中文」
            .forEach { key ->
                val en = AppStrings.entry(key)!!.en
                assertFalse(cjk.containsMatchIn(en), "英文含中文：$key -> $en")
            }
    }

    @Test
    fun `提供参数后不残留占位符`() {
        StringKey.entries.forEach { key ->
            val entry = AppStrings.entry(key)!!
            val names = placeholder.findAll(entry.zh).map { it.groupValues[1] }.toSet()
            if (names.isEmpty()) return@forEach
            val args = names.associateWith { "X" }
            val zh = AppStrings.format(ResolvedLanguage.CHINESE, key, args)
            val en = AppStrings.format(ResolvedLanguage.ENGLISH, key, args)
            assertFalse(placeholder.containsMatchIn(zh), "中文残留占位符：$key -> $zh")
            assertFalse(placeholder.containsMatchIn(en), "英文残留占位符：$key -> $en")
        }
    }

    @Test
    fun `中文文案都有对应英文翻译`() {
        // 语言名称、品牌名等按惯例保留原文；其余含中文的条目必须提供英文。
        val allowSameText = setOf(StringKey.APP_NAME, StringKey.LANGUAGE_CHINESE)
        StringKey.entries
            .filterNot { it in allowSameText }
            .forEach { key ->
                val entry = AppStrings.entry(key)!!
                if (cjk.containsMatchIn(entry.zh)) {
                    assertNotEquals(entry.zh, entry.en, "疑似未翻译：$key")
                }
            }
    }

    @Test
    fun `错误类文案包含可操作建议`() {
        val actionable = listOf("请", "重试", "确认", "重新")
        StringKey.entries
            .filter {
                (it.name.endsWith("_FAILED") && it != StringKey.OTA_OUTCOME_FAILED) ||
                    it.name.startsWith("OTA_REASON")
            }.forEach { key ->
                val zh = AppStrings.get(ResolvedLanguage.CHINESE, key)
                assertTrue(
                    actionable.any { zh.contains(it) },
                    "错误文案缺少可操作建议：$key -> $zh",
                )
            }
    }

    @Test
    fun `语言解析遵循系统标签与手动覆盖`() {
        assertEquals(ResolvedLanguage.CHINESE, resolveLanguage(AppLanguageMode.SYSTEM, "zh-Hans-CN"))
        assertEquals(ResolvedLanguage.CHINESE, resolveLanguage(AppLanguageMode.SYSTEM, "ZH"))
        assertEquals(ResolvedLanguage.ENGLISH, resolveLanguage(AppLanguageMode.SYSTEM, "en-US"))
        assertEquals(ResolvedLanguage.ENGLISH, resolveLanguage(AppLanguageMode.SYSTEM, "ja-JP"))
        assertEquals(ResolvedLanguage.ENGLISH, resolveLanguage(AppLanguageMode.SYSTEM, null))
        assertEquals(ResolvedLanguage.CHINESE, resolveLanguage(AppLanguageMode.CHINESE, "en-US"))
        assertEquals(ResolvedLanguage.ENGLISH, resolveLanguage(AppLanguageMode.ENGLISH, "zh-CN"))
    }

    @Test
    fun `嵌套消息会先本地化再填充`() {
        val message =
            UiMessage(
                StringKey.ERROR_PREFIX,
                nested = mapOf("detail" to UiMessage.of(StringKey.DASH)),
            )
        assertEquals("错误：—", StringResolver(ResolvedLanguage.CHINESE).render(message))
        assertEquals("Error: —", StringResolver(ResolvedLanguage.ENGLISH).render(message))
    }
}
