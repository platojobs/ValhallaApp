package com.valhalla.feature.firmware

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareLibraryRepository
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.InMemoryFirmwareLibraryStore
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirmwareLibraryImporterTest {

    private val payload = ByteArray(300) { (it * 7 % 251).toByte() }
    private val version = FirmwareVersion(major = 1, minor = 2, patch = 0, build = 0, channel = "release")

    private fun packageBytes(crc32: Long = Crc32.compute(payload)): ByteArray =
        FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = crc32,
            hasSignature = false,
        ).encode() + payload

    private class FakePicker(
        private val name: String,
        private val bytes: ByteArray,
        private val path: String = "content://firmware/$name",
    ) : FirmwarePicker {
        override suspend fun pickFirmware(): PickedFirmware =
            PickedFirmware(name, FirmwareSource.memory(bytes), path)
    }

    @Test
    fun `有效包入库且重复导入被识别`() = runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val importer = FirmwareLibraryImporter(
            FakePicker("fw.bin", packageBytes()),
            repo,
        )

        val first = importer.importOne()
        assertTrue(first is FirmwareImportOutcome.Imported)
        assertEquals(1, repo.count())

        val second = importer.importOne()
        assertTrue(second is FirmwareImportOutcome.Duplicate)
        assertEquals(1, repo.count())
    }

    @Test
    fun `CRC 不符拒绝入库并给出具体错误码`() = runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val importer = FirmwareLibraryImporter(
            FakePicker("corrupt.bin", packageBytes(crc32 = 0x1)),
            repo,
        )

        val outcome = importer.importOne()
        assertTrue(outcome is FirmwareImportOutcome.Rejected)
        assertEquals("firmware.payload_crc", (outcome as FirmwareImportOutcome.Rejected).code)
        assertEquals(0, repo.count())
    }

    @Test
    fun `头部损坏拒绝入库且不入库`() = runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val importer = FirmwareLibraryImporter(
            FakePicker("garbage.bin", ByteArray(64) { 0x7F }),
            repo,
        )

        val outcome = importer.importOne()
        assertTrue(outcome is FirmwareImportOutcome.Rejected)
        assertTrue((outcome as FirmwareImportOutcome.Rejected).code.isNotBlank())
        assertEquals(0, repo.count())
    }

    @Test
    fun `用户取消不产生条目`() = runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val importer = FirmwareLibraryImporter(
            object : FirmwarePicker {
                override suspend fun pickFirmware(): PickedFirmware? = null
            },
            repo,
        )

        assertEquals(FirmwareImportOutcome.Cancelled, importer.importOne())
        assertEquals(0, repo.count())
    }
}
