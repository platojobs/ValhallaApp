package com.valhalla.core.protocol

private const val PAYLOAD_LENGTH_OFFSET = 7

/**
 * 增量帧解析器，适配 BLE 通知分包/粘包场景。
 *
 * 使用方式：把每次收到的数据块交给 [feed]，返回值即本次凑齐的完整帧。
 * 解析器内部维护残留缓冲，遇到噪声或损坏帧会自动重新寻找 SOF。
 */
class FrameParser(
    private val maxPayloadSize: Int = FrameCodec.MAX_PAYLOAD_SIZE,
) {
    private var buffer: ByteArray = ByteArray(0)

    val bufferedBytes: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<Frame> {
        if (chunk.isEmpty()) return emptyList()
        buffer = buffer + chunk

        val frames = mutableListOf<Frame>()
        var guard = 0
        while (guard++ < MAX_LOOP) {
            val start = indexOfSof()
            if (start < 0) {
                // 没有完整 SOF：只保留可能是 SOF 前半段的最后一个字节
                buffer =
                    if (buffer.isNotEmpty() && buffer[buffer.size - 1] == FrameCodec.SOF_FIRST) {
                        byteArrayOf(FrameCodec.SOF_FIRST)
                    } else {
                        ByteArray(0)
                    }
                break
            }
            if (start > 0) {
                buffer = buffer.copyOfRange(start, buffer.size)
            }
            if (buffer.size < FrameCodec.HEADER_SIZE) break

            val payloadLength = FrameCodec.readUInt16(buffer, PAYLOAD_LENGTH_OFFSET)
            if (payloadLength > maxPayloadSize) {
                // 长度字段不可信，丢弃当前 SOF 后重新同步
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            val totalSize = FrameCodec.HEADER_SIZE + payloadLength + FrameCodec.CRC_SIZE
            if (buffer.size < totalSize) break

            val candidate = buffer.copyOfRange(0, totalSize)
            try {
                frames += FrameCodec.decode(candidate)
                buffer = buffer.copyOfRange(totalSize, buffer.size)
            } catch (_: FrameDecodeException) {
                // CRC/版本等校验失败：逐字节丢弃，继续寻找下一帧
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }
        return frames
    }

    private fun indexOfSof(): Int {
        if (buffer.size < 2) return -1
        for (index in 0..buffer.size - 2) {
            if (buffer[index] == FrameCodec.SOF_FIRST && buffer[index + 1] == FrameCodec.SOF_SECOND) {
                return index
            }
        }
        return -1
    }

    private companion object {
        const val MAX_LOOP = 1024
    }
}
