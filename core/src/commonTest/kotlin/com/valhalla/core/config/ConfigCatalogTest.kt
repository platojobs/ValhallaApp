package com.valhalla.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigCatalogTest {

    @Test
    fun `字符串按字节长度校验并拒绝空值`() {
        val spec = ConfigCatalog.find("device_name")!!
        assertTrue(ConfigCatalog.validate(spec, "手柄") is ConfigValidation.Valid)
        val invalid = ConfigCatalog.validate(spec, "x".repeat(33))
        assertTrue(invalid is ConfigValidation.Invalid)
        assertEquals("长度 33 字节超过上限 32 字节", (invalid as ConfigValidation.Invalid).message)
        assertTrue(ConfigCatalog.validate(spec, "   ") is ConfigValidation.Invalid)
    }

    @Test
    fun `布尔值接受常见写法并规范化`() {
        val spec = ConfigCatalog.find("led_enabled")!!
        val on = ConfigCatalog.validate(spec, " ON ") as ConfigValidation.Valid
        assertEquals("true", on.canonicalText)
        assertEquals(1, on.bytes.single().toInt())

        val off = ConfigCatalog.validate(spec, "0") as ConfigValidation.Valid
        assertEquals("false", off.canonicalText)
        assertEquals(0, off.bytes.single().toInt())

        assertTrue(ConfigCatalog.validate(spec, "maybe") is ConfigValidation.Invalid)
    }

    @Test
    fun `整数必须落在命令表区间`() {
        val interval = ConfigCatalog.find("sample_interval_ms")!!
        assertTrue(ConfigCatalog.validate(interval, "100") is ConfigValidation.Valid)
        assertTrue(ConfigCatalog.validate(interval, "60000") is ConfigValidation.Valid)
        assertTrue(ConfigCatalog.validate(interval, "99") is ConfigValidation.Invalid)
        assertTrue(ConfigCatalog.validate(interval, "abc") is ConfigValidation.Invalid)
        assertTrue(ConfigCatalog.validate(interval, "") is ConfigValidation.Invalid)
    }

    @Test
    fun `未知键按只读处理且不能保存`() {
        val unknown = ConfigCatalog.findOrReadOnly("mystery_key")
        assertFalse(unknown.editable)
        assertEquals("未在命令表定义，仅只读", unknown.readOnlyReason)
        val result = ConfigCatalog.validate(unknown, "value")
        assertTrue(result is ConfigValidation.Invalid)
    }

    @Test
    fun `设备原始值按类型格式化`() {
        val text = ConfigCatalog.find("device_name")!!
        assertEquals("Valhalla", ConfigCatalog.format(text, "Valhalla".encodeToByteArray()))

        val flag = ConfigCatalog.find("led_enabled")!!
        assertEquals("true", ConfigCatalog.format(flag, byteArrayOf(1)))
        assertEquals("false", ConfigCatalog.format(flag, byteArrayOf(0)))
        assertEquals("false", ConfigCatalog.format(flag, "false".encodeToByteArray()))

        val interval = ConfigCatalog.find("sample_interval_ms")!!
        assertEquals("1500", ConfigCatalog.format(interval, "1500".encodeToByteArray()))
    }

    @Test
    fun `命令表可编辑键覆盖预期集合`() {
        val editable = ConfigCatalog.keys.filter { it.editable }.map { it.key }.toSet()
        assertEquals(setOf("device_name", "led_enabled", "sample_interval_ms", "tx_power_dbm"), editable)
    }
}
