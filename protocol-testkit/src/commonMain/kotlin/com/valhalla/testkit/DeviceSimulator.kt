package com.valhalla.testkit

import com.valhalla.core.command.FrameLink
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.FrameCodec
import com.valhalla.core.protocol.FrameParser
import com.valhalla.core.protocol.message.AbortOtaRequest
import com.valhalla.core.protocol.message.AbortOtaResponse
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.AuthResponse
import com.valhalla.core.protocol.message.SignedAuthCredential
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.ErrorResponse
import com.valhalla.core.protocol.message.FactoryResetRequest
import com.valhalla.core.protocol.message.FactoryResetResponse
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.GetParamRequest
import com.valhalla.core.protocol.message.GetTimeRequest
import com.valhalla.core.protocol.message.HeartbeatRequest
import com.valhalla.core.protocol.message.HeartbeatResponse
import com.valhalla.core.protocol.message.LogDumpRequest
import com.valhalla.core.protocol.message.LogDumpResponse
import com.valhalla.core.protocol.message.MessageCodec
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.OtaProgressNotification
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.core.protocol.message.ParamResponse
import com.valhalla.core.protocol.message.RebootRequest
import com.valhalla.core.protocol.message.RebootResponse
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.core.protocol.message.SetParamRequest
import com.valhalla.core.protocol.message.SetParamResponse
import com.valhalla.core.protocol.message.SetTimeRequest
import com.valhalla.core.protocol.message.SetTimeResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.core.protocol.message.TimeResponse
import com.valhalla.core.security.AuthSignatureMessage
import com.valhalla.core.security.HmacSha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 设备侧协议一致性模拟器。
 *
 * 它实现与真实 BLE 链路相同的 [FrameLink] 接口：接收 App 请求帧，按命令表生成
 * 合规应答与通知，并可注入超时、忙、CRC 失败、确认长度不足、重复/乱序/粘包/拆包、
 * 断连等故障。用于验证**协议一致性**，不代表真机硬件行为。
 */
