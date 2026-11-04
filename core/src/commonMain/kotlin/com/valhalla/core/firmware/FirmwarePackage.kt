package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion

/**
 * 已解析的固件包：固定头 + payload，数据仍留在 [source] 中按需分块读取。
 *
 * 生命周期由调用方管理：替换或放弃选择时必须调用 [close]，
 * OTA 控制器不会关闭传入的固件包。
 */
class FirmwarePackage(
    val name: String,
    val header: FirmwareHeader,
    val source: FirmwareSource,
) {

    /** payload 在文件中的起始偏移（= 文件头长度）。 */
    val payloadOffset: Long get() = FirmwareHeader.SIZE.toLong()

    val payloadLength: Long get() = header.payloadLength

    val version: FirmwareVersion get() = header.firmwareVersion

    /** payload CRC-32，由文件头声明；实际值可用 [computePayloadCrc32] 校验。 */
    val payloadCrc32: Long get() = header.payloadCrc32

    /** 读取 payload 的一段，[offset] 相对 payload 起点；越界抛 [FirmwareException.ReadFailure]。 */
    suspend fun readPayload(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > payloadLength) {
            throw FirmwareException.ReadFailure(payloadOffset + offset, length)
        }
        return source.read(payloadOffset + offset, length)
    }

    /** 流式计算 payload CRC-32；分块大小仅影响读取粒度，不改变结果。 */
    suspend fun computePayloadCrc32(chunkSize: Int = DEFAULT_CRC_CHUNK_SIZE): Long {
        require(chunkSize > 0) { "chunkSize 必须大于 0: $chunkSize" }
        var crc = 0L
        var offset = 0L
        while (offset < payloadLength) {
            val length = minOf(chunkSize.toLong(), payloadLength - offset).toInt()
            val chunk = readPayload(offset, length)
            crc = Crc32.update(crc, chunk)
            offset += length
        }
        return crc
    }

    fun close() {
        source.close()
    }

    override fun toString(): String = "FirmwarePackage(name=$name, $header)"

    companion object {
        const val DEFAULT_CRC_CHUNK_SIZE: Int = 64 * 1024
    }
}

/** 固件包解析：先校验文件头，再核对头里声明的 payload 长度与真实文件长度。 */
object FirmwareParser {

    /**
     * 解析固件文件。
     *
     * 失败时抛出 [FirmwareException]，由调用方负责 [FirmwareSource.close]；
     * 成功时返回的 [FirmwarePackage] 接管 source 的生命周期。
     */
    suspend fun parse(name: String, source: FirmwareSource): FirmwarePackage {
        if (source.size < FirmwareHeader.SIZE.toLong()) {
            throw FirmwareException.Truncated(FirmwareHeader.SIZE.toLong(), source.size)
        }
        val header = FirmwareHeader.decode(source.read(0, FirmwareHeader.SIZE))
        val actualPayloadBytes = source.size - FirmwareHeader.SIZE.toLong()
        if (actualPayloadBytes != header.payloadLength) {
            throw FirmwareException.PayloadSizeMismatch(header.payloadLength, actualPayloadBytes)
        }
        return FirmwarePackage(name = name, header = header, source = source)
    }
}
