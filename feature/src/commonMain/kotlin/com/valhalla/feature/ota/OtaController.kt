package com.valhalla.feature.ota

import com.valhalla.core.protocol.Crc16
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleUuids
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
 * OTA 传输控制器（占位实现）。
 *
 * 当前版本完成「分片发送 + 进度 + CRC 记录」，真实设备协议落地后需要补充：
 * - START_OTA / FINISH_OTA 控制指令与设备校验回执；
 * - 断点续传与固件签名验证；
 * - 失败重试与回滚。
 */
class OtaController(
    private val connection: BleConnection,
    private val chunkSize: Int = 64,
    private val serviceUuid: String = BleUuids.OTA_SERVICE,
    private val dataCharacteristicUuid: String = BleUuids.OTA_DATA,
) {

    init {
        require(chunkSize in 1..1024) { "chunkSize 需在 1..1024 之间" }
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = mutableState.asStateFlow()

    /** 将固件按 [chunkSize] 分片写入，返回最终状态。 */
    suspend fun start(firmware: ByteArray): OtaState = mutex.withLock {
        if (firmware.isEmpty()) {
            return failed("固件包为空", 0)
        }

        val crc = Crc16.ccittFalse(firmware)
        mutableState.value = OtaState.Preparing(firmware.size, crc)

        var sent = 0
        try {
            var chunks = 0
            while (sent < firmware.size) {
                val end = minOf(sent + chunkSize, firmware.size)
                val chunk = firmware.copyOfRange(sent, end)
                connection.write(
                    serviceUuid = serviceUuid,
                    characteristicUuid = dataCharacteristicUuid,
                    payload = chunk,
                    withResponse = false,
                )
                sent = end
                chunks++
                mutableState.value = OtaState.Transferring(sent, firmware.size, crc)
            }

            mutableState.value = OtaState.Verifying(firmware.size, crc)
            val done = OtaState.Succeeded(firmware.size, crc, chunks)
            mutableState.value = done
            done
        } catch (cancellation: CancellationException) {
            mutableState.value = OtaState.Failed("OTA 已取消", sent)
            throw cancellation
        } catch (error: Throwable) {
            failed(error.message ?: "OTA 传输失败", sent)
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
