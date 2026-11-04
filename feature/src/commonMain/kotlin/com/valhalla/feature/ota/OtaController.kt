package com.valhalla.feature.ota

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.RetryPolicy
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.AbortOtaRequest
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.OtaAbortReason
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.OtaProgressNotification
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.data.ble.BleException
import com.valhalla.feature.toFailureInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** OTA 流程状态。 */
sealed interface OtaState {

    data object Idle : OtaState

    data class Preparing(
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
    ) : OtaState

    data class Transferring(
        val sentBytes: Int,
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
        /** 设备进度通知上报的阶段，默认按本地传输阶段展示。 */
        val stage: OtaStage = OtaStage.TRANSFERRING,
        val retransmissions: Int = 0,
    ) : OtaState {
        val progress: Float
            get() = if (totalBytes == 0) 0f else sentBytes.toFloat() / totalBytes
    }

    data class Verifying(
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
        val retransmissions: Int = 0,
    ) : OtaState

    data class Succeeded(
        val totalBytes: Int,
        val crc16: Int,
        val chunks: Int,
        val retransmissions: Int = 0,
        val rebootRequired: Boolean = false,
    ) : OtaState

    /** 用户主动中止或流程被取消。 */
    data class Aborted(
        val reason: String,
        val sentBytes: Int,
        val totalBytes: Int,
    ) : OtaState

    data class Failed(
        val reason: String,
        val sentBytes: Int,
        val totalBytes: Int = 0,
        val status: DeviceStatus? = null,
        val resumePoint: OtaResumePoint? = null,
    ) : OtaState
}

/**
 * OTA 传输控制器：START_OTA → 逐片 OTA_DATA（绝对偏移）→ FINISH_OTA 校验。
 *
 * 能力：
 * - 以设备返回的 `chunkSize` 为准重新切片，支持乱序偏移与分片确认长度校验；
 * - 设备以 `BUSY` / `CRC_MISMATCH` / `INTERNAL_ERROR` 拒绝分片时按 [maxChunkRetries] 重传同一偏移；
 * - 订阅 `OTA_PROGRESS_NOTIFY`，用设备进度与阶段驱动 UI（不阻塞传输流程）；
 * - 支持 `ABORT_OTA` 主动中止，失败时保留断点续传锚点；
 * - 通过 [checkpointStore] 持久化断点：传输中按 [CHECKPOINT_INTERVAL_BYTES] 间隔落盘，
 *   失败时记录精确偏移；成功、中止、reset 时清除；
 * - 通过 [connectionState] 感知链路断开，在分片间隙快速失败并允许 UI 复位。
 *
 * 续传协议限制：当前协议没有独立的恢复指令，`START_OTA` 也不携带起始偏移。
 * 只有设备在新会话中复用原 `sessionId`，控制器才会从断点偏移继续，否则从头传输。
 * 该行为依赖设备固件实现，真机未验证。
 */
