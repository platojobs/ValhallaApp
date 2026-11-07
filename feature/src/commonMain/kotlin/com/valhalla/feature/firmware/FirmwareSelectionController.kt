package com.valhalla.feature.firmware

import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePackage
import com.valhalla.core.firmware.FirmwareParser
import com.valhalla.core.firmware.FirmwarePrecheckCancelledException
import com.valhalla.core.firmware.FirmwarePrecheckProgress
import com.valhalla.core.firmware.FirmwarePrecheckResult
import com.valhalla.core.firmware.FirmwarePrechecker
import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 固件选择与本地预检的 UI 状态。 */
data class FirmwareSelectionState(
    val picking: Boolean = false,
    /** 是否正在扫描 payload（解析已完成，正在做 CRC/签名/CRC16 预检）。 */
    val prechecking: Boolean = false,
    /** 预检扫描进度 0..1；仅在 [prechecking] 时有意义。 */
    val precheckProgress: Float = 0f,
    val packageName: String? = null,
    val version: FirmwareVersion? = null,
    val payloadBytes: Long = 0,
    /** 平台持久引用（固件库复用）；仅在记录元信息时使用。 */
    val sourcePath: String? = null,
    /** 文件头声明的 CRC-32。 */
    val declaredCrc32: Long? = null,
    /** 预检实际重算出的 CRC-32；读取失败时为 null。 */
    val computedCrc32: Long? = null,
    /** 预检时顺带计算的协议层 CRC-16，用于断点续传比对；读取失败时为 null。 */
    val computedCrc16: Int? = null,
    val precheck: FirmwarePrecheckResult? = null,
    val resumePoint: OtaResumePoint? = null,
    /** 存在断点但无法续传时的原因；没有断点或可续传时为 null。 */
    val resumeBlockedReason: UiMessage? = null,
    val error: UiMessage? = null,
    val errorCode: String? = null,
) {
    val selected: Boolean get() = packageName != null

    /** 只有预检全部通过且没有正在进行的预检才允许启动 OTA。 */
    val readyForOta: Boolean get() = !picking && !prechecking && precheck?.passed == true

    /** 有可用的历史断点（预检通过且断点与当前固件一致）。 */
    val canResume: Boolean get() = readyForOta && resumePoint != null
}

