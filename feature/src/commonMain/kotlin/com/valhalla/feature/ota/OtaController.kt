package com.valhalla.feature.ota

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FinishOtaResponse
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.protocol.message.StartOtaResponse
import com.valhalla.feature.toFailureInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** OTA 流程状态。 */
sealed interface OtaState {

    data object Idle : OtaState

    data class Preparing(val totalBytes: Int, val crc16: Int) : OtaState

    data class Transferring(
        val sentBytes: Int,
        val totalBytes: Int,
        val crc16: Int,
    ) : OtaState {
        val progress: Float
            get() = if (totalBytes == 0) 0f else sentBytes.toFloat() / totalBytes
    }

    data class Verifying(val totalBytes: Int, val crc16: Int) : OtaState

    data class Succeeded(val totalBytes: Int, val crc16: Int, val chunks: Int) : OtaState

    data class Failed(val reason: String, val sentBytes: Int) : OtaState
}

/**
 * OTA 传输控制器：START_OTA → 逐片 OTA_DATA → FINISH_OTA。
 *
 * 所有控制与数据指令都经 [ConcurrentCommandDispatcher] 以强类型消息发送，
 * 由调度器负责序号分配、超时重试与错误码翻译；本类只维护 OTA 会话状态与进度。
 * 设备可在 [StartOtaResponse] 中返回实际分片大小，控制器以其为准重新切片。
 */
class OtaController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val chunkSize: Int = 64,
    private val firmwareVersion: FirmwareVersion = FirmwareVersion(1, 0, 0, 0, "release"),
) {

    init {
        require(chunkSize in 1..1024) { "chunkSize 需在 1..1024 之间" }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = mutableState.asStateFlow()

    /** 执行一次完整升级：开始会话、按协议偏移分片、结束并校验。 */
    suspend fun start(firmware: ByteArray): OtaState = mutex.withLock {
        if (firmware.isEmpty()) {
            return failed("固件包为空", 0)
        }

        val crc = Crc16.ccittFalse(firmware)
        mutableState.value = OtaState.Preparing(firmware.size, crc)

        var sent = 0
        var chunks = 0
        try {
            val startResponse = dispatcher.execute(
                StartOtaRequest(
                    totalBytes = firmware.size.toLong(),
                    crc16 = crc,
                    chunkSize = chunkSize,
                    firmwareVersion = firmwareVersion,
                ),
            )
            if (startResponse !is StartOtaResponse) {
                error("开始 OTA 应答类型异常: ${startResponse::class.simpleName}")
            }
            val effectiveChunkSize = startResponse.chunkSize.takeIf { it in 1..1024 } ?: chunkSize

            while (sent < firmware.size) {
                val end = minOf(sent + effectiveChunkSize, firmware.size)
                val response = dispatcher.execute(
                    OtaDataRequest(offset = sent.toLong(), data = firmware.copyOfRange(sent, end)),
                )
                if (response !is OtaDataResponse) {
                    error("OTA 分片应答类型异常: ${response::class.simpleName}")
                }
                sent = end
                chunks++
                mutableState.value = OtaState.Transferring(sent, firmware.size, crc)
            }

            mutableState.value = OtaState.Verifying(firmware.size, crc)
            val finishResponse = dispatcher.execute(
                FinishOtaRequest(totalBytes = firmware.size.toLong(), crc16 = crc),
            )
            if (finishResponse !is FinishOtaResponse) {
                error("结束 OTA 应答类型异常: ${finishResponse::class.simpleName}")
            }

            val done = OtaState.Succeeded(firmware.size, crc, chunks)
            mutableState.value = done
            done
        } catch (cancellation: CancellationException) {
            mutableState.value = OtaState.Failed("OTA 已取消", sent)
            throw cancellation
        } catch (error: Throwable) {
            failed(error.toFailureInfo().message, sent)
        }
    }

    fun reset() {
        mutableState.value = OtaState.Idle
    }

    private fun failed(reason: String, sentBytes: Int): OtaState.Failed {
        val state = OtaState.Failed(reason, sentBytes)
        mutableState.value = state
        return state
    }
}
