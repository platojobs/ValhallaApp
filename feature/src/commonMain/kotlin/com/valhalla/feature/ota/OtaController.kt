package com.valhalla.feature.ota

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.command.RetryPolicy
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.core.profile.OtaOutcome
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
import com.valhalla.feature.CommandFailureInfo
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MAX_CHUNK_SIZE = 1024
private const val MAX_U16 = 0xFFFF

/** OTA 流程状态。 */
sealed interface OtaState {
    data object Idle : OtaState

    data class Preparing(
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
        /** 本次请求携带的续传偏移（0 表示从头开始）。 */
        val requestedOffset: Int = 0,
    ) : OtaState

    data class Transferring(
        val sentBytes: Int,
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
        /** 设备进度通知上报的阶段，默认按本地传输阶段展示。 */
        val stage: OtaStage = OtaStage.TRANSFERRING,
        val retransmissions: Int = 0,
        /** 设备实际确认的起始偏移（续传时为断点偏移，从头开始为 0）。 */
        val initialOffset: Int = 0,
        /** 设备拒绝或下调续传偏移时的说明；正常从头开始为 null。 */
        val resumeFallbackReason: UiMessage? = null,
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

    /**
     * 队列暂停：在分片边界停在安全点，断点已落盘，可通过 [OtaController.resume] 继续。
     * 与 [Aborted] 不同，暂停不会清除断点。
     */
    data class Paused(
        val sentBytes: Int,
        val totalBytes: Int,
        val crc16: Int,
        val sessionId: Long? = null,
        val checkpointSaved: Boolean = true,
    ) : OtaState {
        val progress: Float
            get() = if (totalBytes == 0) 0f else sentBytes.toFloat() / totalBytes
    }

    /** 用户主动中止或流程被取消。 */
    data class Aborted(
        val reason: String,
        val sentBytes: Int,
        val totalBytes: Int,
    ) : OtaState

    data class Failed(
        val reason: UiMessage,
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
 * 续传协议（v0.9.0）：
 * - 存在可用断点时，`START_OTA` 在基础字段之后追加 14 字节续传扩展
 *   （`extVersion | flags | resumeSessionId | resumeOffset | payloadCrc32`）；
 * - 以设备应答中的 `acceptedOffset` 为唯一权威起点：0 表示从 0 开始，
 *   `0 < accepted <= requested` 表示按设备确认（允许向下调整）续传，
 *   `accepted > requested` 或 `accepted >= total` 视为设备应答非法并直接失败；
 * - 设备显式拒绝续传请求（任意非 0 状态码）时，自动回退为不带扩展的旧格式
 *   `START_OTA` 并从 0 开始，回退原因记录在 [OtaState.Transferring.resumeFallbackReason]；
 * - 断点是否可续传仍先由本地 `matchesPayload(版本/总长/CRC32/CRC16)` 判定，
 *   身份不一致时不会发送续传扩展。
 */
@OptIn(ExperimentalTime::class)
class OtaController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val chunkSize: Int = 64,
    private val firmwareVersion: FirmwareVersion = FirmwareVersion(1, 0, 0, 0, "release"),
    private val scope: CoroutineScope? = null,
    private val connectionState: StateFlow<Boolean>? = null,
    private val maxChunkRetries: Int = 2,
    private val checkpointStore: OtaCheckpointStore? = null,
    /** 当前会话设备 id；与 [profileRepository] 一起提供时，OTA 终态会写入设备档案。 */
    private val deviceId: String? = null,
    private val profileRepository: DeviceProfileRepository? = null,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** 进度收集协程异常的结构化记录出口；生产环境由界面层注入。 */
    private val errorReporter: ErrorReporter? = null,
    /** 队列暂停信号：在每个分片完成后检查，true 时停在安全点并保留断点。 */
    private val pauseSignal: () -> Boolean = { false },
    /** 队列取消当前信号：与 [abort] 等价，但由队列外部触发。 */
    private val cancelSignal: () -> Boolean = { false },
) {
    private var pauseSignalProvider: () -> Boolean = pauseSignal
    private var cancelSignalProvider: () -> Boolean = cancelSignal

    /** 队列执行前注入本次运行的暂停/取消信号；执行结束应调用 [clearQueueSignals]。 */
    fun configureQueueSignals(
        pauseSignal: () -> Boolean,
        cancelSignal: () -> Boolean,
    ) {
        pauseSignalProvider = pauseSignal
        cancelSignalProvider = cancelSignal
    }

    fun clearQueueSignals() {
        pauseSignalProvider = { false }
        cancelSignalProvider = { false }
    }

    init {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "chunkSize 需在 1..1024 之间" }
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
    suspend fun start(
        firmware: ByteArray,
        resumeFrom: OtaResumePoint? = null,
    ): OtaState =
        startMutex.withLock {
            prepareRun()
            if (firmware.isEmpty()) {
                return failed(UiMessage(StringKey.OTA_REASON_EMPTY_FIRMWARE), 0, 0)
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
     * 调用方必须先通过 [com.valhalla.core.firmware.FirmwarePrechecker] 预检；若预检已经
     * 算出 CRC-16，通过 [knownCrc16] 传入即可复用，避免启动前把 payload 再完整读一遍
     * （大文件下这会带来明显的启动延迟且无进度反馈）。不传时回退为本地流式重算。
     *
     * 本方法不重新校验 CRC-32，只保证分片读取边界与协议 CRC-16 一致。
     */
    suspend fun startPackage(
        firmware: FirmwarePackage,
        resumeFrom: OtaResumePoint? = null,
        knownCrc16: Int? = null,
    ): OtaState =
        startMutex.withLock {
            if (knownCrc16 != null) {
                require(knownCrc16 in 0..MAX_U16) { "knownCrc16 超出 u16 范围: $knownCrc16" }
            }
            prepareRun()
            if (firmware.payloadLength <= 0 || firmware.payloadLength > Int.MAX_VALUE.toLong()) {
                return failed(
                    UiMessage(
                        StringKey.OTA_REASON_INVALID_LENGTH,
                        mapOf("length" to firmware.payloadLength.toString()),
                    ),
                    0,
                    0,
                )
            }

            val crc =
                knownCrc16 ?: try {
                    computeCrc16(firmware)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: FirmwareException) {
                    return failed(
                        UiMessage(
                            StringKey.FIRMWARE_LOAD_FAILED,
                            mapOf("detail" to (error.message ?: StringKey.OTA_REASON_FIRMWARE_READ.name)),
                        ),
                        0,
                        0,
                    )
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
        if (!isBusyState(current)) {
            return current
        }

        abortMutex.withLock { abortReason = reason }
        val aborted =
            OtaState.Aborted(
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
     * 优先使用内存中最近一次失败记录的断点；进程重启后内存断点为空，回退读取持久化存储。
     * 控制器随后会本地校验断点固件身份，再将其作为 `START_OTA` 续传扩展发送；
     * 最终起始偏移以设备应答中的 `acceptedOffset` 为准。
     */
    suspend fun resume(firmware: ByteArray): OtaState = start(firmware, resumeFrom = lastResumePoint ?: checkpointStore?.load())

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
        clearCheckpointStore()
        mutableState.value = OtaState.Idle
    }

    private fun isBusyState(state: OtaState): Boolean =
        state is OtaState.Preparing ||
            state is OtaState.Transferring ||
            state is OtaState.Verifying ||
            state is OtaState.Paused

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
        val ctx = TransferContext(totalBytes, crc16, crc32, version, resumeFrom, clockMillis())
        val progress = TransferProgress()
        var sessionId: Long? = null
        startProgressCollector()
        yield()
        val resumeGate = checkResumeGate(resumeFrom, totalBytes, crc16, crc32, version)
        mutableState.value = OtaState.Preparing(ctx.total, crc16, requestedOffset = resumeGate.offset)

        try {
            val session = openSession(ctx, resumeGate)
            sessionId = session.response.sessionId
            activeSessionId = sessionId
            progress.sent = resolveStartOffset(resumeGate.offset, session.response.acceptedOffset, ctx.total)
            val setup = finalizeSessionStart(ctx, resumeGate, session, progress.sent)
            publishTransferringIfNeeded(setup, ctx)
            transferChunks(ctx, setup, progress, sessionId, read)
            return finishTransfer(ctx, progress, sessionId)
        } catch (cancellation: CancellationException) {
            handleCancellation(ctx, progress, sessionId, cancellation)
        } catch (aborted: OtaAbortedException) {
            return handleAborted(ctx, progress, aborted)
        } catch (paused: OtaPausedException) {
            return handlePaused(ctx, sessionId, paused)
        } catch (error: Throwable) {
            return handleFailure(ctx, progress, sessionId, error)
        } finally {
            progressJob?.cancel()
            progressJob = null
            activeSessionId = null
        }
    }

    /** 建立传输会话；会话一旦建立，后续偏移校验失败也必须保留 sessionId 供清理分支使用。 */
    private suspend fun openSession(
        ctx: TransferContext,
        resumeGate: ResumeGate,
    ): StartSession {
        ensureLinkAlive()
        return startSession(
            totalBytes = ctx.totalBytes,
            crc16 = ctx.crc16,
            crc32 = ctx.crc32,
            version = ctx.version,
            requestedOffset = resumeGate.offset,
            resumeSessionId = ctx.resumeFrom?.sessionId ?: 0,
        )
    }

    /** 落盘起始断点并计算续传回退原因。 */
    private suspend fun finalizeSessionStart(
        ctx: TransferContext,
        resumeGate: ResumeGate,
        session: StartSession,
        sent: Int,
    ): TransferSetup {
        // 新会话一旦建立，旧会话的断点立即失效：按设备确认值立即落盘（或清除），
        // 避免在下一个 4 KiB 检查点之前崩溃时续到旧的高偏移。
        syncCheckpointAfterStart(session.response.sessionId, sent, ctx.total, ctx.crc16, ctx.crc32, ctx.version)
        val fallbackReason =
            fallbackNote(
                requestedOffset = resumeGate.offset,
                acceptedOffset = sent,
                deviceFallbackReason = session.fallbackReason,
            ) ?: resumeGate.reason
        val effectiveChunkSize = session.response.chunkSize.takeIf { it in 1..MAX_CHUNK_SIZE } ?: chunkSize
        return TransferSetup(session.response.sessionId, sent, effectiveChunkSize, fallbackReason)
    }

    private fun publishTransferringIfNeeded(
        setup: TransferSetup,
        ctx: TransferContext,
    ) {
        if (setup.sent > 0 || setup.fallbackReason != null) {
            mutableState.value =
                OtaState.Transferring(
                    sentBytes = setup.sent,
                    totalBytes = ctx.total,
                    crc16 = ctx.crc16,
                    sessionId = setup.sessionId,
                    initialOffset = setup.sent,
                    resumeFallbackReason = setup.fallbackReason,
                )
        }
    }

    /** 分片传输循环；暂停/取消只在分片边界生效，避免把设备停在半片状态。 */
    private suspend fun transferChunks(
        ctx: TransferContext,
        setup: TransferSetup,
        progress: TransferProgress,
        sessionId: Long,
        read: suspend (offset: Long, length: Int) -> ByteArray,
    ) {
        var lastCheckpointBytes = setup.sent
        while (progress.sent < ctx.total) {
            checkAborted()
            ensureLinkAlive()
            val length = minOf(setup.effectiveChunkSize, ctx.total - progress.sent)
            val chunk = read(progress.sent.toLong(), length)
            if (chunk.size != length) {
                throw FirmwareException.ReadFailure(progress.sent.toLong(), length)
            }
            sendChunk(progress.sent, chunk)
            progress.sent += length
            progress.chunks++
            publishChunkProgress(ctx, progress, sessionId)

            if (progress.sent < ctx.total && progress.sent - lastCheckpointBytes >= CHECKPOINT_INTERVAL_BYTES) {
                saveCheckpoint(sessionId, progress.sent, ctx.total, ctx.crc16, ctx.crc32, ctx.version)
                lastCheckpointBytes = progress.sent
            }

            checkPauseOrCancel(progress.sent)
        }
    }

    /** 队列暂停/取消只在分片边界生效。 */
    private fun checkPauseOrCancel(sent: Int) {
        if (cancelSignalProvider()) {
            throw OtaAbortedException(OtaAbortReason.USER_CANCELLED)
        }
        if (pauseSignalProvider()) {
            throw OtaPausedException(sent)
        }
    }

    private fun publishChunkProgress(
        ctx: TransferContext,
        progress: TransferProgress,
        sessionId: Long?,
    ) {
        val current = mutableState.value
        mutableState.value =
            if (current is OtaState.Transferring) {
                current.copy(
                    sentBytes = progress.sent,
                    sessionId = sessionId,
                    retransmissions = retransmissions,
                )
            } else {
                OtaState.Transferring(
                    sentBytes = progress.sent,
                    totalBytes = ctx.total,
                    crc16 = ctx.crc16,
                    sessionId = sessionId,
                    retransmissions = retransmissions,
                )
            }
    }

    private suspend fun finishTransfer(
        ctx: TransferContext,
        progress: TransferProgress,
        sessionId: Long?,
    ): OtaState {
        checkAborted()
        ensureLinkAlive()
        mutableState.value =
            OtaState.Verifying(
                totalBytes = ctx.total,
                crc16 = ctx.crc16,
                sessionId = sessionId,
                retransmissions = retransmissions,
            )
        val finishResponse =
            dispatcher.execute(
                FinishOtaRequest(totalBytes = ctx.totalBytes, crc16 = ctx.crc16),
            )
        if (finishResponse !is FinishOtaResponse) {
            error("unexpected OTA finish response: ${finishResponse::class.simpleName}")
        }

        clearCheckpointStore()
        val done =
            OtaState.Succeeded(
                totalBytes = ctx.total,
                crc16 = ctx.crc16,
                chunks = progress.chunks,
                retransmissions = retransmissions,
                rebootRequired = finishResponse.rebootRequired,
            )
        mutableState.value = done
        recordOtaHistory(ctx.version, ctx.startedAt, OtaOutcome.SUCCEEDED, ctx.total, ctx.total)
        return done
    }

    private suspend fun handleCancellation(
        ctx: TransferContext,
        progress: TransferProgress,
        sessionId: Long?,
        cancellation: CancellationException,
    ): Nothing {
        persistInterruptedCheckpoint(
            sessionId,
            progress.sent,
            ctx.total,
            ctx.crc16,
            ctx.crc32,
            ctx.version,
            ctx.resumeFrom,
        )
        mutableState.value = OtaState.Aborted(OtaAbortReason.USER_CANCELLED.name, progress.sent, ctx.total)
        recordOtaHistory(
            version = ctx.version,
            startedAt = ctx.startedAt,
            outcome = OtaOutcome.ABORTED,
            sentBytes = progress.sent,
            totalBytes = ctx.total,
            detail = "cancelled",
        )
        throw cancellation
    }

    private suspend fun handleAborted(
        ctx: TransferContext,
        progress: TransferProgress,
        aborted: OtaAbortedException,
    ): OtaState {
        clearCheckpointStore()
        val state = OtaState.Aborted(aborted.reason.name, progress.sent, ctx.total)
        mutableState.value = state
        recordOtaHistory(
            version = ctx.version,
            startedAt = ctx.startedAt,
            outcome = OtaOutcome.ABORTED,
            sentBytes = progress.sent,
            totalBytes = ctx.total,
            detail = aborted.reason.name,
        )
        return state
    }

    private suspend fun handlePaused(
        ctx: TransferContext,
        sessionId: Long?,
        paused: OtaPausedException,
    ): OtaState {
        val resumePoint =
            persistInterruptedCheckpoint(
                sessionId = sessionId,
                sentBytes = paused.sentBytes,
                totalBytes = ctx.total,
                crc16 = ctx.crc16,
                crc32 = ctx.crc32,
                version = ctx.version,
                previous = ctx.resumeFrom,
            )
        lastResumePoint = resumePoint
        val state =
            OtaState.Paused(
                sentBytes = paused.sentBytes,
                totalBytes = ctx.total,
                crc16 = ctx.crc16,
                sessionId = sessionId,
                checkpointSaved = resumePoint != null,
            )
        mutableState.value = state
        recordOtaHistory(
            version = ctx.version,
            startedAt = ctx.startedAt,
            outcome = OtaOutcome.ABORTED,
            sentBytes = paused.sentBytes,
            totalBytes = ctx.total,
            detail = "queue.paused",
        )
        return state
    }

    private suspend fun handleFailure(
        ctx: TransferContext,
        progress: TransferProgress,
        sessionId: Long?,
        error: Throwable,
    ): OtaState {
        val info = error.toFailureInfo()
        val failureMessage = error.toOtaFailureMessage(info)
        val resumePoint =
            persistInterruptedCheckpoint(
                sessionId = sessionId,
                sentBytes = progress.sent,
                totalBytes = ctx.total,
                crc16 = ctx.crc16,
                crc32 = ctx.crc32,
                version = ctx.version,
                previous = ctx.resumeFrom,
            )
        lastResumePoint = resumePoint
        val state =
            OtaState.Failed(
                reason = failureMessage,
                sentBytes = progress.sent,
                totalBytes = ctx.total,
                status = info.status,
                resumePoint = resumePoint,
            )
        mutableState.value = state
        recordOtaHistory(
            version = ctx.version,
            startedAt = ctx.startedAt,
            outcome = OtaOutcome.FAILED,
            sentBytes = progress.sent,
            totalBytes = ctx.total,
            errorCode = info.status?.describe(),
            detail = failureMessage.key.name,
        )
        return state
    }

    private class TransferProgress {
        var sent: Int = 0
        var chunks: Int = 0
    }

    private class TransferContext(
        val totalBytes: Long,
        val crc16: Int,
        val crc32: Long,
        val version: FirmwareVersion,
        val resumeFrom: OtaResumePoint?,
        val startedAt: Long,
    ) {
        val total: Int = totalBytes.toInt()
    }

    private class TransferSetup(
        val sessionId: Long,
        val sent: Int,
        val effectiveChunkSize: Int,
        val fallbackReason: UiMessage?,
    )

    private suspend fun sendChunk(
        offset: Int,
        chunk: ByteArray,
    ) {
        var attempt = 0
        while (true) {
            checkAborted()
            ensureLinkAlive()
            try {
                val response = dispatcher.execute(OtaDataRequest(offset = offset.toLong(), data = chunk))
                if (response !is OtaDataResponse) {
                    throw CommandFailure.InvalidResponse(
                        "unexpected OTA chunk response: ${response::class.simpleName}",
                    )
                }
                val expected = offset + chunk.size
                if (response.receivedBytes < expected) {
                    throw CommandFailure.InvalidResponse(
                        "device acknowledged fewer bytes: ${response.receivedBytes} < $expected",
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

    private fun isRetryable(error: Throwable): Boolean =
        when (error) {
            is CommandFailure.InvalidResponse -> true
            is CommandFailure.DeviceRejected -> error.status in RETRYABLE_STATUS
            else -> false
        }

    private fun startProgressCollector() {
        val scope = scope ?: return
        progressJob?.cancel()
        progressJob =
            scope.launch {
                try {
                    dispatcher.notifications
                        .filterIsInstance<OtaProgressNotification>()
                        .collect { notification -> applyProgress(notification) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // 进度通知只是 UI 增强，收集失败不应中断传输或让协程崩溃。
                    errorReporter?.record("ota-progress", error)
                }
            }
    }

    private fun applyProgress(notification: OtaProgressNotification) {
        if (notification.sessionId != activeSessionId) return
        mutableState.update { current ->
            if (current is OtaState.Transferring) {
                current.copy(
                    sentBytes =
                        maxOf(
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
        val reason = abortMutex.withLock { abortReason }
        if (reason != null) throw OtaAbortedException(reason)
        if (cancelSignalProvider()) throw OtaAbortedException(OtaAbortReason.USER_CANCELLED)
    }

    private fun ensureLinkAlive() {
        if (connectionState != null && !connectionState.value) {
            throw BleException("链路已断开")
        }
    }

    private suspend fun startSession(
        totalBytes: Long,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
        requestedOffset: Int,
        resumeSessionId: Long,
    ): StartSession {
        if (requestedOffset > 0) {
            try {
                val response =
                    executeStart(
                        totalBytes = totalBytes,
                        crc16 = crc16,
                        version = version,
                        resumeSessionId = resumeSessionId,
                        resumeOffset = requestedOffset.toLong(),
                        crc32 = crc32,
                    )
                return StartSession(response)
            } catch (error: CommandFailure.DeviceRejected) {
                // 旧设备不识别尾部扩展，或设备侧续传上下文失效：回退为旧格式并从 0 开始。
                val reason =
                    UiMessage(
                        StringKey.OTA_FALLBACK_REJECTED,
                        mapOf("detail" to error.status.describe()),
                    )
                val legacy =
                    executeStart(
                        totalBytes = totalBytes,
                        crc16 = crc16,
                        version = version,
                        resumeSessionId = 0,
                        resumeOffset = 0,
                        crc32 = 0,
                    )
                return StartSession(legacy, reason)
            }
        }
        return StartSession(
            executeStart(
                totalBytes = totalBytes,
                crc16 = crc16,
                version = version,
                resumeSessionId = 0,
                resumeOffset = 0,
                crc32 = 0,
            ),
        )
    }

    private suspend fun executeStart(
        totalBytes: Long,
        crc16: Int,
        version: FirmwareVersion,
        resumeSessionId: Long,
        resumeOffset: Long,
        crc32: Long,
    ): StartOtaResponse {
        val response =
            dispatcher.execute(
                StartOtaRequest(
                    totalBytes = totalBytes,
                    crc16 = crc16,
                    chunkSize = chunkSize,
                    firmwareVersion = version,
                    resumeSessionId = resumeSessionId,
                    resumeOffset = resumeOffset,
                    payloadCrc32 = crc32,
                ),
            )
        return response as? StartOtaResponse
            ?: throw CommandFailure.InvalidResponse(
                "unexpected OTA start response: ${response::class.simpleName}",
            )
    }

    /**
     * 设备确认偏移的合法性与语义：
     *
     * - `accepted >= total`：设备应答非法（没有任何可传输内容）；
     * - 未请求续传却返回非 0 偏移：设备应答非法；
     * - `accepted > requested`：设备声称的起点晚于本地请求，说明上下文错乱，直接失败；
     * - `0 <= accepted <= requested`：以设备为准，0 表示设备要求从头开始。
     */
    private fun resolveStartOffset(
        requestedOffset: Int,
        acceptedOffset: Long,
        totalBytes: Int,
    ): Int {
        if (acceptedOffset >= totalBytes) {
            throw OtaValidationException(
                UiMessage.of(
                    StringKey.OTA_REASON_OFFSET_TOO_LARGE,
                    "offset" to acceptedOffset.toString(),
                    "total" to totalBytes.toString(),
                ),
            )
        }
        if (requestedOffset <= 0) {
            if (acceptedOffset > 0) {
                throw OtaValidationException(
                    UiMessage.of(
                        StringKey.OTA_REASON_OFFSET_UNEXPECTED,
                        "offset" to acceptedOffset.toString(),
                    ),
                )
            }
            return 0
        }
        if (acceptedOffset > requestedOffset.toLong()) {
            throw OtaValidationException(
                UiMessage.of(
                    StringKey.OTA_REASON_OFFSET_GREATER_THAN_REQUESTED,
                    "accepted" to acceptedOffset.toString(),
                    "requested" to requestedOffset.toString(),
                ),
            )
        }
        return acceptedOffset.toInt()
    }

    private fun fallbackNote(
        requestedOffset: Int,
        acceptedOffset: Int,
        deviceFallbackReason: UiMessage?,
    ): UiMessage? {
        if (deviceFallbackReason != null) return deviceFallbackReason
        if (requestedOffset <= 0) return null
        return when {
            acceptedOffset == 0 -> UiMessage(StringKey.OTA_FALLBACK_NOT_ACCEPTED)
            acceptedOffset < requestedOffset ->
                UiMessage(
                    StringKey.OTA_FALLBACK_LOWERED,
                    mapOf(
                        "accepted" to acceptedOffset.toString(),
                        "requested" to requestedOffset.toString(),
                    ),
                )
            else -> null
        }
    }

    private fun persistInterruptedCheckpoint(
        sessionId: Long?,
        sentBytes: Int,
        totalBytes: Int,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
        previous: OtaResumePoint?,
    ): OtaResumePoint? {
        val point = sessionId?.let { pointOf(it, sentBytes, totalBytes, crc16, crc32, version) }
        return when {
            // 已建立新会话且传输过至少一个字节：落盘精确断点。
            point != null -> {
                saveCheckpoint(point)
                point
            }
            // 新会话已建立但尚未传输：旧会话上下文失效，清除旧断点。
            sessionId != null -> {
                clearCheckpointStore()
                null
            }
            // 会话尚未建立：保留调用方传入的旧断点，便于下次重试。
            else -> previous?.takeIf { it.isValid() }
        }
    }

    /**
     * 续传前置检查：返回可请求的偏移与不可续传时的原因。
     * 调用方传入的断点若已失效或与当前固件不一致，仍从头开始，但会在 UI 上记录原因。
     */
    private fun checkResumeGate(
        resumeFrom: OtaResumePoint?,
        totalBytes: Long,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
    ): ResumeGate {
        val point = resumeFrom ?: return ResumeGate(0, null)
        if (!point.isValid()) {
            return ResumeGate(0, UiMessage(StringKey.RESUME_INVALID))
        }
        if (!point.matchesPayload(version, totalBytes, crc32, crc16)) {
            return ResumeGate(0, UiMessage(StringKey.RESUME_MISMATCH))
        }
        return ResumeGate(point.sentBytes, null)
    }

    /** 新会话建立后立即把断点同步为设备确认的起点；起点为 0 时清除旧断点。 */
    private fun syncCheckpointAfterStart(
        sessionId: Long,
        sentBytes: Int,
        totalBytes: Int,
        crc16: Int,
        crc32: Long,
        version: FirmwareVersion,
    ) {
        if (sentBytes > 0) {
            saveCheckpoint(sessionId, sentBytes, totalBytes, crc16, crc32, version)
        } else {
            clearCheckpointStore()
        }
    }

    private fun clearCheckpointStore() {
        runCatching { checkpointStore?.clear() }
    }

    /** 把传输失败翻译成可操作的提示；拒绝类失败优先展示设备状态码。 */
    private fun Throwable.toOtaFailureMessage(failure: CommandFailureInfo): UiMessage =
        when (this) {
            is OtaValidationException -> uiMessage
            is BleException -> UiMessage(StringKey.OTA_REASON_DISCONNECTED)
            is FirmwareException.ReadFailure ->
                UiMessage(
                    StringKey.OTA_REASON_READ_FIRMWARE,
                    mapOf("offset" to offset.toString(), "length" to length.toString()),
                )
            is CommandFailure.Timeout ->
                UiMessage(
                    StringKey.OTA_REASON_TIMEOUT,
                    mapOf("attempts" to attempts.toString()),
                )
            is CommandFailure.TransportFailure ->
                UiMessage(
                    StringKey.OTA_REASON_TRANSPORT,
                    mapOf("attempts" to attempts.toString()),
                )
            else ->
                UiMessage(
                    StringKey.OTA_REASON_DEVICE_REJECTED,
                    nested = mapOf("detail" to (failure.statusUi ?: failure.ui)),
                )
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

    /**
     * OTA 终态写入设备档案；未配置设备 id / 仓储或写入失败时静默跳过，不影响升级结果。
     */
    private fun recordOtaHistory(
        version: FirmwareVersion,
        startedAt: Long,
        outcome: OtaOutcome,
        sentBytes: Int,
        totalBytes: Int,
        errorCode: String? = null,
        detail: String? = null,
    ) {
        val repository = profileRepository ?: return
        val id = deviceId ?: return
        val finishedAt = clockMillis()
        runCatching {
            repository.recordOta(
                deviceId = id,
                record =
                    OtaHistoryRecord(
                        firmwareVersion = version.toString(),
                        finishedAtEpochMillis = finishedAt,
                        durationMillis = (finishedAt - startedAt).coerceAtLeast(0),
                        outcome = outcome,
                        errorCode = errorCode,
                        detail = detail,
                        sentBytes = sentBytes,
                        totalBytes = totalBytes,
                    ),
            )
        }
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
    ): OtaResumePoint? =
        OtaResumePoint(
            sessionId = sessionId,
            sentBytes = sentBytes,
            totalBytes = totalBytes,
            crc16 = crc16,
            firmwareVersion = version,
            crc32 = crc32,
        ).takeIf { it.isValid() }

    private fun failed(
        reason: UiMessage,
        sentBytes: Int,
        totalBytes: Int,
    ): OtaState.Failed {
        val state = OtaState.Failed(reason, sentBytes, totalBytes)
        mutableState.value = state
        return state
    }

    private class OtaAbortedException(
        val reason: OtaAbortReason,
    ) : Exception("OTA 已中止：${reason.name}")

    /** 队列暂停信号在分片边界触发，携带已确认的字节数用于落盘断点。 */
    private class OtaPausedException(
        val sentBytes: Int,
    ) : Exception("OTA 队列暂停")

    /** 一次 START_OTA 的结果；[fallbackReason] 非空表示续传被拒后回退为从头开始。 */
    private data class StartSession(
        val response: StartOtaResponse,
        val fallbackReason: UiMessage? = null,
    )

    /** 续传前置检查结果：[offset] 为 0 且 [reason] 非空表示请求了续传但被本地门控拒绝。 */
    private data class ResumeGate(
        val offset: Int,
        val reason: UiMessage?,
    )

    /** 续传偏移校验失败：直接携带可展示文案，避免上层再做字符串匹配。 */
    private class OtaValidationException(
        val uiMessage: UiMessage,
    ) : Exception(uiMessage.key.name)

    private fun OtaState.sentBytes(): Int =
        when (this) {
            is OtaState.Preparing -> 0
            is OtaState.Transferring -> sentBytes
            is OtaState.Verifying -> totalBytes
            is OtaState.Paused -> sentBytes
            else -> 0
        }

    private fun OtaState.totalBytes(): Int =
        when (this) {
            is OtaState.Preparing -> totalBytes
            is OtaState.Transferring -> totalBytes
            is OtaState.Verifying -> totalBytes
            is OtaState.Paused -> totalBytes
            else -> 0
        }

    private companion object {
        const val CRC_CHUNK_SIZE: Int = 64 * 1024
        const val CHECKPOINT_INTERVAL_BYTES: Int = 4 * 1024

        val RETRYABLE_STATUS =
            setOf(
                DeviceStatus.BUSY,
                DeviceStatus.CRC_MISMATCH,
                DeviceStatus.INTERNAL_ERROR,
            )
    }
}