/**
 * 固件选择控制器：选择文件 → 解析文件头 → 本地预检 → 暴露可续传断点。
 *
 * 关键约束：
 * - 新文件解析与预检成功前不关闭上一次固件，失败或取消时可回滚到上一次可用选择；
 * - 解析与扫描在 [Dispatchers.Default] 上执行，大文件不会阻塞 UI；
 * - 预检可被 [cancelPrecheck] 取消，取消后保留上一次可用选择；
 * - 同一时刻只允许一个选择/预检操作（[operationMutex]）。
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

    private val operationMutex = Mutex()

    /** 由 [cancelPrecheck] 置位；预检扫描在每个分块间读取。 */
    private val cancelRequested = MutableStateFlow(false)

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
        if (!operationMutex.tryLock()) return false
        try {
            if (mutableState.value.picking || mutableState.value.prechecking) return false
            mutableState.update {
                it.copy(
                    picking = true,
                    prechecking = false,
                    precheckProgress = 0f,
                    error = null,
                    errorCode = null,
                )
            }

            val picked =
                try {
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

            cancelRequested.value = false
            mutableState.update {
                it.copy(picking = false, prechecking = true, precheckProgress = 0f)
            }
            return loadAndCheck(picked, currentVersion, currentModel)
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * 按固件库记录中的路径重新打开并预检（等效于再次选择同一文件）。
     * 文件已删除/移动/权限失效时返回 false，并保留上一次可用选择。
     */
    suspend fun openFromLibrary(
        sourcePath: String,
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): Boolean {
        if (!operationMutex.tryLock()) return false
        try {
            if (mutableState.value.picking || mutableState.value.prechecking) return false
            mutableState.update {
                it.copy(
                    picking = true,
                    prechecking = false,
                    precheckProgress = 0f,
                    error = null,
                    errorCode = null,
                )
            }
            val picked =
                try {
                    picker.openFirmware(sourcePath)
                } catch (cancellation: CancellationException) {
                    mutableState.update { it.copy(picking = false) }
                    throw cancellation
                } catch (error: Throwable) {
                    mutableState.update { it.copy(picking = false) }
                    fail(error)
                    return false
                }
            if (picked == null) {
                mutableState.update {
                    it.copy(
                        picking = false,
                        error =
                            UiMessage(
                                StringKey.FIRMWARE_FILE_READ_FAILED,
                                mapOf("detail" to "firmware.library.unavailable"),
                            ),
                        errorCode = "firmware.library.unavailable",
                    )
                }
                return false
            }
            cancelRequested.value = false
            mutableState.update {
                it.copy(picking = false, prechecking = true, precheckProgress = 0f)
            }
            return loadAndCheck(picked, currentVersion, currentModel)
        } finally {
            operationMutex.unlock()
        }
    }

    /** 用户取消正在进行的预检；取消后保留上一次可用固件。 */
    fun cancelPrecheck() {
        if (mutableState.value.prechecking) {
            cancelRequested.value = true
        }
    }

    /** 设备信息变化后重新预检当前固件。预检期间保留旧结果但禁用 OTA。 */
    suspend fun recheck(
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): Boolean {
        val current = firmware ?: return false
        if (!operationMutex.tryLock()) return false
        try {
            if (mutableState.value.picking || mutableState.value.prechecking) return false
            cancelRequested.value = false
            mutableState.update {
                it.copy(prechecking = true, precheckProgress = 0f, error = null, errorCode = null)
            }
            val result =
                withContext(Dispatchers.Default) {
                    prechecker.check(
                        firmware = current,
                        currentVersion = currentVersion,
                        currentModel = currentModel,
                        onProgress = ::publishProgress,
                        shouldCancel = { cancelRequested.value },
                    )
                }
            val resume = resolveResume(current, result)
            mutableState.update {
                it.copy(
                    picking = false,
                    prechecking = false,
                    precheckProgress = 0f,
                    precheck = result,
                    computedCrc32 = result.payloadCrc32,
                    computedCrc16 = result.payloadCrc16,
                    resumePoint = resume.point,
                    resumeBlockedReason = resume.blockedReason,
                    error = null,
                    errorCode = null,
                )
            }
            return result.passed
        } catch (cancelled: FirmwarePrecheckCancelledException) {
            mutableState.update { it.copy(prechecking = false, precheckProgress = 0f) }
            return false
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(prechecking = false, precheckProgress = 0f) }
            throw cancellation
        } catch (error: Throwable) {
            mutableState.update { it.copy(prechecking = false, precheckProgress = 0f) }
            fail(error)
            return false
        } finally {
            operationMutex.unlock()
        }
    }

    /** 从持久化存储重新加载断点，并按当前固件校验。 */
    fun refreshResume() {
        val current = firmware
        val resolution =
            if (current == null) {
                ResumeResolution(null, null)
            } else {
                resolveResume(current, mutableState.value.precheck)
            }
        mutableState.update {
            it.copy(resumePoint = resolution.point, resumeBlockedReason = resolution.blockedReason)
        }
    }

    /** 清除断点（成功升级、用户放弃续传或存储损坏时使用）。 */
    fun clearResume() {
        runCatching { checkpointStore.clear() }
        mutableState.update { it.copy(resumePoint = null, resumeBlockedReason = null) }
    }

    /** 当前固件对应的有效断点；没有选择或校验失败时返回 null。 */
    fun resumePointForCurrentFirmware(): OtaResumePoint? {
        val current = firmware ?: return null
        return resolveResume(current, mutableState.value.precheck).point
    }

    /** 放弃当前选择并关闭数据源，保留（已校验的）断点。 */
    fun clearSelection() {
        cancelRequested.value = true
        if (mutableState.value.prechecking) return
        firmware?.close()
        firmware = null
        mutableState.value =
            FirmwareSelectionState(
                picking = false,
                resumePoint = checkpointStore.load()?.takeIf { it.isValid() },
            )
    }

    /** 页面销毁时调用。 */
    fun close() {
        cancelRequested.value = true
        firmware?.close()
        firmware = null
    }

    private suspend fun loadAndCheck(
        picked: PickedFirmware,
        currentVersion: FirmwareVersion?,
        currentModel: String?,
    ): Boolean {
        var parsed: FirmwarePackage? = null
        return try {
            val outcome =
                withContext(Dispatchers.Default) {
                    val packageUnderTest = FirmwareParser.parse(picked.name, picked.source)
                    parsed = packageUnderTest
                    ParsedPrecheck(
                        firmware = packageUnderTest,
                        result =
                            try {
                                prechecker.check(
                                    firmware = packageUnderTest,
                                    currentVersion = currentVersion,
                                    currentModel = currentModel,
                                    onProgress = ::publishProgress,
                                    shouldCancel = { cancelRequested.value },
                                )
                            } catch (error: Throwable) {
                                packageUnderTest.close()
                                parsed = null
                                throw error
                            },
                    )
                }

            val resume = resolveResume(outcome.firmware, outcome.result)
            val previous = firmware
            firmware = outcome.firmware
            previous?.close()
            mutableState.value =
                FirmwareSelectionState(
                    picking = false,
                    prechecking = false,
                    precheckProgress = 0f,
                    packageName = picked.name,
                    version = outcome.firmware.version,
                    payloadBytes = outcome.firmware.payloadLength,
                    sourcePath = picked.sourcePath,
                    declaredCrc32 = outcome.firmware.payloadCrc32,
                    computedCrc32 = outcome.result.payloadCrc32,
                    computedCrc16 = outcome.result.payloadCrc16,
                    precheck = outcome.result,
                    resumePoint = resume.point,
                    resumeBlockedReason = resume.blockedReason,
                )
            outcome.result.passed
        } catch (cancelled: FirmwarePrecheckCancelledException) {
            closeParsedOrSource(parsed, picked)
            mutableState.update {
                it.copy(picking = false, prechecking = false, precheckProgress = 0f)
            }
            false
        } catch (cancellation: CancellationException) {
            closeParsedOrSource(parsed, picked)
            mutableState.update {
                it.copy(picking = false, prechecking = false, precheckProgress = 0f)
            }
            throw cancellation
        } catch (error: Throwable) {
            closeParsedOrSource(parsed, picked)
            fail(error)
            false
        }
    }

    private fun closeParsedOrSource(
        parsed: FirmwarePackage?,
        picked: PickedFirmware,
    ) {
        if (parsed != null) {
            parsed.close()
        } else {
            runCatching { picked.source.close() }
        }
    }

    private fun publishProgress(progress: FirmwarePrecheckProgress) {
        mutableState.update {
            if (it.prechecking) it.copy(precheckProgress = progress.fraction) else it
        }
    }

    private fun fail(error: Throwable) {
        val message =
            when (error) {
                is FirmwareException ->
                    UiMessage(
                        StringKey.FIRMWARE_LOAD_FAILED,
                        mapOf("detail" to (error.message ?: error.code)),
                    )
                else ->
                    UiMessage(
                        StringKey.FIRMWARE_FILE_READ_FAILED,
                        mapOf("detail" to (error.message ?: StringKey.UNKNOWN.name)),
                    )
            }
        val code = (error as? FirmwareException)?.code
        mutableState.update {
            it.copy(
                picking = false,
                prechecking = false,
                precheckProgress = 0f,
                error = message,
                errorCode = code,
            )
        }
    }

    private data class ParsedPrecheck(
        val firmware: FirmwarePackage,
        val result: FirmwarePrecheckResult,
    )

    private data class ResumeResolution(
        val point: OtaResumePoint?,
        val blockedReason: UiMessage?,
    )

    /**
     * 续传门控：断点必须同时满足
     *
     * 1. 断点字段合法；
     * 2. 固件标识一致（版本号（忽略渠道）/ payload 总长 / CRC-32）；
     * 3. 本地预检通过；
     * 4. 协议层 CRC-16 与断点记录一致（固件内容未变）。
     *
     * 任一不满足都不进入续传分支，并通过 [ResumeResolution.blockedReason] 给出原因。
     * 断点本身保留，便于用户切回原固件后继续使用，或手动清除。
     */
    private fun resolveResume(
        firmware: FirmwarePackage,
        precheck: FirmwarePrecheckResult?,
    ): ResumeResolution {
        val stored = checkpointStore.load() ?: return ResumeResolution(null, null)
        if (!stored.isValid()) {
            return ResumeResolution(null, UiMessage(StringKey.RESUME_INVALID))
        }
        if (!stored.matchesFirmware(firmware)) {
            return ResumeResolution(null, UiMessage(StringKey.RESUME_MISMATCH))
        }
        if (precheck?.passed != true) {
            return ResumeResolution(null, UiMessage(StringKey.RESUME_PRECHECK_BLOCKED))
        }
        val crc16 =
            precheck.payloadCrc16
                ?: return ResumeResolution(null, UiMessage(StringKey.RESUME_CRC16_MISSING))
        if (stored.crc16 != crc16) {
            return ResumeResolution(null, UiMessage(StringKey.RESUME_CRC16_MISMATCH))
        }
        return ResumeResolution(stored, null)
    }
}
