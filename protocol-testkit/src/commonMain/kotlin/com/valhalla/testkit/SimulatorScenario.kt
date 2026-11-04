package com.valhalla.testkit

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Hex
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.security.Sha256

/**
 * 设备侧模拟器的行为配置。
 *
 * 该配置只描述「设备应该怎样回应」，不包含 App 侧逻辑；端到端测试通过
 * [DeviceSimulator] 把它接入真实的 `ConcurrentCommandDispatcher`。
 */
data class SimulatorScenario(
    val vendor: String = "Valhalla",
    val model: String = "VH-100",
    val serialNumber: String = "SIM0001",
    val hardwareVersion: String = "HW-1.0",
    val deviceVersion: FirmwareVersion = FirmwareVersion(1, 0, 0, 0, "stable"),
    val batteryPercent: Int = 87,
    val charging: Boolean = true,
    val voltageMillivolts: Int = 4_100,
    val authStatus: DeviceStatus = DeviceStatus.OK,
    val authToken: String = "sim-token",
    val authExpiresInSeconds: Long = 3_600,
    /** 挑战模式下设备下发的随机挑战。 */
    val authChallenge: ByteArray = DEFAULT_CHALLENGE,
    /** 签名挑战应答使用的预共享密钥。 */
    val authSecret: ByteArray = DEFAULT_AUTH_SECRET,
    val configs: Map<String, ByteArray> = mapOf("led.brightness" to byteArrayOf(0x10)),
    val chunkSize: Int = 64,
    /** 设备上已存在的 payload；续传时用于还原断点之前的内容并校验整包 CRC。 */
    val existingPayload: ByteArray? = null,
    val resumePolicy: ResumePolicy = ResumePolicy.IGNORE,
    /** [ResumePolicy.ADJUST] 时向下的调整字节数。 */
    val resumeDownBytes: Int = 0,
    /** [ResumePolicy.REJECT] 时返回的错误码。 */
    val resumeRejectStatus: DeviceStatus = DeviceStatus.OTA_RESUME_CONTEXT_INVALID,
    val faults: FaultInjection = FaultInjection(),
) {

    /** 设备标识摘要（vendor|model|serial 的 SHA-256），签名挑战应答时由 App 侧参与签名。 */
    val deviceDigest: String
        get() = Hex.encode(Sha256.compute("$vendor|$model|$serialNumber".encodeToByteArray()))

    companion object {
        val DEFAULT_CHALLENGE: ByteArray = ByteArray(16) { index -> (index + 1).toByte() }
        val DEFAULT_AUTH_SECRET: ByteArray = "valhalla-sim-auth-secret".encodeToByteArray()
    }
}

/** 设备对 `START_OTA` 续传扩展的处理策略。 */
enum class ResumePolicy {
    /** 忽略续传扩展，始终返回 `acceptedOffset = 0`。 */
    IGNORE,

    /** 接受请求中的续传偏移（`acceptedOffset = resumeOffset`）。 */
    ACCEPT,

    /** 接受续传但向下调整 [SimulatorScenario.resumeDownBytes] 字节。 */
    ADJUST,

    /** 返回 [SimulatorScenario.resumeRejectStatus]，模拟上下文失效/标识不匹配。 */
    REJECT,

    /** 旧设备：识别到尾部扩展即返回 `INVALID_PAYLOAD`。 */
    LEGACY_DEVICE,
}

/**
 * 故障注入矩阵。所有计数均表示「前 N 次该指令触发该故障」，用完后自动恢复正常。
 */
data class FaultInjection(
    /** 丢弃应答，触发调度器超时。 */
    val timeoutCommands: Map<CommandType, Int> = emptyMap(),
    /** 返回 `BUSY`。 */
    val busyCommands: Map<CommandType, Int> = emptyMap(),
    /** `FINISH_OTA` 返回 `CRC_MISMATCH`。 */
    val crcMismatchCommands: Map<CommandType, Int> = emptyMap(),
    /** `OTA_DATA` 确认长度比实际少 1 字节，触发同偏移重传。 */
    val shortConfirmCommands: Map<CommandType, Int> = emptyMap(),
    /** 同一应答发送两次，验证调度器丢弃重复帧。 */
    val duplicateCommands: Map<CommandType, Int> = emptyMap(),
    /** 把两个应答帧粘在一次字节流里，验证 `FrameParser` 拆包。 */
    val stickyCommands: Map<CommandType, Int> = emptyMap(),
    /** 把应答帧拆成多段字节写入，验证 `FrameParser` 粘包。 */
    val fragmentCommands: Map<CommandType, Int> = emptyMap(),
    /** 延迟应答（毫秒），用于制造并发请求的乱序返回。 */
    val delayedCommands: Map<CommandType, Long> = emptyMap(),
    /** 丢弃前 N 条 OTA 进度通知。 */
    val dropNotifications: Int = 0,
    /** 收到第 N 个 `OTA_DATA` 并应答后断开连接。 */
    val disconnectAfterDataChunks: Int? = null,
)