class OtaController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val chunkSize: Int = 64,
    private val firmwareVersion: FirmwareVersion = FirmwareVersion(1, 0, 0, 0, "release"),
    private val scope: CoroutineScope? = null,
    private val connectionState: StateFlow<Boolean>? = null,
    private val maxChunkRetries: Int = 2,
    private val checkpointStore: OtaCheckpointStore? = null,
) {

    init {
        require(chunkSize in 1..1024) { "chunkSize 需在 1..1024 之间" }
        require(maxChunkRetries >= 0) { "maxChunkRetries 不能为负" }
    }

    private val startMutex = Mutex()
    private val abortMutex = Mutex()
    private val mutableState = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = mutableState.asStateFlow()

    /** 最近一次传输失败时记录的断点锚点；成功、重置或重新开始时清空。 */
    var lastResumePoint: OtaResumePoint? = null
        private set

    private var abortReason: OtaAbortReason? = null
    private var activeSessionId: Long? = null
    private var progressJob: Job? = null
    private var retransmissions = 0

    /**
     * 以内存 payload 执行一次升级（测试与兼容入口）。
     * 传入的 [firmware] 视为已经剥离文件头的 payload。
     */
    suspend fun start(firmware: ByteArray, resumeFrom: OtaResumePoint? = null): OtaState = startMutex.withLock {
        prepareRun()
        if (firmware.isEmpty()) {
            return failed("固件包为空", 0, 0)
        }

        val crc = Crc16.ccittFalse(firmware)
        val crc32 = Crc32.compute(firmware)
        runTransfer(
            totalBytes = firmware.size.toLong(),
            crc16 = crc,
            crc32 = crc32,
            version = firmwareVersion,
            resumeFrom = resumeFrom,
            read = { offset, length -> firmware.copyOfRange(offset.toInt(), offset.toInt() + length) },
        )
    }

    /**
     * 从已解析的固件包执行升级：先流式计算协议层 CRC-16，再按 payload 偏移分片读取。
     *
     * 调用方必须先通过 [com.valhalla.core.firmware.FirmwarePrechecker] 预检；
     * 本方法不重新校验 CRC-32，只保证分片读取边界与协议 CRC-16 一致。
     */
    suspend fun startPackage(
        firmware: FirmwarePackage,
        resumeFrom: OtaResumePoint? = null,
    ): OtaState = startMutex.withLock {
        prepareRun()
        if (firmware.payloadLength <= 0 || firmware.payloadLength > Int.MAX_VALUE.toLong()) {
            return failed("固件 payload 长度非法: ${firmware.payloadLength}", 0, 0)
        }

        val crc = try {
            computeCrc16(firmware)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: FirmwareException) {
            return failed(error.message ?: "固件读取失败", 0, 0)
        }

        runTransfer(
            totalBytes = firmware.payloadLength,
            crc16 = crc,
            crc32 = firmware.payloadCrc32,
            version = firmware.version,
            resumeFrom = resumeFrom,
            read = { offset, length -> firmware.readPayload(offset, length) },
        )
    }

    /**
     * 中止升级：置位中止标记并尽力发送 `ABORT_OTA`。
     *
     * 只对 Preparing / Transferring / Verifying 生效；传输循环会在下一个分片边界退出。
     */
    suspend fun abort(reason: OtaAbortReason = OtaAbortReason.USER_CANCELLED): OtaState {
        val current = mutableState.value
        if (current !is OtaState.Preparing &&
            current !is OtaState.Transferring &&
            current !is OtaState.Verifying
        ) {
            return current
        }

        abortMutex.withLock { abortReason = reason }
        val aborted = OtaState.Aborted(
            reason = reason.name,
            sentBytes = current.sentBytes(),
            totalBytes = current.totalBytes(),
        )
        mutableState.value = aborted

        try {
            dispatcher.execute(
                AbortOtaRequest(reason),
                policy = RetryPolicy(maxAttempts = 1, timeoutMillis = 1_000),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // 中止是尽力而为：链路已断时以本地状态为准。
        }
        return aborted
    }

    /**
     * 断点续传入口（内存 payload）。
     *
     * 仅当设备在新会话中返回相同 `sessionId` 且固件身份一致时才从断点继续，否则从头开始。
     */
    suspend fun resume(firmware: ByteArray): OtaState = start(firmware, resumeFrom = lastResumePoint)

    fun reset() {
        abortMutex.tryLock().let { locked ->
            if (locked) {
                abortReason = null
                abortMutex.unlock()
            }
        }
        lastResumePoint = null
        retransmissions = 0
        activeSessionId = null
        checkpointStore?.clear()
        mutableState.value = OtaState.Idle
    }

    private suspend fun prepareRun() {
        abortMutex.withLock { abortReason = null }
        lastResumePoint = null
        retransmissions = 0
    }

    private suspend fun computeCrc16(firmware: FirmwarePackage): Int {
        var crc = Crc16.INITIAL
        var offset = 0L
        while (offset < firmware.payloadLength) {
            val length = minOf(CRC_CHUNK_SIZE.toLong(), firmware.payloadLength - offset).toInt()
            crc = Crc16.update(crc, firmware.readPayload(offset, length))
            offset += length
        }
        return crc
    }

    private suspend fun runTransfer(
        totalBytes: Long,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
        resumeFrom: OtaResumePoint?,
        read: suspend (offset: Long, length: Int) -> ByteArray,
    ): OtaState {
        val total = totalBytes.toInt()
        var sent = 0
        var chunks = 0
        var sessionId: Long? = null
        var lastCheckpointBytes = 0

        startProgressCollector()
        yield()
        mutableState.value = OtaState.Preparing(total, crc16)

        try {
            ensureLinkAlive()
            val startResponse = dispatcher.execute(
                StartOtaRequest(
                    totalBytes = totalBytes,
                    crc16 = crc16,
                    chunkSize = chunkSize,
                    firmwareVersion = version,
                ),
            )
            if (startResponse !is StartOtaResponse) {
                error("开始 OTA 应答类型异常: ${startResponse::class.simpleName}")
            }
            sessionId = startResponse.sessionId
            activeSessionId = sessionId

            val effectiveChunkSize = startResponse.chunkSize.takeIf { it in 1..1024 } ?: chunkSize
            sent = resumableOffset(resumeFrom, sessionId, total, crc16, crc32, version)
            lastCheckpointBytes = sent
            if (sent > 0) {
                mutableState.value = OtaState.Transferring(
                    sentBytes = sent,
                    totalBytes = total,
                    crc16 = crc16,
                    sessionId = sessionId,
                )
            }

            while (sent < total) {
                checkAborted()
                ensureLinkAlive()
                val length = minOf(effectiveChunkSize, total - sent)
                val chunk = read(sent.toLong(), length)
                if (chunk.size != length) {
                    throw FirmwareException.ReadFailure(sent.toLong(), length)
                }
                sendChunk(sent, chunk)
                sent += length
                chunks++
                val current = mutableState.value
                mutableState.value = if (current is OtaState.Transferring) {
                    current.copy(
                        sentBytes = sent,
                        sessionId = sessionId,
                        retransmissions = retransmissions,
                    )
                } else {
                    OtaState.Transferring(
                        sentBytes = sent,
                        totalBytes = total,
                        crc16 = crc16,
                        sessionId = sessionId,
                        retransmissions = retransmissions,
                    )
                }

                if (sent < total && sent - lastCheckpointBytes >= CHECKPOINT_INTERVAL_BYTES) {
                    saveCheckpoint(sessionId, sent, total, crc16, crc32, version)
                    lastCheckpointBytes = sent
                }
            }

            checkAborted()
            ensureLinkAlive()
            mutableState.value = OtaState.Verifying(
                totalBytes = total,
                crc16 = crc16,
                sessionId = sessionId,
                retransmissions = retransmissions,
            )
            val finishResponse = dispatcher.execute(
                FinishOtaRequest(totalBytes = totalBytes, crc16 = crc16),
            )
            if (finishResponse !is FinishOtaResponse) {
                error("结束 OTA 应答类型异常: ${finishResponse::class.simpleName}")
            }

            checkpointStore?.clear()
            val done = OtaState.Succeeded(
                totalBytes = total,
                crc16 = crc16,
                chunks = chunks,
                retransmissions = retransmissions,
                rebootRequired = finishResponse.rebootRequired,
            )
            mutableState.value = done
            return done
        } catch (cancellation: CancellationException) {
            sessionId?.let { saveCheckpoint(it, sent, total, crc16, crc32, version) }
            mutableState.value = OtaState.Aborted(OtaAbortReason.USER_CANCELLED.name, sent, total)
            throw cancellation
        } catch (aborted: OtaAbortedException) {
            checkpointStore?.clear()
            val state = OtaState.Aborted(aborted.reason.name, sent, total)
            mutableState.value = state
            return state
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            val resumePoint = sessionId?.let { pointOf(it, sent, total, crc16, crc32, version) }
            if (resumePoint != null) {
                saveCheckpoint(resumePoint)
            } else {
                checkpointStore?.clear()
            }
            lastResumePoint = resumePoint
            val state = OtaState.Failed(
                reason = info.message,
                sentBytes = sent,
                totalBytes = total,
                status = info.status,
                resumePoint = resumePoint,
            )
            mutableState.value = state
            return state
        } finally {
            progressJob?.cancel()
            progressJob = null
            activeSessionId = null
        }
    }

    private suspend fun sendChunk(offset: Int, chunk: ByteArray) {
        var attempt = 0
        while (true) {
            checkAborted()
            ensureLinkAlive()
            try {
                val response = dispatcher.execute(OtaDataRequest(offset = offset.toLong(), data = chunk))
                if (response !is OtaDataResponse) {
                    throw CommandFailure.InvalidResponse(
                        "OTA 分片应答类型异常: ${response::class.simpleName}",
                    )
                }
                val expected = offset + chunk.size
                if (response.receivedBytes < expected) {
                    throw CommandFailure.InvalidResponse(
                        "设备确认长度不足: ${response.receivedBytes} < $expected",
                    )
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (!isRetryable(error) || attempt >= maxChunkRetries) throw error
                attempt++
                retransmissions++
                val current = mutableState.value
                if (current is OtaState.Transferring) {
                    mutableState.value = current.copy(retransmissions = retransmissions)
                }
            }
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is CommandFailure.InvalidResponse -> true
        is CommandFailure.DeviceRejected -> error.status in RETRYABLE_STATUS
        else -> false
    }

    private fun startProgressCollector() {
        val scope = scope ?: return
        progressJob?.cancel()
        progressJob = scope.launch {
            dispatcher.notifications
                .filterIsInstance<OtaProgressNotification>()
                .collect { notification -> applyProgress(notification) }
        }
    }

    private fun applyProgress(notification: OtaProgressNotification) {
        if (notification.sessionId != activeSessionId) return
        mutableState.update { current ->
            if (current is OtaState.Transferring) {
                current.copy(
                    sentBytes = maxOf(
                        current.sentBytes,
                        notification.receivedBytes.toInt().coerceIn(0, current.totalBytes),
                    ),
                    stage = notification.stage,
                )
            } else {
                current
            }
        }
    }

    private suspend fun checkAborted() {
        val reason = abortMutex.withLock { abortReason } ?: return
        throw OtaAbortedException(reason)
    }

    private fun ensureLinkAlive() {
        if (connectionState != null && !connectionState.value) {
            throw BleException("链路已断开")
        }
    }

    private fun resumableOffset(
        resumeFrom: OtaResumePoint?,
        sessionId: Long,
        totalBytes: Int,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
    ): Int {
        val point = resumeFrom ?: return 0
        if (!point.isValid()) return 0
        if (point.sessionId != sessionId) return 0
        if (!point.matchesPayload(version, totalBytes.toLong(), crc32, crc16)) return 0
        return point.sentBytes.coerceIn(0, totalBytes)
    }

    private fun saveCheckpoint(
        sessionId: Long,
        sentBytes: Int,
        totalBytes: Int,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
    ) {
        pointOf(sessionId, sentBytes, totalBytes, crc16, crc32, version)?.let(::saveCheckpoint)
    }

    private fun saveCheckpoint(point: OtaResumePoint) {
        runCatching { checkpointStore?.save(point) }
    }

    private fun pointOf(
        sessionId: Long,
        sentBytes: Int,
        totalBytes: Int,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
    ): OtaResumePoint? = OtaResumePoint(
        sessionId = sessionId,
        sentBytes = sentBytes,
        totalBytes = totalBytes,
        crc16 = crc16,
        firmwareVersion = version,
        crc32 = crc32,
    ).takeIf { it.isValid() }

    private fun failed(reason: String, sentBytes: Int, totalBytes: Int): OtaState.Failed {
        val state = OtaState.Failed(reason, sentBytes, totalBytes)
        mutableState.value = state
        return state
    }

    private class OtaAbortedException(val reason: OtaAbortReason) : Exception("OTA 已中止：${reason.name}")

    private fun OtaState.sentBytes(): Int = when (this) {
        is OtaState.Preparing -> 0
        is OtaState.Transferring -> sentBytes
        is OtaState.Verifying -> totalBytes
        else -> 0
    }

    private fun OtaState.totalBytes(): Int = when (this) {
        is OtaState.Preparing -> totalBytes
        is OtaState.Transferring -> totalBytes
        is OtaState.Verifying -> totalBytes
        else -> 0
    }

    private companion object {
        const val CRC_CHUNK_SIZE: Int = 64 * 1024
        const val CHECKPOINT_INTERVAL_BYTES: Int = 4 * 1024

        val RETRYABLE_STATUS = setOf(
            DeviceStatus.BUSY,
            DeviceStatus.CRC_MISMATCH,
            DeviceStatus.INTERNAL_ERROR,
        )
    }
}
