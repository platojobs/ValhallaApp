package com.valhalla.testkit

import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareHeader
import com.valhalla.core.protocol.message.FirmwareVersion

/** 构造端到端测试用的固件文件（104 字节头 + payload）与设备侧既有内容。 */
object SimulatorFirmware {

    fun packageBytes(
        payload: ByteArray,
        version: FirmwareVersion,
        models: List<String> = listOf("VH-100"),
    ): ByteArray {
        val header = FirmwareHeader(
            firmwareVersion = version,
            targetModels = models,
            payloadLength = payload.size.toLong(),
            payloadCrc32 = Crc32.compute(payload),
        )
        return header.encode() + payload
    }
}
