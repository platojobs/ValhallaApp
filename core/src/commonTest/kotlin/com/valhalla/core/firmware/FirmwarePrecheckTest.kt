package com.valhalla.core.firmware

import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwarePrecheckTest {
    private val target = FirmwareVersion(2, 0, 0, 5, "release")

    @Test
    fun `版本较新且型号匹配时预检通过`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(256) { it.toByte() })
            val result =
                FirmwarePrechecker().check(
                    firmware,
                    currentVersion = FirmwareVersion(1, 9, 9, 9, "release"),
                    currentModel = "VH-100",
                )

            assertTrue(result.passed)
            assertEquals(firmware.payloadCrc32, result.payloadCrc32)
            assertEquals(Crc16.ccittFalse(firmware.readPayload(0, firmware.payloadLength.toInt())), result.payloadCrc16)
            assertEquals("预检通过", result.summary())
        }

    @Test
    fun `相同版本允许重刷`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(64) { it.toByte() })
            val result = FirmwarePrechecker().check(firmware, target.copy(build = 5), "VH-100")
            assertTrue(result.passed)
        }

    @Test
    fun `通配型号匹配任意设备`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(64), models = listOf("*"))
            val result = FirmwarePrechecker().check(firmware, target, "UNKNOWN-MODEL")
            assertTrue(result.passed)
        }

    @Test
    fun `型号不匹配时预检失败`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(64), models = listOf("VH-100"))
            val result = FirmwarePrechecker().check(firmware, target, "VH-200")

            assertFalse(result.passed)
            assertIs<FirmwareException.HardwareModelMismatch>(result.primaryError)
        }

    @Test
    fun `设备型号未知时预检失败`() =
        runTest {
            val result = FirmwarePrechecker().check(buildPackage(payload = ByteArray(64)), target, null)
            assertFalse(result.passed)
            assertIs<FirmwareException.HardwareUnknown>(result.primaryError)
        }

    @Test
    fun `设备版本未知时预检失败`() =
        runTest {
            val result = FirmwarePrechecker().check(buildPackage(payload = ByteArray(64)), null, "VH-100")
            assertFalse(result.passed)
            assertIs<FirmwareException.VersionUnknown>(result.primaryError)
        }

    @Test
    fun `固件版本低于设备当前版本时判定回退`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(64), version = FirmwareVersion(1, 0, 0, 0, "release"))
            val result = FirmwarePrechecker().check(firmware, FirmwareVersion(2, 0, 0, 0, "release"), "VH-100")

            assertFalse(result.passed)
            assertIs<FirmwareException.VersionRollback>(result.primaryError)
        }

    @Test
    fun `payload CRC32 不一致时预检失败`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(64) { it.toByte() }, crcDelta = 1)
            val result = FirmwarePrechecker().check(firmware, target, "VH-100")

            assertFalse(result.passed)
            assertIs<FirmwareException.PayloadChecksumMismatch>(result.primaryError)
        }

    @Test
    fun `payload 超过上限时预检失败且跳过 CRC 计算`() =
        runTest {
            val firmware = buildPackage(payload = ByteArray(128))
            val result = FirmwarePrechecker(maxPayloadBytes = 64).check(firmware, target, "VH-100")

            assertFalse(result.passed)
            assertIs<FirmwareException.PayloadTooLarge>(result.primaryError)
            assertNull(result.payloadCrc32)
        }

    @Test
    fun `多项问题会一次性收集`() =
        runTest {
            val firmware =
                buildPackage(
                    payload = ByteArray(128) { it.toByte() },
                    version = FirmwareVersion(1, 0, 0, 0, "release"),
                    models = listOf("VH-100"),
                    crcDelta = 1,
                )
            val result =
                FirmwarePrechecker(maxPayloadBytes = 64).check(
                    firmware,
                    currentVersion = FirmwareVersion(2, 0, 0, 0, "release"),
                    currentModel = "VH-200",
                )

            assertFalse(result.passed)
            assertEquals(3, result.errors.size)
            assertTrue(result.errors.any { it is FirmwareException.PayloadTooLarge })
            assertTrue(result.errors.any { it is FirmwareException.HardwareModelMismatch })
            assertTrue(result.errors.any { it is FirmwareException.VersionRollback })
        }

    @Test
    fun `版本比较按 major minor patch build 顺序`() {
        val base = FirmwareVersion(1, 2, 3, 4, "release")
        assertEquals(0, FirmwarePrechecker.compare(base, base))
        assertTrue(FirmwarePrechecker.compare(base.copy(major = 2), base) > 0)
        assertTrue(FirmwarePrechecker.compare(base.copy(build = 5), base) > 0)
        assertTrue(FirmwarePrechecker.compare(base.copy(patch = 2), base) < 0)
        assertEquals(
            0,
            FirmwarePrechecker.compare(base, base.copy(channel = "beta")),
        )
    }

    private suspend fun buildPackage(
        payload: ByteArray,
        version: FirmwareVersion = target,
        models: List<String> = listOf("VH-100"),
        crcDelta: Long = 0,
    ): FirmwarePackage {
        val header =
            FirmwareHeader(
                firmwareVersion = version,
                targetModels = models,
                payloadLength = payload.size.toLong(),
                payloadCrc32 = (Crc32.compute(payload) + crcDelta) and 0xFFFFFFFFL,
            ).encode()
        return FirmwareParser.parse("test.bin", FirmwareSource.memory(header + payload))
    }
}
