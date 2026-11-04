package com.valhalla.testkit

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.firmware.FirmwareSignature
import com.valhalla.core.firmware.FirmwareSignatureAlgorithm
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.security.HmacSha256
import com.valhalla.core.security.Sha256Digest

/** 构造端到端测试用的固件文件（104 字节头 + payload [+ 签名段]）与设备侧既有内容。 */
object SimulatorFirmware {

    /**
     * @param signingSecret 非空时生成带签名段的固件：标志位 bit0 置位，签名对象为
     *   `SHA-256(文件头 || payload)`，算法 HMAC-SHA256。
     */
    fun packageBytes(
        payload: ByteArray,
        version: FirmwareVersion,
        models: List<String> = listOf("VH-100"),
        signingSecret: ByteArray? = null,
    ): ByteArray {
        val hasSignature = signingSecret != null
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = models,
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
            hasSignature = hasSignature,
        )
        val headerBytes = header.encode()
        val packageWithoutSignature = headerBytes + payload
        if (signingSecret == null) return packageWithoutSignature

        val digest = Sha256Digest().update(headerBytes).update(payload).digest()
        val signature = FirmwareSignature(
            algorithm = FirmwareSignatureAlgorithm.HMAC_SHA256,
            bytes = HmacSha256.compute(signingSecret, digest),
        )
        return packageWithoutSignature + signature.encode()
    }
}
