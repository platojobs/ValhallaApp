package com.valhalla.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameParserTest {
    private fun frame(
        sequence: Int,
        payload: ByteArray = byteArrayOf(0x01, 0x02),
    ): ByteArray = FrameCodec.encode(Frame(type = CommandType.GET_DEVICE_INFO, sequence = sequence, payload = payload))

    @Test
    fun `分块输入可以拼出完整帧`() {
        val parser = FrameParser()
        val bytes = frame(10, ByteArray(20) { it.toByte() })
        val collected = mutableListOf<Frame>()

        collected += parser.feed(bytes.copyOfRange(0, 4))
        assertEquals(0, collected.size)
        collected += parser.feed(bytes.copyOfRange(4, 12))
        assertEquals(0, collected.size)
        collected += parser.feed(bytes.copyOfRange(12, bytes.size))

        assertEquals(1, collected.size)
        assertEquals(10, collected.single().sequence)
        assertEquals(0, parser.bufferedBytes)
    }

    @Test
    fun `单次输入包含多帧时全部返回`() {
        val parser = FrameParser()
        val first = frame(1)
        val second = frame(2, ByteArray(0))
        val frames = parser.feed(first + second)

        assertEquals(2, frames.size)
        assertEquals(1, frames[0].sequence)
        assertEquals(2, frames[1].sequence)
    }

    @Test
    fun `噪声前缀被自动跳过`() {
        val parser = FrameParser()
        val frames = parser.feed(byteArrayOf(0x00, 0x11, 0x22) + frame(3))

        assertEquals(1, frames.size)
        assertEquals(3, frames.single().sequence)
    }

    @Test
    fun `损坏帧后仍能恢复后续有效帧`() {
        val parser = FrameParser()
        val corrupted = frame(4).copyOf().also { it[FrameCodec.HEADER_SIZE] = 0x7F }
        val frames = parser.feed(corrupted + frame(5))

        assertEquals(1, frames.size)
        assertEquals(5, frames.single().sequence)
    }

    @Test
    fun `纯噪声不会无限堆积`() {
        val parser = FrameParser()
        val frames = parser.feed(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertTrue(frames.isEmpty())
        assertEquals(0, parser.bufferedBytes)
    }

    @Test
    fun `尾部半个 SOF 会保留用于下次拼接`() {
        val parser = FrameParser()
        val bytes = frame(6)
        assertTrue(parser.feed(bytes.copyOfRange(0, bytes.size - 1)).isEmpty())
        val frames = parser.feed(byteArrayOf(bytes.last()))
        assertEquals(1, frames.size)
        assertEquals(6, frames.single().sequence)
    }

    @Test
    fun `reset 清空残留缓冲`() {
        val parser = FrameParser()
        parser.feed(frame(7).copyOfRange(0, 5))
        assertTrue(parser.bufferedBytes > 0)
        parser.reset()
        assertEquals(0, parser.bufferedBytes)
    }
}
