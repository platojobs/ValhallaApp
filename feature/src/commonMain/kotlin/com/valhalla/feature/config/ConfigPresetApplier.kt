package com.valhalla.feature.config

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ConfigValidation
import com.valhalla.core.config.PresetFieldDiff
import com.valhalla.core.config.PresetFieldStatus
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import kotlinx.coroutines.CancellationException

/** 单键写入结果。 */
enum class PresetApplyStatus {
    WRITTEN,
    ALREADY_TARGET,
    SKIPPED,
    FAILED,
}

data class PresetApplyResult(
    val key: String,
    val status: PresetApplyStatus,
    val errorCode: String? = null,
    val detail: String? = null,
) {
    val succeeded: Boolean get() =
        status == PresetApplyStatus.WRITTEN ||
            status == PresetApplyStatus.ALREADY_TARGET
}

/** 一次预设应用的结果；部分失败不会抛出。 */
data class PresetApplyReport(
    val results: List<PresetApplyResult>,
    val cancelled: Boolean = false,
) {
    val writtenCount: Int get() = results.count { it.status == PresetApplyStatus.WRITTEN }

    val alreadyCount: Int get() = results.count { it.status == PresetApplyStatus.ALREADY_TARGET }

    val skippedCount: Int get() = results.count { it.status == PresetApplyStatus.SKIPPED }

    val failedCount: Int get() = results.count { it.status == PresetApplyStatus.FAILED }

    val allSucceeded: Boolean
        get() = !cancelled && failedCount == 0
}

/**
 * 逐键写入预设。
 *
 * - 能力不满足/取值非法的键在写入前跳过，不整包失败；
 * - 每个键独立下发与结算，失败键保留设备错误码，后续键继续；
 * - 只写 `WILL_CHANGE` 的键，已等于目标值的键不重复下发。
 */
class ConfigPresetApplier(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val catalog: ConfigCatalog = ConfigCatalog,
) {
    suspend fun apply(diffs: List<PresetFieldDiff>): PresetApplyReport {
        val results = mutableListOf<PresetApplyResult>()
        for (diff in diffs) {
            when (diff.status) {
                PresetFieldStatus.UNSUPPORTED ->
                    results +=
                        PresetApplyResult(
                            key = diff.key,
                            status = PresetApplyStatus.SKIPPED,
                            detail = diff.reason,
                        )

                PresetFieldStatus.ALREADY_TARGET ->
                    results +=
                        PresetApplyResult(
                            key = diff.key,
                            status = PresetApplyStatus.ALREADY_TARGET,
                        )

                PresetFieldStatus.WILL_CHANGE -> {
                    val spec = catalog.find(diff.key)
                    val validation = if (spec == null) null else catalog.validate(spec, diff.targetValue)
                    if (validation !is ConfigValidation.Valid) {
                        results +=
                            PresetApplyResult(
                                key = diff.key,
                                status = PresetApplyStatus.SKIPPED,
                                detail =
                                    (validation as? ConfigValidation.Invalid)?.message
                                        ?: "未知配置键",
                            )
                        continue
                    }
                    try {
                        val response = dispatcher.execute(SetConfigRequest(diff.key, validation.bytes))
                        if (response !is SetConfigResponse) {
                            error("写配置应答类型异常: ${response::class.simpleName}")
                        }
                        results += PresetApplyResult(diff.key, PresetApplyStatus.WRITTEN)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (rejected: CommandFailure.DeviceRejected) {
                        results +=
                            PresetApplyResult(
                                key = diff.key,
                                status = PresetApplyStatus.FAILED,
                                errorCode = "0x${rejected.status.code.toString(16).uppercase().padStart(2, '0')}",
                                detail = "${rejected.status.name}: ${rejected.status.describe()}",
                            )
                    } catch (failure: CommandFailure) {
                        results +=
                            PresetApplyResult(
                                key = diff.key,
                                status = PresetApplyStatus.FAILED,
                                detail = failure.message,
                            )
                    } catch (error: Throwable) {
                        results +=
                            PresetApplyResult(
                                key = diff.key,
                                status = PresetApplyStatus.FAILED,
                                detail = error.message,
                            )
                    }
                }
            }
        }
        return PresetApplyReport(results)
    }
}
