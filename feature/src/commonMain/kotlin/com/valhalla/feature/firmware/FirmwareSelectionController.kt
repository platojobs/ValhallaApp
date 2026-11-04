package com.valhalla.feature.firmware

import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwarePrecheckResult
import com.valhalla.core.firmware.FirmwarePrechecker
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 固件选择与本地预检的 UI 状态。 */
data class FirmwareSelectionState(
    val picking: Boolean = false,
    val packageName: String? = null,
    val version: FirmwareVersion? = null,
    val payloadBytes: Long = 0,
    /** 文件头声明的 CRC-32。 */
    val declaredCrc32: Long? = null,
    /** 预检实际重算出的 CRC-32；读取失败时为 null。 */
    val computedCrc32: Long? = null,
    val precheck: FirmwarePrecheckResult? = null,
    val resumePoint: OtaResumePoint? = null,
    val error: String? = null,
    val errorCode: String? = null,
) {
    val selected: Boolean get() = packageName != null

    /** 只有预检全部通过才允许启动 OTA。 */
    val readyForOta: Boolean get() = precheck?.passed == true

    /** 有可用的历史断点（预检通过且断点与当前固件一致）。 */
    val canResume: Boolean get() = readyForOta && resumePoint != null
}

/**
 * 固件选择控制器：选择文件 → 解析文件头 → 本地预检 → 暴露可续传断点。
 *
 * 该控制器持有 [FirmwarePackage] 的生命周期；页面销毁、切换文件或清除选择时关闭数据源。
 * OTA 控制器只借用固件包，不负责关闭。
 */
class FirmwareSelectionController(
    private val picker: FirmwarePicker,
    private val checkpointStore: OtaCheckpointStore,
    private val prechecker: FirmwarePrechecker = FirmwarePrechecker(),
) {

    private val mutableState = MutableStateFlow(FirmwareSelectionState())
    val state: StateFlow<FirmwareSelectionState> = mutableState.asStateFlow()

    var firmware: FirmwarePackage? = null
        private set

    /**
     * 打开选择器并预检。用户取消时保留上一次选择并返回 false。
     *
     * @param currentVersion 设备当前固件版本（来自 GET_FIRMWARE_VERSION 应答）
     * @param currentModel 设备型号（来自 GET_DEVICE_INFO 应答）
     */
    suspend fun pickAndPrecheck(
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): Boolean {
        if (mutableState.value.picking) return false
        mutableState.update { it.copy(picking = true, error = null, errorCode = null) }

        val picked = try {
            picker.pickFirmware()
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(picking = false) }
            throw cancellation
        } catch (error: Throwable) {
            fail(error)
            return false
        }

        if (picked == null) {
            mutableState.update { it.copy(picking = false) }
            return false
        }

        return loadAndCheck(picked, currentVersion, currentModel)
    }

    /** 设备信息变化后重新预检当前固件。 */
    suspend fun recheck(currentVersion: FirmwareVersion?, currentModel: String?): Boolean {
        val current = firmware ?: return false
        val result = prechecker.check(current, currentVersion, currentModel)
        mutableState.update {
            it.copy(
                picking = false,
                precheck = result,
                computedCrc32 = result.payloadCrc32,
                resumePoint = resolveResumePoint(current),
                error = null,
                errorCode = null,
            )
        }
        return result.passed
    }

    /** 从持久化存储重新加载断点，并按当前固件校验。 */
    fun refreshResume() {
        mutableState.update { it.copy(resumePoint = firmware?.let(::resolveResumePoint)) }
    }

    /** 清除断点（成功升级、用户放弃续传或存储损坏时使用）。 */
    fun clearResume() {
        checkpointStore.clear()
        mutableState.update { it.copy(resumePoint = null) }
    }

    /** 当前固件对应的有效断点；没有选择或校验失败时返回 null。 */
    fun resumePointForCurrentFirmware(): OtaResumePoint? {
        val current = firmware ?: return null
        return resolveResumePoint(current)
    }

    /** 放弃当前选择并关闭数据源，保留（已校验的）断点。 */
    fun clearSelection() {
        firmware?.close()
        firmware = null
        mutableState.update {
            FirmwareSelectionState(
                picking = false,
                resumePoint = checkpointStore.load()?.takeIf { it.isValid() },
            )
        }
    }

    /** 页面销毁时调用。 */
    fun close() {
        firmware?.close()
        firmware = null
    }

    private suspend fun loadAndCheck(
        picked: PickedFirmware,
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): Boolean {
        return try {
            firmware?.close()
            firmware = null
            val parsed = FirmwareParser.parse(picked.name, picked.source)
            firmware = parsed

            val result = prechecker.check(parsed, currentVersion, currentModel)
            mutableState.value = FirmwareSelectionState(
                picking = false,
                packageName = picked.name,
                version = parsed.version,
                payloadBytes = parsed.payloadLength,
                declaredCrc32 = parsed.payloadCrc32,
                computedCrc32 = result.payloadCrc32,
                precheck = result,
                resumePoint = checkpointStore.load()
                    ?.takeIf { it.isValid() && it.matchesFirmware(parsed) },
            )
            result.passed
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(picking = false) }
            throw cancellation
        } catch (error: Throwable) {
            val parsed = firmware
            if (parsed != null) {
                parsed.close()
            } else {
                runCatching { picked.source.close() }
            }
            firmware = null
            fail(error)
            false
        }
    }

    private fun fail(error: Throwable) {
        val message = when (error) {
            is FirmwareException -> error.message ?: "固件预检失败"
            else -> error.message ?: "固件读取失败"
        }
        val code = (error as? FirmwareException)?.code
        mutableState.update {
            it.copy(
                picking = false,
                precheck = null,
                error = message,
                errorCode = code,
            )
        }
    }

    private fun resolveResumePoint(firmware: FirmwarePackage): OtaResumePoint? =
        checkpointStore.load()?.takeIf { it.isValid() && it.matchesFirmware(firmware) }
}
