package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirmwareParserTest {

    private val version = FirmwareVersion(1, 0, 0, 1, "release")

    @Test
    fun `合法固件包解析并可分块读取`() = runTest {
        val payload = ByteArray(300) { index -> (index % 251).toByte() }
        val packageFile = buildPackage(payload)

        val parsed = FirmwareParser.parse("demo.bin", FirmwareSource.memory(packageFile))

        assertEquals("demo.bin", parsed.name)
        assertEquals(version, parsed.version)
        assertEquals(300L, parsed.payloadLength)
        assertEquals(Crc32.compute(payload), parsed.payloadCrc32)

        assertEquals(payload.copyOfRange(0, 64).toList(), parsed.readPayload(0, 64).toList())
        assertEquals(payload.copyOfRange(256, 300).toList(), parsed.readPayload(256, 44).toList())
    }

    @Test
    fun `文件不足文件头长度被拒绝`() = runTest {
        val error = assertFailsWith<FirmwareException.Truncated> {
            FirmwareParser.parse("short.bin", FirmwareSource.memory(ByteArray(20)))
        }
        assertEquals(FirmwareHeader.SIZE.toLong(), error.expectedBytes)
    }

    @Test
    fun `头声明长度与实际 payload 不符被拒绝`() = runTest {
        val header = FirmwareHeader(version, listOf("VH-100"), payloadLength = 10, payloadCrc32 = 1).encode()
        val source = FirmwareSource.memory(header + ByteArray(5))

        val error = assertFailsWith<FirmwareException.PayloadSizeMismatch> {
            FirmwareParser.parse("bad.bin", source)
        }
        assertEquals(10L, error.declaredBytes)
        assertEquals(5L, error.actualBytes)
    }

    @Test
    fun `payload 读取越界抛出明确错误`() = runTest {
        val parsed = FirmwareParser.parse("demo.bin", FirmwareSource.memory(buildPackage(ByteArray(100))))

        assertFailsWith<FirmwareException.ReadFailure> { parsed.readPayload(90, 20) }
        assertFailsWith<FirmwareException.ReadFailure> { parsed.readPayload(-1, 10) }
    }

    @Test
    fun `不同分块大小计算出的 CRC32 一致`() = runTest {
        val payload = ByteArray(1000) { index -> (index / 3).toByte() }
        val parsed = FirmwareParser.parse("demo.bin", FirmwareSource.memory(buildPackage(payload)))

        val expected = Crc32.compute(payload)
        assertEquals(expected, parsed.computePayloadCrc32(chunkSize = 1))
        assertEquals(expected, parsed.computePayloadCrc32(chunkSize = 17))
        assertEquals(expected, parsed.computePayloadCrc32(chunkSize = 64 * 1024))
    }

    @Test
    fun `内存固件源越界读取拒绝`() = runTest {
        val source = FirmwareSource.memory(ByteArray(8))
        assertEquals(8L, source.size)
        assertFailsWith<FirmwareException.ReadFailure> { source.read(-1, 1) }
        assertFailsWith<FirmwareException.ReadFailure> { source.read(8, 1) }
        assertFailsWith<FirmwareException.ReadFailure> { source.read(4, 5) }
    }

    private fun buildPackage(payload: ByteArray): ByteArray {
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = listOf("VH-100"),
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
        ).encode()
        return header + payload
    }
}
