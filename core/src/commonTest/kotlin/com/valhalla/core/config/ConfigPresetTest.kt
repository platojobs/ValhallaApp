package com.valhalla.core.config

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigPresetTest {

    private val capableTarget = DeviceConfigTarget(
        firmwareVersion = FirmwareVersion(1, 3, 0, 0, "stable"),
        model = "VH-1000",
    )

    private val oldTarget = DeviceConfigTarget(
        firmwareVersion = FirmwareVersion(1, 2, 0, 0, "stable"),
        model = "VH-1000",
    )

    @Test
    fun `编码解码保留中文名称与特殊值`() {
        val presets = listOf(
            ConfigPreset(
                id = "preset-1",
                name = "日常 | 省电",
                values = mapOf("device_name" to "Valhalla 100%", "led_enabled" to "false"),
                createdAtMillis = 1,
                lastUsedAtMillis = 2,
            ),
        )
        val encoded = ConfigPresetCodec.encode(presets)
        val decoded = ConfigPresetCodec.decode(encoded)
        assertEquals(presets, decoded)
    }

    @Test
    fun `损坏数据回退为空且不抛异常`() {
        val store = InMemoryConfigPresetStore("not-a-valid-line")
        val repository = ConfigPresetRepository(store, clockMillis = { 10 })
        assertTrue(repository.list().isEmpty())
        val saved = repository.saveFromValues("日常", mapOf("device_name" to "A"))
        assertNotNull(saved)
        assertEquals("日常", repository.list().single().name)
    }

    @Test
    fun `重命名删除复制与最近使用排序`() {
        var now = 1_000L
        val repository = ConfigPresetRepository(InMemoryConfigPresetStore(), clockMillis = { now })
        val daily = repository.saveFromValues("日常", mapOf("device_name" to "A"))!!
        now = 2_000L
        val demo = repository.saveFromValues("演示", mapOf("led_enabled" to "true"))!!
        now = 3_000L
        assertTrue(repository.markUsed(daily.id))
        assertEquals(listOf("日常", "演示"), repository.list().map { it.name })

        assertTrue(repository.rename(demo.id, "演示模式"))
        assertEquals("演示模式", repository.find(demo.id)?.name)
        val copy = repository.duplicate(daily.id)
        assertNotNull(copy)
        assertEquals("日常 Copy", copy.name)
        assertTrue(repository.delete(demo.id))
        assertNull(repository.find(demo.id))
        assertEquals(2, repository.list().size)
    }

    @Test
    fun `差异区分将修改已是目标与不支持`() {
        val preset = ConfigPreset(
            id = "p1",
            name = "日常",
            values = mapOf(
                "device_name" to "Valhalla",
                "led_enabled" to "true",
                "tx_power_dbm" to "3",
                "serial_number" to "SN-001",
                "unknown_key" to "x",
            ),
            createdAtMillis = 0,
            lastUsedAtMillis = 0,
        )
        val diffs = ConfigPresetDiffer.diff(
            preset = preset,
            target = oldTarget,
            currentValues = mapOf(
                "device_name" to "Valhalla",
                "led_enabled" to "false",
                "sample_interval_ms" to "1500",
                "serial_number" to "SN-001",
                "unknown_key" to "x",
            ),
        ).associateBy { it.key }

        assertEquals(PresetFieldStatus.ALREADY_TARGET, diffs.getValue("device_name").status)
        assertEquals(PresetFieldStatus.WILL_CHANGE, diffs.getValue("led_enabled").status)
        assertEquals(PresetFieldStatus.UNSUPPORTED, diffs.getValue("tx_power_dbm").status)
        assertEquals(PresetFieldStatus.UNSUPPORTED, diffs.getValue("serial_number").status)
        assertEquals(PresetFieldStatus.UNSUPPORTED, diffs.getValue("unknown_key").status)
        assertNotNull(diffs.getValue("tx_power_dbm").reason)
    }

    @Test
    fun `非法预设值判为不支持`() {
        val preset = ConfigPreset(
            id = "p2",
            name = "越界",
            values = mapOf("sample_interval_ms" to "999999"),
            createdAtMillis = 0,
            lastUsedAtMillis = 0,
        )
        val diff = ConfigPresetDiffer.diff(preset, oldTarget, emptyMap(), ConfigCatalog).single()
        assertEquals(PresetFieldStatus.UNSUPPORTED, diff.status)
        assertFalse(diff.reason.isNullOrBlank())
    }

    @Test
    fun `能力满足时按规范化值比较`() {
        val preset = ConfigPreset(
            id = "p3",
            name = "布尔",
            values = mapOf("led_enabled" to "1"),
            createdAtMillis = 0,
            lastUsedAtMillis = 0,
        )
        val diff = ConfigPresetDiffer.diff(
            preset,
            capableTarget,
            mapOf("led_enabled" to "true"),
            ConfigCatalog,
        ).single()
        assertEquals(PresetFieldStatus.ALREADY_TARGET, diff.status)
        assertEquals("true", diff.targetValue)
    }
}
