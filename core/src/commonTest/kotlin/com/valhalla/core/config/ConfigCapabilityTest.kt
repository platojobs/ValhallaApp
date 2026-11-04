package com.valhalla.core.config

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigCapabilityTest {

    private fun version(major: Int, minor: Int, patch: Int = 0, build: Int = 0) =
        FirmwareVersion(major, minor, patch, build, "stable")

    private fun spec(key: String): ConfigKeySpec = assertNotNull(ConfigCatalog.find(key))

    @Test
    fun `通用键在未知设备下仍可用`() {
        val result = ConfigCatalog.capability(spec("device_name"), DeviceConfigTarget())

        assertEquals(ConfigCapabilityStatus.SUPPORTED, result.status)
        assertTrue(result.supported)
        assertTrue(result.visible)
        assertEquals(null, result.reason)
    }

    @Test
    fun `固件未知时受限键隐藏并提示刷新设备信息`() {
        val result = ConfigCatalog.capability(spec("led_enabled"), DeviceConfigTarget())

        assertEquals(ConfigCapabilityStatus.TARGET_UNKNOWN, result.status)
        assertFalse(result.supported)
        assertFalse(result.visible)
        assertTrue(result.reason!!.contains("刷新设备信息"))
    }

    @Test
    fun `版本低于下限时隐藏并给出最低要求`() {
        val result = ConfigCatalog.capability(
            spec("led_enabled"),
            DeviceConfigTarget(firmwareVersion = version(0, 9, 9, 7), model = "VH-1000"),
        )

        assertEquals(ConfigCapabilityStatus.VERSION_TOO_OLD, result.status)
        assertFalse(result.visible)
        assertTrue(result.reason!!.contains("1.0.0"))
        assertTrue(result.reason!!.contains("build 0"))
    }

    @Test
    fun `版本矩阵按锚点逐级放行`() {
        val targetV12 = DeviceConfigTarget(firmwareVersion = version(1, 2, 0), model = "VH-1000")

        assertTrue(ConfigCatalog.capability(spec("led_enabled"), targetV12).supported)
        assertTrue(ConfigCatalog.capability(spec("sample_interval_ms"), targetV12).supported)
        assertEquals(
            ConfigCapabilityStatus.VERSION_TOO_OLD,
            ConfigCatalog.capability(spec("tx_power_dbm"), targetV12).status,
        )

        val targetV13 = DeviceConfigTarget(firmwareVersion = version(1, 3, 0), model = "VH-1000")
        assertTrue(ConfigCatalog.capability(spec("tx_power_dbm"), targetV13).supported)
    }

    @Test
    fun `型号未知或不在白名单时隐藏发射功率`() {
        val firmware = version(1, 3, 0)

        val unknownModel = ConfigCatalog.capability(
            spec("tx_power_dbm"),
            DeviceConfigTarget(firmwareVersion = firmware, model = null),
        )
        assertEquals(ConfigCapabilityStatus.TARGET_UNKNOWN, unknownModel.status)
        assertFalse(unknownModel.visible)

        val unsupported = ConfigCatalog.capability(
            spec("tx_power_dbm"),
            DeviceConfigTarget(firmwareVersion = firmware, model = "VH-3000"),
        )
        assertEquals(ConfigCapabilityStatus.MODEL_UNSUPPORTED, unsupported.status)
        assertTrue(unsupported.reason!!.contains("VH-1000/VH-2000"))

        val supported = ConfigCatalog.capability(
            spec("tx_power_dbm"),
            DeviceConfigTarget(firmwareVersion = firmware, model = " vh-2000 "),
        )
        assertTrue(supported.supported, "型号比较应忽略大小写与首尾空白")
    }

    @Test
    fun `版本高于上限时返回过高状态`() {
        val bounded = ConfigKeySpec(
            key = "bounded",
            displayName = "受上限约束",
            type = ConfigValueType.BOOLEAN,
            editable = true,
            scope = ConfigScope(minVersion = version(1, 0, 0), maxVersion = version(1, 2, 0)),
        )

        val result = ConfigCatalog.capability(
            bounded,
            DeviceConfigTarget(firmwareVersion = version(1, 3, 0)),
        )

        assertEquals(ConfigCapabilityStatus.VERSION_TOO_NEW, result.status)
        assertFalse(result.visible)
        assertTrue(result.reason!!.contains("1.2.0"))
    }

    @Test
    fun `visibleKeys 保持命令表顺序并过滤不支持项`() {
        val visible = ConfigCatalog.visibleKeys(
            DeviceConfigTarget(firmwareVersion = version(1, 0, 0), model = "VH-1000"),
        )

        assertEquals(
            listOf("device_name", "led_enabled", "serial_number", "hardware_version"),
            visible.map { it.key },
        )
    }

    @Test
    fun `渠道名不参与版本比较`() {
        val beta = DeviceConfigTarget(
            firmwareVersion = FirmwareVersion(1, 2, 0, 0, "beta"),
            model = "VH-1000",
        )
        val stable = DeviceConfigTarget(
            firmwareVersion = FirmwareVersion(1, 2, 0, 0, "stable"),
            model = "VH-1000",
        )

        assertEquals(
            ConfigCatalog.capability(spec("sample_interval_ms"), beta).status,
            ConfigCatalog.capability(spec("sample_interval_ms"), stable).status,
        )
    }
}
