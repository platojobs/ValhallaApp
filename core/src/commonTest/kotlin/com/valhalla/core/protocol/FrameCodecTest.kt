package com.valhalla.core.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Crc16Test {
    @Test
    fun `CCITT-FALSE 标准校验向量`() {
        val data = "123456789".encodeToByteArray()
        assertEquals(0x29B1, Crc16.ccittFalse(data))
    }

    @Test
    fun `空数据返回初始值`() {
        assertEquals(0xFFFF, Crc16.ccittFalse(ByteArray(0)))
    }

    @Test
    fun `区间校验只覆盖指定范围`() {
        val data = byteArrayOf(0x00, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x00)
        assertEquals(0x29B1, Crc16.ccittFalse(data, 1, 10))
    }

    @Test
    fun `非法区间抛出异常`() {
        assertFailsWith<IllegalArgumentException> {
            Crc16.ccittFalse(ByteArray(4), 2, 1)
        }
    }
}

class HexTest {
    @Test
    fun `编码默认大写且无分隔符`() {
        assertEquals("A55A0102", Hex.encode(byteArrayOf(0xA5.toByte(), 0x5A, 0x01, 0x02)))
    }

    @Test
    fun `编码支持分隔符与小写`() {
        assertEquals("a5 5a", Hex.encode(byteArrayOf(0xA5.toByte(), 0x5A), upperCase = false, separator = " "))
    }

    @Test
    fun `解码忽略空白与分隔符`() {
        assertContentEquals(byteArrayOf(0xA5.toByte(), 0x5A), Hex.decode("a5:5a"))
        assertContentEquals(byteArrayOf(0x01, 0x02), Hex.decode("01 02"))
    }

    @Test
    fun `解码非法输入抛出异常`() {
        assertFailsWith<IllegalArgumentException> { Hex.decode("A5A") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("ZZ") }
    }
}

class FrameCodecTest {
    @Test
    fun `编码解码往返保持字段一致`() {
        val original =
            Frame(
                type = CommandType.SET_CONFIG,
                sequence = 0x1234,
                payload = byteArrayOf(0x10, 0x20, 0x30),
                isResponse = true,
            )
        val decoded = FrameCodec.decode(FrameCodec.encode(original))
        assertEquals(original, decoded)
        assertContentEquals(original.payload, decoded.payload)
    }

    @Test
    fun `空 payload 往返正常`() {
        val original = Frame(type = CommandType.HEARTBEAT, sequence = 0)
        assertEquals(original, FrameCodec.decode(FrameCodec.encode(original)))
    }

    @Test
    fun `报文头字节布局符合协议`() {
        val encoded =
            FrameCodec.encode(
                Frame(type = CommandType.AUTH, sequence = 7, payload = byteArrayOf(0x01, 0x02), isResponse = true),
            )
        assertEquals(13, encoded.size)
        assertContentEquals(
            byteArrayOf(
                0xA5.toByte(),
                0x5A, // SOF
                0x01, // version
                0x01, // type = AUTH
                0x01, // flags = response
                0x07,
                0x00, // sequence LE
                0x02,
                0x00, // length LE
                0x01,
                0x02, // payload
            ),
            encoded.copyOfRange(0, 11),
        )
    }

    @Test
    fun `未知指令码解析为 UNKNOWN`() {
        val frame = Frame(type = CommandType.HEARTBEAT, sequence = 1)
        val encoded = FrameCodec.encode(frame)
        encoded[3] = 0x66
        val crc = Crc16.ccittFalse(encoded, 2, encoded.size - 2)
        encoded[encoded.size - 2] = (crc and 0xFF).toByte()
        encoded[encoded.size - 1] = ((crc shr 8) and 0xFF).toByte()

        assertEquals(CommandType.UNKNOWN, FrameCodec.decode(encoded).type)
    }

    @Test
    fun `SOF 不匹配时拒绝解析`() {
        val encoded = FrameCodec.encode(Frame(type = CommandType.AUTH, sequence = 2))
        encoded[0] = 0x00
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(encoded) }
    }

    @Test
    fun `payload 被篡改时 CRC 校验失败`() {
        val encoded =
            FrameCodec.encode(
                Frame(type = CommandType.GET_DEVICE_INFO, sequence = 3, payload = byteArrayOf(0x01, 0x02)),
            )
        encoded[FrameCodec.HEADER_SIZE] = 0x7F
        val error = assertFailsWith<FrameDecodeException> { FrameCodec.decode(encoded) }
        assertTrue(error.message!!.contains("CRC"))
    }

    @Test
    fun `截断报文被拒绝`() {
        val encoded = FrameCodec.encode(Frame(type = CommandType.AUTH, sequence = 4))
        assertFailsWith<FrameDecodeException> {
            FrameCodec.decode(encoded.copyOfRange(0, encoded.size - 1))
        }
    }

    @Test
    fun `尾部多余字节被拒绝`() {
        val encoded = FrameCodec.encode(Frame(type = CommandType.AUTH, sequence = 5))
        assertFailsWith<FrameDecodeException> {
            FrameCodec.decode(encoded + byteArrayOf(0x00))
        }
    }

    @Test
    fun `不支持的版本被拒绝`() {
        val encoded = FrameCodec.encode(Frame(type = CommandType.AUTH, sequence = 6))
        encoded[2] = 9
        assertFailsWith<FrameDecodeException> { FrameCodec.decode(encoded) }
    }

    @Test
    fun `超长 payload 在构造阶段即被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            Frame(type = CommandType.OTA_DATA, sequence = 7, payload = ByteArray(FrameCodec.MAX_PAYLOAD_SIZE + 1))
        }
    }

    @Test
    fun `序号越界被拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            Frame(type = CommandType.AUTH, sequence = Frame.MAX_SEQUENCE + 1)
        }
    }
}