class DeviceSimulator(
    var scenario: SimulatorScenario = SimulatorScenario(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FrameLink {

    private val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    override val incoming: Flow<Frame> = incomingChannel.receiveAsFlow()

    /** 是否在线；测试可据此驱动 OtaController 的断连快速失败。 */
    val connectionState = MutableStateFlow(true)

    /** 按发送顺序记录的请求帧与强类型请求，便于断言协议字段。 */
    val frames: MutableList<Frame> = mutableListOf()
    val requests: MutableList<DeviceRequest> = mutableListOf()

    /** 每次成功解析请求后回调，便于测试在特定指令到达时观察 App 状态。 */
    var onRequest: suspend (DeviceRequest) -> Unit = {}

    private val configs = scenario.configs.toMutableMap()
    private val remainingFaults = mutableMapOf<Pair<CommandType, FaultKind>, Int>()
    private var sessionCounter = 0L
    private var activeSessionId: Long? = null
    private var activeTotalBytes: Long = 0
    private var activeCrc16: Int = 0
    private var activeCrc32: Long = 0
    private var acceptedOffset: Long = 0
    private var receivedBytes: Long = 0
    private var buffer: ByteArray? = null
    private var otaDataChunks = 0
    private var droppedNotifications = 0

    init {
        resetFaults()
    }

    /** 重新装载故障计数（场景切换后调用）。 */
    fun resetFaults() {
        remainingFaults.clear()
        scenario.faults.timeoutCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.TIMEOUT] = count
        }
        scenario.faults.busyCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.BUSY] = count
        }
        scenario.faults.crcMismatchCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.CRC_MISMATCH] = count
        }
        scenario.faults.shortConfirmCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.SHORT_CONFIRM] = count
        }
        scenario.faults.duplicateCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.DUPLICATE] = count
        }
        scenario.faults.stickyCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.STICKY] = count
        }
        scenario.faults.fragmentCommands.forEach { (command, count) ->
            remainingFaults[command to FaultKind.FRAGMENT] = count
        }
        droppedNotifications = scenario.faults.dropNotifications
    }

    /** 主动断开链路：后续请求不再产生应答，App 侧感知 [connectionState] 变化。 */
    fun disconnect() {
        connectionState.value = false
    }

    fun reconnect() {
        connectionState.value = true
    }

    /** 主动上报一条通知（例如设备阶段变化）。 */
    fun emit(notificationPayload: ByteArray, command: CommandType, sequence: Int = 0) {
        incomingChannel.trySend(
            Frame(command, sequence, notificationPayload, isNotification = true),
        )
    }

    override suspend fun send(frame: Frame) {
        frames += Frame(
            type = frame.type,
            sequence = frame.sequence,
            payload = frame.payloadCopy(),
            isResponse = frame.isResponse,
            isNotification = frame.isNotification,
        )
        if (!connectionState.value) return

        val request = try {
            MessageCodec.decodeRequest(frame.type, frame.payload)
        } catch (_: RuntimeException) {
            respond(frame, ErrorResponse(frame.type, DeviceStatus.INVALID_PAYLOAD, "请求 payload 解析失败"))
            return
        }
        requests += request
        onRequest(request)
        handle(frame, request)
    }

    // -----------------------------------------------------------------
    // 命令分发
    // -----------------------------------------------------------------

    private suspend fun handle(frame: Frame, request: DeviceRequest) {
        when (request) {
            is AuthRequest -> handleAuth(frame, request)

            is GetDeviceInfoRequest -> respond(
                frame,
                DeviceInfoResponse(
                    status = DeviceStatus.OK,
                    vendor = scenario.vendor,
                    model = scenario.model,
                    serialNumber = scenario.serialNumber,
                    hardwareVersion = scenario.hardwareVersion,
                    firmwareVersion = scenario.deviceVersion,
                ),
            )

            is GetBatteryRequest -> respond(
                frame,
                BatteryResponse(
                    status = DeviceStatus.OK,
                    percent = scenario.batteryPercent,
                    charging = scenario.charging,
                    voltageMillivolts = scenario.voltageMillivolts,
                ),
            )

            is GetFirmwareVersionRequest -> respond(
                frame,
                FirmwareVersionResponse(DeviceStatus.OK, scenario.deviceVersion),
            )

            is SetTimeRequest -> respond(frame, SetTimeResponse(DeviceStatus.OK, request.epochSeconds))
            is GetTimeRequest -> respond(
                frame,
                TimeResponse(DeviceStatus.OK, epochSeconds = 1_700_000_000L, timezoneOffsetMinutes = 0),
            )

            is SetConfigRequest -> {
                configs[request.key] = request.value
                respond(frame, SetConfigResponse(DeviceStatus.OK))
            }

            is GetConfigRequest -> {
                val value = configs[request.key]
                respond(
                    frame,
                    if (value == null) {
                        ErrorResponse(CommandType.GET_CONFIG, DeviceStatus.INVALID_PARAMETER, "未知配置项")
                    } else {
                        ConfigResponse(DeviceStatus.OK, request.key, value)
                    },
                )
            }

            is SetParamRequest -> respond(frame, SetParamResponse(DeviceStatus.OK))
            is GetParamRequest -> respond(
                frame,
                ParamResponse(DeviceStatus.OK, request.paramId, byteArrayOf(0x01)),
            )

            is FactoryResetRequest -> respond(frame, FactoryResetResponse(DeviceStatus.OK))
            is RebootRequest -> respond(frame, RebootResponse(DeviceStatus.OK))
            is LogDumpRequest -> respond(frame, LogDumpResponse(DeviceStatus.OK, emptyList()))
            is HeartbeatRequest -> respond(
                frame,
                HeartbeatResponse(DeviceStatus.OK, uptimeSeconds = 5_000, batteryPercent = scenario.batteryPercent),
            )

            is StartOtaRequest -> handleStart(frame, request)
            is OtaDataRequest -> handleData(frame, request)
            is FinishOtaRequest -> handleFinish(frame, request)
            is AbortOtaRequest -> {
                activeSessionId = null
                respond(frame, AbortOtaResponse(DeviceStatus.OK))
            }

            else -> respond(
                frame,
                ErrorResponse(frame.type, DeviceStatus.UNKNOWN_COMMAND, "模拟器未实现 ${frame.type.name}"),
            )
        }
    }

    /**
     * AUTH 应答：TOKEN 直接比对场景配置；CHALLENGE 下发随机挑战；
     * SIGNED 用预共享密钥对 `challenge || clientNonce || deviceDigest` 验签。
     */
    private fun handleAuth(frame: Frame, request: AuthRequest) {
        if (scenario.authStatus != DeviceStatus.OK) {
            respond(frame, ErrorResponse(CommandType.AUTH, scenario.authStatus, "模拟鉴权失败"))
            return
        }

        when (request.mode) {
            AuthMode.TOKEN -> respond(frame, authSuccess(ByteArray(0)))

            AuthMode.CHALLENGE -> respond(frame, authSuccess(scenario.authChallenge))

            AuthMode.SIGNED -> {
                val credential = try {
                    SignedAuthCredential.decode(request.credential)
                } catch (_: RuntimeException) {
                    respond(
                        frame,
                        ErrorResponse(CommandType.AUTH, DeviceStatus.INVALID_PAYLOAD, "签名凭据格式非法"),
                    )
                    return
                }
                val message = AuthSignatureMessage.canonical(
                    challenge = credential.challenge,
                    credential = credential.clientNonce,
                    deviceDigest = credential.deviceDigest,
                )
                val valid = credential.deviceDigest == scenario.deviceDigest &&
                    HmacSha256.verify(scenario.authSecret, message, credential.signature)
                respond(
                    frame,
                    if (valid) {
                        authSuccess(ByteArray(0))
                    } else {
                        ErrorResponse(CommandType.AUTH, DeviceStatus.AUTH_FAILED, "签名挑战应答校验失败")
                    },
                )
            }
        }
    }

    private fun authSuccess(challenge: ByteArray): AuthResponse = AuthResponse(
        status = DeviceStatus.OK,
        token = scenario.authToken,
        expiresInSeconds = scenario.authExpiresInSeconds,
        challenge = challenge,
    )

    // -----------------------------------------------------------------
    // OTA
    // -----------------------------------------------------------------

    private fun handleStart(frame: Frame, request: StartOtaRequest) {
        // 超时/忙必须在推进设备状态之前注入，否则重试时偏移会不连续。
        if (consume(FaultKind.TIMEOUT, CommandType.START_OTA)) return
        val busy = consume(FaultKind.BUSY, CommandType.START_OTA)

        val hasExtension = request.hasResumeExtension
        if (hasExtension) {
            when (scenario.resumePolicy) {
                ResumePolicy.LEGACY_DEVICE -> {
                    respond(
                        frame,
                        ErrorResponse(CommandType.START_OTA, DeviceStatus.INVALID_PAYLOAD, "旧设备不支持续传扩展"),
                        transientConsumed = true,
                        busy = busy,
                    )
                    return
                }

                ResumePolicy.REJECT -> {
                    respond(
                        frame,
                        ErrorResponse(CommandType.START_OTA, scenario.resumeRejectStatus, "模拟器拒绝续传"),
                        transientConsumed = true,
                        busy = busy,
                    )
                    return
                }

                ResumePolicy.IGNORE, ResumePolicy.ACCEPT, ResumePolicy.ADJUST -> Unit
            }
        }

        if (hasExtension && scenario.existingPayload == null) {
            respond(
                frame,
                ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_RESUME_CONTEXT_INVALID, "设备没有可复用上下文"),
                transientConsumed = true,
                busy = busy,
            )
            return
        }

        val existing = scenario.existingPayload
        if (hasExtension && existing != null && request.payloadCrc32 > 0) {
            val expectedCrc32 = Crc32.compute(existing)
            if (expectedCrc32 != request.payloadCrc32) {
                respond(
                    frame,
                    ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_FIRMWARE_MISMATCH, "固件 CRC32 不匹配"),
                    transientConsumed = true,
                    busy = busy,
                )
                return
            }
        }

        val accepted = when {
            !hasExtension -> 0L
            scenario.resumePolicy == ResumePolicy.IGNORE -> 0L
            scenario.resumePolicy == ResumePolicy.ADJUST ->
                (request.resumeOffset - scenario.resumeDownBytes).coerceAtLeast(0L)

            else -> request.resumeOffset
        }
        if (accepted >= request.totalBytes) {
            respond(
                frame,
                ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_RESUME_OFFSET_INVALID, "续传偏移越界"),
                transientConsumed = true,
                busy = busy,
            )
            return
        }
        if (accepted > 0 && existing != null && existing.size < accepted) {
            respond(
                frame,
                ErrorResponse(CommandType.START_OTA, DeviceStatus.OTA_RESUME_CONTEXT_INVALID, "设备侧数据不足以续传"),
                transientConsumed = true,
                busy = busy,
            )
            return
        }

        sessionCounter += 1
        activeSessionId = sessionCounter
        activeTotalBytes = request.totalBytes
        activeCrc16 = request.crc16
        activeCrc32 = request.payloadCrc32
        acceptedOffset = accepted
        receivedBytes = accepted
        otaDataChunks = 0
        buffer = ByteArray(request.totalBytes.toInt()).also { target ->
            if (existing != null) {
                existing.copyInto(target, endIndex = minOf(existing.size, target.size))
            }
        }

        respond(
            frame,
            StartOtaResponse(
                status = DeviceStatus.OK,
                sessionId = sessionCounter,
                chunkSize = scenario.chunkSize,
                acceptedOffset = accepted,
            ),
            transientConsumed = true,
            busy = busy,
        )
    }

    private fun handleData(frame: Frame, request: OtaDataRequest) {
        val session = activeSessionId
        if (session == null) {
            respond(frame, ErrorResponse(CommandType.OTA_DATA, DeviceStatus.OTA_STATE_INVALID, "没有进行中的 OTA"))
            return
        }
        if (request.offset != receivedBytes || request.offset + request.data.size > activeTotalBytes) {
            respond(
                frame,
                ErrorResponse(CommandType.OTA_DATA, DeviceStatus.OTA_RESUME_OFFSET_INVALID, "分片偏移不连续"),
            )
            return
        }

        // 故障注入先于状态推进：超时/忙/短确认后重试同一偏移时，设备状态必须保持不变。
        if (consume(FaultKind.TIMEOUT, CommandType.OTA_DATA)) return
        val busy = consume(FaultKind.BUSY, CommandType.OTA_DATA)
        val shortConfirm = consume(FaultKind.SHORT_CONFIRM, CommandType.OTA_DATA)
        val newReceived = request.offset + request.data.size

        if (busy || shortConfirm) {
            val response = if (busy) {
                ErrorResponse(CommandType.OTA_DATA, DeviceStatus.BUSY, "模拟器忙")
            } else {
                OtaDataResponse(DeviceStatus.OK, newReceived - 1)
            }
            respond(frame, response, transientConsumed = true)
            return
        }

        val target = buffer
        val end = newReceived.toInt()
        if (target != null && request.offset <= target.size && end <= target.size) {
            request.data.copyInto(target, destinationOffset = request.offset.toInt())
        }
        receivedBytes = newReceived
        otaDataChunks += 1

        respond(
            frame,
            OtaDataResponse(DeviceStatus.OK, receivedBytes),
            transientConsumed = true,
        )

        if (droppedNotifications > 0) {
            droppedNotifications -= 1
        } else {
            incomingChannel.trySend(
                Frame(
                    CommandType.OTA_PROGRESS_NOTIFY,
                    sequence = 0,
                    payload = OtaProgressNotification(
                        sessionId = session,
                        receivedBytes = receivedBytes,
                        totalBytes = activeTotalBytes,
                        stage = OtaStage.TRANSFERRING,
                    ).encode(),
                    isNotification = true,
                ),
            )
        }

        val disconnectAfter = scenario.faults.disconnectAfterDataChunks
        if (disconnectAfter != null && otaDataChunks >= disconnectAfter) {
            connectionState.value = false
        }
    }

    private fun handleFinish(frame: Frame, request: FinishOtaRequest) {
        if (activeSessionId == null) {
            respond(frame, ErrorResponse(CommandType.FINISH_OTA, DeviceStatus.OTA_STATE_INVALID, "没有进行中的 OTA"))
            return
        }
        if (consume(FaultKind.CRC_MISMATCH, CommandType.FINISH_OTA)) {
            respond(frame, FinishOtaResponse(DeviceStatus.CRC_MISMATCH, rebootRequired = false))
            return
        }
        if (request.totalBytes != activeTotalBytes || receivedBytes != activeTotalBytes) {
            respond(
                frame,
                ErrorResponse(CommandType.FINISH_OTA, DeviceStatus.OTA_VERIFY_FAILED, "接收长度不完整"),
            )
            return
        }
        val target = buffer
        if (target != null && Crc16.ccittFalse(target) != request.crc16) {
            respond(
                frame,
                ErrorResponse(CommandType.FINISH_OTA, DeviceStatus.OTA_VERIFY_FAILED, "整包 CRC16 不匹配"),
            )
            return
        }
        if (target != null && activeCrc32 > 0 && Crc32.compute(target) != activeCrc32) {
            respond(
                frame,
                ErrorResponse(CommandType.FINISH_OTA, DeviceStatus.OTA_FIRMWARE_MISMATCH, "整包 CRC32 不匹配"),
            )
            return
        }
        activeSessionId = null
        respond(frame, FinishOtaResponse(DeviceStatus.OK, rebootRequired = true))
    }

    // -----------------------------------------------------------------
    // 应答投递与故障注入
    // -----------------------------------------------------------------

    private enum class FaultKind {
        TIMEOUT,
        BUSY,
        CRC_MISMATCH,
        SHORT_CONFIRM,
        DUPLICATE,
        STICKY,
        FRAGMENT,
    }

    private fun consume(kind: FaultKind, command: CommandType): Boolean {
        val key = command to kind
        val left = remainingFaults[key] ?: 0
        if (left <= 0) return false
        remainingFaults[key] = left - 1
        return true
    }

    private fun respond(
        requestFrame: Frame,
        response: DeviceResponse,
        transientConsumed: Boolean = false,
        busy: Boolean = false,
    ) {
        val command = requestFrame.type
        if (!transientConsumed) {
            if (consume(FaultKind.TIMEOUT, command)) return
            if (consume(FaultKind.BUSY, command)) {
                respond(requestFrame, ErrorResponse(command, DeviceStatus.BUSY, "模拟器忙"), transientConsumed = true)
                return
            }
        } else if (busy) {
            val frame = Frame(
                type = command,
                sequence = requestFrame.sequence,
                payload = ErrorResponse(command, DeviceStatus.BUSY, "模拟器忙").encode(),
                isResponse = true,
            )
            incomingChannel.trySend(frame)
            return
        }

        val frame = Frame(
            type = response.command,
            sequence = requestFrame.sequence,
            payload = response.encode(),
            isResponse = true,
        )

        val deliver = {
            when {
                consume(FaultKind.DUPLICATE, command) -> {
                    incomingChannel.trySend(frame)
                    incomingChannel.trySend(frame)
                }

                consume(FaultKind.STICKY, command) -> emitSticky(frame)
                consume(FaultKind.FRAGMENT, command) -> emitFragmented(frame)
                else -> incomingChannel.trySend(frame)
            }
        }

        val delayMillis = scenario.faults.delayedCommands[command] ?: 0L
        if (delayMillis > 0) {
            scope.launch {
                delay(delayMillis)
                deliver()
            }
        } else {
            deliver()
        }
    }

    private fun emitSticky(frame: Frame) {
        val bytes = FrameCodec.encode(frame) + FrameCodec.encode(frame)
        val parser = FrameParser()
        parser.feed(bytes).forEach { incomingChannel.trySend(it) }
    }

    private fun emitFragmented(frame: Frame) {
        val bytes = FrameCodec.encode(frame)
        val step = maxOf(1, bytes.size / 3)
        val parser = FrameParser()
        var index = 0
        while (index < bytes.size) {
            val end = minOf(bytes.size, index + step)
            parser.feed(bytes.copyOfRange(index, end)).forEach { incomingChannel.trySend(it) }
            index = end
        }
    }
}
