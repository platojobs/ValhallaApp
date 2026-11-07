package com.valhalla.core.firmware

/**
 * 固件文件读取抽象：按绝对偏移分块读取，OTA 分片与预检复用同一份数据源。
 * [read] 必须返回恰好 [length] 字节，越界或读短时抛出 [FirmwareException.ReadFailure]。
 */
interface FirmwareSource {
    val size: Long

    suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray

    fun close()

    companion object {
        fun memory(bytes: ByteArray): FirmwareSource = ByteArrayFirmwareSource(bytes)
    }
}

/** 内存实现，主要用于测试与小文件。 */
class ByteArrayFirmwareSource(
    private val bytes: ByteArray,
) : FirmwareSource {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size.toLong()) {
            throw FirmwareException.ReadFailure(offset, length)
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun close() = Unit
}
