package com.valhalla.core.firmware

import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirmwareHeaderTest {

    private val version = FirmwareVersion(1, 2, 3, 4, "release")

    private fun validHeader(
        models: List<String> = listOf("VH-100", "VH-200"),
        payloadLength: Long = 1024,
        payloadCrc32: Long = 0x11223344,
    ) = FirmwareHeader(version, models, payloadLength, payloadCrc32)

    @Test
    fun `编码解码往返保持全部字段`() {
        val header = validHeader()
        val decoded = FirmwareHeader.decode(header.encode())

        assertEquals(FirmwareHeader.SIZE, header.encode().size)
        assertEquals(version, decoded.firmwareVersion)
        assertEquals(listOf("VH-100", "VH-200"), decoded.targetModels)
        assertEquals(1024L, decoded.payloadLength)
        assertEquals(0x11223344L, decoded.payloadCrc32)
    }

    @Test
    fun `通配型号可往返且不做具体匹配`() {
        val header = validHeader(models = listOf("*"))
        val decoded = FirmwareHeader.decode(header.encode())

        assertTrue(decoded.matchesAnyModel)
        assertTrue(decoded.supportsModel("任意型号"))
    }

    @Test
    fun `型号匹配大小写不敏感`() {
        val header = validHeader(models = listOf("VH-100"))
        assertTrue(header.supportsModel("vh-100"))
        assertTrue(!header.supportsModel("VH-200"))
    }

    @Test
    fun `文件头不足 104 字节视为截断`() {
        val error = assertFailsWith<FirmwareException.Truncated> {
            FirmwareHeader.decode(ByteArray(20))
        }
        assertEquals(FirmwareHeader.SIZE.toLong(), error.expectedBytes)
        assertEquals(20L, error.actualBytes)
    }

    @Test
    fun `魔数不符返回明确错误`() {
        val bytes = validHeader().encode()
        bytes[0] = 0x00

        val error = assertFailsWith<FirmwareException.InvalidMagic> {
            FirmwareHeader.decode(bytes)
        }
        assertTrue(error.actual.contains("0x00"))
    }

    @Test
    fun `未知格式版本被拒绝`() {
        val bytes = badField(2, 2)
        assertFailsWith<FirmwareException.UnsupportedFormat> { FirmwareHeader.decode(bytes) }
    }

    @Test
    fun `未知标志位被拒绝`() {
        val bytes = badField(3, 0x02)
        assertFailsWith<FirmwareException.UnsupportedFlags> { FirmwareHeader.decode(bytes) }
    }

    @Test
    fun `签名标志位可往返识别`() {
        val header = FirmwareHeader(
            version,
            listOf("VH-100"),
            payloadLength = 1024,
            payloadCrc32 = 0x11223344,
            hasSignature = true,
        )
        val decoded = FirmwareHeader.decode(header.encode())

        assertTrue(decoded.hasSignature)
        assertEquals(FirmwareHeader.SIGNATURE_FLAG.toByte(), header.encode()[3])
    }

    @Test
    fun `头部 CRC 被篡改时校验失败`() {
        val bytes = validHeader().encode()
        bytes[94] = (bytes[94].toInt() xor 0x01).toByte()

        assertFailsWith<FirmwareException.HeaderChecksumMismatch> { FirmwareHeader.decode(bytes) }
    }

    @Test
    fun `目标型号数量非法被拒绝`() {
        val bytes = badField(29, 0)
        val error = assertFailsWith<FirmwareException.InvalidHeaderField> { FirmwareHeader.decode(bytes) }
        assertEquals("modelCount", error.field)
    }

    @Test
    fun `型号包含非法字符被拒绝`() {
        val bytes = validHeader(models = listOf("VH-100")).encode()
        bytes[30 + 2] = '/'.code.toByte()
        rewriteHeaderCrc(bytes)

        val error = assertFailsWith<FirmwareException.InvalidHeaderField> { FirmwareHeader.decode(bytes) }
        assertTrue(error.message!!.contains("非法字符"))
    }

    @Test
    fun `渠道名非法字符被拒绝`() {
        val bytes = validHeader().encode()
        bytes[13] = '$'.code.toByte()
        rewriteHeaderCrc(bytes)

        assertFailsWith<FirmwareException.InvalidHeaderField> { FirmwareHeader.decode(bytes) }
    }

    @Test
    fun `payload 长度为 0 被拒绝`() {
        val bytes = validHeader().encode()
        for (offset in 94..97) bytes[offset] = 0
        rewriteHeaderCrc(bytes)

        val error = assertFailsWith<FirmwareException.InvalidHeaderField> { FirmwareHeader.decode(bytes) }
        assertEquals("payloadLength", error.field)
    }

    @Test
    fun `构造时拒绝空型号与重复型号`() {
        assertFailsWith<IllegalArgumentException> {
            FirmwareHeader(version, emptyList(), 10, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareHeader(version, listOf("A", "a"), 10, 1)
        }
    }

    @Test
    fun `构造时拒绝超过字段长度的渠道名`() {
        assertFailsWith<IllegalArgumentException> {
            FirmwareHeader(
                FirmwareVersion(1, 0, 0, 0, "0123456789abcdefg"),
                listOf("VH-100"),
                10,
                1,
            )
        }
    }

    private fun badField(offset: Int, value: Int): ByteArray {
        val bytes = validHeader().encode()
        bytes[offset] = value.toByte()
        rewriteHeaderCrc(bytes)
        return bytes
    }

    private fun rewriteHeaderCrc(bytes: ByteArray) {
        val crc = Crc16.ccittFalse(bytes, 0, 102)
        bytes[102] = (crc and 0xFF).toByte()
        bytes[103] = ((crc shr 8) and 0xFF).toByte()
    }
}
