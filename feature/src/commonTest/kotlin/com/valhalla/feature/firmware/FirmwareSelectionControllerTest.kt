package com.valhalla.feature.firmware

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareSelectionControllerTest {

    private val currentVersion = FirmwareVersion(1, 0, 0, 1, "release")
    private val targetVersion = FirmwareVersion(1, 1, 0, 0, "release")
    private val payload = ByteArray(300) { index -> (index % 251).toByte() }

    private class FakePicker(var result: PickedFirmware?) : FirmwarePicker {
        var calls: Int = 0
        override suspend fun pickFirmware(): PickedFirmware? {
            calls++
            return result
        }
    }

    private class TrackingSource(private val bytes: ByteArray) : FirmwareSource {
        var closed: Boolean = false
        override val size: Long get() = bytes.size.toLong()
        override suspend fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `选择合法固件后预检通过`() = runTest {
        val source = TrackingSource(packageBytes(payload, targetVersion, declaredCrc = Crc32.compute(payload)))
        val picker = FakePicker(PickedFirmware("firmware.bin", source))
        val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())

        val passed = controller.pickAndPrecheck(currentVersion, "VH-100")
        val state = controller.state.value

        assertTrue(passed)
        assertTrue(state.readyForOta)
        assertEquals("firmware.bin", state.packageName)
        assertEquals(targetVersion, state.version)
        assertEquals(300L, state.payloadBytes)
        assertEquals(Crc32.compute(payload), state.computedCrc32)
        assertNotNull(controller.firmware)
        controller.close()
        assertTrue(source.closed)
    }

    @Test
    fun `预检失败时不可启动 OTA`() = runTest {
        val source = TrackingSource(packageBytes(payload, FirmwareVersion(0, 9, 0, 0, "release"), Crc32.compute(payload)))
        val picker = FakePicker(PickedFirmware("old.bin", source))
        val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())

        val passed = controller.pickAndPrecheck(currentVersion, "VH-200")
        val state = controller.state.value

        assertFalse(passed)
        assertFalse(state.readyForOta)
        assertEquals(2, state.precheck?.errors?.size)
        assertTrue(state.precheck!!.errors.any { it is FirmwareException.VersionRollback })
        assertTrue(state.precheck!!.errors.any { it is FirmwareException.HardwareModelMismatch })
    }

    @Test
    fun `取消选择保留上一次固件`() = runTest {
        val firstSource = TrackingSource(packageBytes(payload, targetVersion, Crc32.compute(payload)))
        val picker = FakePicker(PickedFirmware("first.bin", firstSource))
        val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())

        controller.pickAndPrecheck(currentVersion, "VH-100")
        picker.result = null
        val passed = controller.pickAndPrecheck(currentVersion, "VH-100")

        assertFalse(passed)
        assertEquals("first.bin", controller.state.value.packageName)
        assertFalse(firstSource.closed)
        controller.close()
        assertTrue(firstSource.closed)
    }

    @Test
    fun `文件头损坏时给出明确错误码`() = runTest {
        val broken = packageBytes(payload, targetVersion, Crc32.compute(payload)).copyOf()
        broken[0] = 0x00
        val picker = FakePicker(PickedFirmware("broken.bin", TrackingSource(broken)))
        val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())

        val passed = controller.pickAndPrecheck(currentVersion, "VH-100")
        val state = controller.state.value

        assertFalse(passed)
        assertNull(state.precheck)
        assertEquals("firmware.magic", state.errorCode)
        assertTrue(state.error!!.contains("魔数"))
        assertNull(controller.firmware)
    }

    @Test
    fun `匹配的断点会加载并可清除`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val point = resumePoint()
        store.save(point)
        val picker = FakePicker(
            PickedFirmware("firmware.bin", TrackingSource(packageBytes(payload, targetVersion, Crc32.compute(payload)))),
        )
        val controller = FirmwareSelectionController(picker, store)
        controller.refreshResume()

        controller.pickAndPrecheck(currentVersion, "VH-100")

        val state = controller.state.value
        assertTrue(state.canResume)
        assertEquals(point, state.resumePoint)
        assertEquals(point, controller.resumePointForCurrentFirmware())

        controller.clearResume()
        assertNull(controller.state.value.resumePoint)
        assertNull(store.load())
    }

    @Test
    fun `固件身份不匹配时不加载断点`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(totalBytes = 299))
        val picker = FakePicker(
            PickedFirmware("firmware.bin", TrackingSource(packageBytes(payload, targetVersion, Crc32.compute(payload)))),
        )
        val controller = FirmwareSelectionController(picker, store)

        controller.pickAndPrecheck(currentVersion, "VH-100")

        assertFalse(controller.state.value.canResume)
        assertNull(controller.state.value.resumePoint)
        assertNotNull(store.load())
    }

    @Test
    fun `重新预检在设备信息变化后更新结果`() = runTest {
        val picker = FakePicker(
            PickedFirmware("firmware.bin", TrackingSource(packageBytes(payload, targetVersion, Crc32.compute(payload)))),
        )
        val controller = FirmwareSelectionController(picker, InMemoryOtaCheckpointStore())
        controller.pickAndPrecheck(currentVersion, "VH-100")
        assertTrue(controller.state.value.readyForOta)

        val passed = controller.recheck(currentVersion, "OTHER")

        assertFalse(passed)
        assertFalse(controller.state.value.readyForOta)
    }

    private fun packageBytes(
        body: ByteArray,
        version: FirmwareVersion,
        declaredCrc: Long,
    ): ByteArray = FirmwareHeader(
        firmwareVersion = version,
        targetModels = listOf("VH-100"),
        payloadLength = body.size.toLong(),
        payloadCrc32 = declaredCrc,
    ).encode() + body

    private fun resumePoint(totalBytes: Int = payload.size): OtaResumePoint = OtaResumePoint(
        sessionId = 42,
        sentBytes = 128,
        totalBytes = totalBytes,
        crc16 = Crc16.ccittFalse(payload),
        firmwareVersion = targetVersion,
        crc32 = Crc32.compute(payload),
    )
}
