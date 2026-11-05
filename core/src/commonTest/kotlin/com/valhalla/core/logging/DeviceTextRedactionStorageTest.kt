package com.valhalla.core.logging

import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.profile.DeviceProfileCodec
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 设备自由文本入库脱敏测试：日志缓冲与设备档案。 */
class DeviceTextRedactionStorageTest {

    @Test
    fun `设备日志入库前脱敏`() {
        val logger = InMemoryDeviceLogger()
        logger.log(
            level = LogLevel.INFO,
            tag = "device AA:BB:CC:DD:EE:FF",
            message = "回传文本 token=secret-token serial=SN-001 firmware 1.2.0",
            timestampMillis = 1000,
        )

        val entry = logger.entries().single()
        val text = entry.format()
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("SN-001"))
        assertTrue(text.contains("token=***"))
        assertTrue(entry.message.contains("firmware 1.2.0"), "普通版本号应保留")
    }

    @Test
    fun `设备档案编码后不包含自由文本中的敏感串`() {
        val profile = DeviceProfile(
            deviceId = "dev-1",
            displayName = "设备 token=abc",
            vendor = "Valhalla",
            model = "VH-100 serialNumber=SN-999",
            lastConnectedAtEpochMillis = 1000,
            lastKnownFirmwareVersion = "1.2.0-credential=secret",
            lastKnownBatteryPercent = 80,
            lastKnownCharging = false,
            otaHistory = listOf(
                OtaHistoryRecord(
                    firmwareVersion = "1.2.0",
                    finishedAtEpochMillis = 2000,
                    durationMillis = 300,
                    outcome = OtaOutcome.FAILED,
                    errorCode = "E_TOKEN token=leak",
                    detail = "signature=l3ak",
                    sentBytes = 10,
                    totalBytes = 20,
                ),
            ),
        )

        val encoded = DeviceProfileCodec.encode(listOf(profile))
        assertFalse(encoded.contains("token=abc"))
        assertFalse(encoded.contains("SN-999"))
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("leak"))
        assertFalse(encoded.contains("l3ak"))
        // 主键必须保持可用，否则档案无法匹配。
        assertTrue(encoded.contains("dev-1"))

        val decoded = DeviceProfileCodec.decode(encoded).single()
        assertEquals("dev-1", decoded.deviceId)
        assertTrue(decoded.displayName.orEmpty().contains("token=***"))
    }
}
