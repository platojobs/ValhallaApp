package com.valhalla.core.firmware

import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.security.Sha256Digest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 一次流式读取同时得到的 payload 校验值。 */
data class FirmwareChecksums(
    /** CRC-32/IEEE，用于与文件头声明比对。 */
    val crc32: Long,
    /** 协议层 CRC-16/CCITT-FALSE，用于与断点锚点和 START_OTA 比对。 */
    val crc16: Int,
)

/** 预检扫描结果：CRC32、CRC16，以及可选的签名摘要，全部由同一次流式读取产出。 */
data class FirmwareScan(
    val crc32: Long,
    val crc16: Int,
    /** 仅在请求且固件含签名段时非空：`SHA-256(头 104 字节 || payload)`。 */
    val signatureDigest: ByteArray? = null,
)

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
    /** 文件头标志位声明了签名段时为解析结果，否则为 null。 */
    val signature: FirmwareSignature? = null,
) {
    /** payload 在文件中的起始偏移（= 文件头长度）。 */
    val payloadOffset: Long get() = FirmwareHeader.SIZE.toLong()

    /** 签名段在文件中的起始偏移（紧跟 payload 之后）。 */
    val signatureOffset: Long get() = payloadOffset + payloadLength

    val payloadLength: Long get() = header.payloadLength

    val version: FirmwareVersion get() = header.firmwareVersion

    /** payload CRC-32，由文件头声明；实际值可用 [computePayloadCrc32] 校验。 */
    val payloadCrc32: Long get() = header.payloadCrc32

    /** 读取 payload 的一段，[offset] 相对 payload 起点；越界抛 [FirmwareException.ReadFailure]。 */
    suspend fun readPayload(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0 || length < 0 || offset + length > payloadLength) {
            throw FirmwareException.ReadFailure(payloadOffset + offset, length)
        }
        return source.read(payloadOffset + offset, length)
    }

    /**
     * 单次流式扫描 payload，同时产出 CRC-32、协议层 CRC-16，以及可选的签名摘要。
     *
     * [onProgress] 在开始（0 字节）与每个分块结束时回调，参数为已读字节数与 payload 总长；
     * 调用方可通过 [onProgress] 推送进度，也可以在回调中抛出取消异常。
     * 每个分块都会检查协程取消状态，保证大文件预检可被取消。
     */
    suspend fun scanPayload(
        includeSignatureDigest: Boolean = false,
        chunkSize: Int = DEFAULT_CRC_CHUNK_SIZE,
        onProgress: ((readBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): FirmwareScan {
        require(chunkSize > 0) { "chunkSize 必须大于 0: $chunkSize" }
        var crc32 = 0L
        var crc16 = Crc16.INITIAL
        val digest = if (includeSignatureDigest) Sha256Digest().update(header.encode()) else null
        var offset = 0L
        onProgress?.invoke(0L, payloadLength)
        while (offset < payloadLength) {
            currentCoroutineContext().ensureActive()
            val length = minOf(chunkSize.toLong(), payloadLength - offset).toInt()
            val chunk = readPayload(offset, length)
            crc32 = Crc32.update(crc32, chunk)
            crc16 = Crc16.update(crc16, chunk)
            digest?.update(chunk)
            offset += length
            onProgress?.invoke(offset, payloadLength)
        }
        return FirmwareScan(crc32 = crc32, crc16 = crc16, signatureDigest = digest?.digest())
    }

    /** 一次流式读取同时计算 CRC-32 与协议 CRC-16，避免重复扫描 payload。 */
    suspend fun computePayloadChecksums(chunkSize: Int = DEFAULT_CRC_CHUNK_SIZE): FirmwareChecksums {
        val scan = scanPayload(chunkSize = chunkSize)
        return FirmwareChecksums(crc32 = scan.crc32, crc16 = scan.crc16)
    }

    /** 流式计算 payload CRC-32；分块大小仅影响读取粒度，不改变结果。 */
    suspend fun computePayloadCrc32(chunkSize: Int = DEFAULT_CRC_CHUNK_SIZE): Long = computePayloadChecksums(chunkSize).crc32

    /**
     * 计算签名摘要：`SHA-256(文件头 104 字节 || payload)`。
     *
     * 流式读取 payload，不整包进内存；文件头用 [FirmwareHeader.encode] 重建，
     * 与磁盘上的有效固件头逐字节一致（含签名标志与头部 CRC）。
     */
    suspend fun computeSignatureDigest(chunkSize: Int = DEFAULT_CRC_CHUNK_SIZE): ByteArray =
        scanPayload(includeSignatureDigest = true, chunkSize = chunkSize).signatureDigest
            ?: error("签名摘要缺失：includeSignatureDigest 未生效")

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
    suspend fun parse(
        name: String,
        source: FirmwareSource,
    ): FirmwarePackage {
        if (source.size < FirmwareHeader.SIZE.toLong()) {
            throw FirmwareException.Truncated(FirmwareHeader.SIZE.toLong(), source.size)
        }
        val header = FirmwareHeader.decode(source.read(0, FirmwareHeader.SIZE))
        val payloadEnd = FirmwareHeader.SIZE.toLong() + header.payloadLength

        if (!header.hasSignature) {
            val actualPayloadBytes = source.size - FirmwareHeader.SIZE.toLong()
            if (actualPayloadBytes != header.payloadLength) {
                throw FirmwareException.PayloadSizeMismatch(header.payloadLength, actualPayloadBytes)
            }
            return FirmwarePackage(name = name, header = header, source = source)
        }

        // 标志位声明了签名段：payload 之后必须是完整且没有多余尾部的签名段。
        val remaining = source.size - payloadEnd
        if (remaining < FirmwareSignature.HEADER_SIZE.toLong()) {
            throw FirmwareException.SignatureSectionInvalid(
                "签名段缺失：payload 之后仅有 $remaining 字节",
            )
        }
        if (remaining > MAX_SIGNATURE_SECTION_BYTES) {
            throw FirmwareException.SignatureSectionInvalid(
                "签名段过大：$remaining 字节，上限 $MAX_SIGNATURE_SECTION_BYTES 字节",
            )
        }
        val section = FirmwareSignature.decode(source.read(payloadEnd, remaining.toInt()), 0)
        if (section.encodedSize.toLong() != remaining) {
            throw FirmwareException.SignatureSectionInvalid(
                "签名段长度不符：声明 ${section.encodedSize} 字节，实际 $remaining 字节",
            )
        }
        return FirmwarePackage(name = name, header = header, source = source, signature = section)
    }

    private const val MAX_SIGNATURE_SECTION_BYTES: Long =
        FirmwareSignature.HEADER_SIZE.toLong() + 0xFFFFL
}
