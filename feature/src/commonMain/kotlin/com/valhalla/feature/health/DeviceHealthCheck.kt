package com.valhalla.feature.health

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.GetParamRequest
import com.valhalla.core.protocol.message.HeartbeatRequest
import com.valhalla.core.protocol.message.HeartbeatResponse
import com.valhalla.core.profile.OtaHistoryRecord
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 体检项；顺序即执行顺序。 */
enum class HealthCheckItemType {
    DEVICE_INFO,
    BATTERY,
    FIRMWARE,
    HEARTBEAT,
    CONFIG,
    PARAM,
    OTA_HISTORY,
    ;

    companion object {
        /** 固定顺序：信息 → 电量 → 固件 → 心跳 → 配置 → 参数 → OTA。 */
        val ordered: List<HealthCheckItemType> = entries.toList()
    }
}

/** 单项结论。没有评分，只有这四种状态。 */
enum class HealthCheckStatus {
    OK,
    ABNORMAL,
    SKIPPED,
    FAILED,
}

/**
 * 一项体检结论。
 *
 * [detail] 与 [suggestion] 均为可本地化消息；[errorCode] 只在设备明确拒绝时填写，
 * 便于用户对照固件错误码表。
 */
data class HealthCheckItem(
    val type: HealthCheckItemType,
    val status: HealthCheckStatus,
    val detail: UiMessage? = null,
    val errorCode: String? = null,
    val suggestion: UiMessage? = null,
)

/** 体检结果；[abortedByLinkLoss] 时后续项不再执行。 */
data class HealthCheckResult(
    val items: List<HealthCheckItem>,
    val completedAtMillis: Long,
    val abortedByLinkLoss: Boolean = false,
) {

    val abnormalCount: Int get() = items.count { it.status == HealthCheckStatus.ABNORMAL }

    val failedCount: Int get() = items.count { it.status == HealthCheckStatus.FAILED }

    val skippedCount: Int get() = items.count { it.status == HealthCheckStatus.SKIPPED }

    val okCount: Int get() = items.count { it.status == HealthCheckStatus.OK }

    /** 总体结论：异常优先于失败，避免「有异常但显示正常」。 */
    val overall: HealthCheckStatus
        get() = when {
            abnormalCount > 0 -> HealthCheckStatus.ABNORMAL
            failedCount > 0 -> HealthCheckStatus.FAILED
            else -> HealthCheckStatus.OK
        }
}

/** 体检运行状态：进度、已完成项、最终结果。 */
data class HealthCheckState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = HealthCheckItemType.ordered.size,
    val current: HealthCheckItemType? = null,
    val items: List<HealthCheckItem> = emptyList(),
    val result: HealthCheckResult? = null,
)

/**
 * 一键体检控制器。
 *
 * 真实执行设备指令，不使用模拟值：
 * - 每项独立结算，单项失败不阻断后续项（链路断开除外）；
 * - 设备返回 `UNSUPPORTED` 记为跳过并说明原因；
 * - 每项开始前与关键指令后检查链路，断开时立即停止并标记中止；
 * - 仅供前台手动触发，不做后台巡检。
 */
@OptIn(ExperimentalTime::class)
class DeviceHealthCheckController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val isLinkAlive: () -> Boolean,
    private val configCatalog: ConfigCatalog = ConfigCatalog,
    private val paramCatalog: ParamCatalog = ParamCatalog,
    private val otaHistoryProvider: () -> List<OtaHistoryRecord> = { emptyList() },
    private val knownUptimeSeconds: () -> Long = { 0L },
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val mutableState = MutableStateFlow(HealthCheckState())
    val state: StateFlow<HealthCheckState> = mutableState.asStateFlow()

    /** 上次读取到的设备信息，用于配置能力判定。 */
    private var lastDeviceInfo: DeviceInfoResponse? = null

    suspend fun run(): HealthCheckResult? {
        if (mutableState.value.running) return null
        mutableState.value = HealthCheckState(running = true)
        val items = mutableListOf<HealthCheckItem>()
        var batteryPercent: Int? = null
        var aborted = false

        try {
            for (type in HealthCheckItemType.ordered) {
                currentCoroutineContext().ensureActive()
                if (!isLinkAlive()) {
                    aborted = true
                    items += HealthCheckItem(
                        type = type,
                        status = HealthCheckStatus.FAILED,
                        detail = UiMessage.of(StringKey.HEALTH_FAIL_LINK),
                        suggestion = UiMessage.of(StringKey.HEALTH_SUGGEST_LINK),
                    )
                    publish(items, type)
                    break
                }
                mutableState.update { it.copy(current = type) }
                val item = try {
                    when (type) {
                        HealthCheckItemType.DEVICE_INFO -> checkDeviceInfo()
                        HealthCheckItemType.BATTERY -> checkBattery().also { batteryPercent = it.second }.first
                        HealthCheckItemType.FIRMWARE -> checkFirmware()
                        HealthCheckItemType.HEARTBEAT -> checkHeartbeat(batteryPercent)
                        HealthCheckItemType.CONFIG -> checkConfig()
                        HealthCheckItemType.PARAM -> checkParam()
                        HealthCheckItemType.OTA_HISTORY -> checkOtaHistory()
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    failureItem(type, error)
                }
                items += item
                publish(items, type)
                if (item.status == HealthCheckStatus.FAILED && !isLinkAlive()) {
                    aborted = true
                    break
                }
            }

            val result = HealthCheckResult(
                items = items.toList(),
                completedAtMillis = clockMillis(),
                abortedByLinkLoss = aborted,
            )
            mutableState.update {
                it.copy(running = false, current = null, completed = items.size, result = result)
            }
            return result
        } catch (cancellation: CancellationException) {
            mutableState.update {
                it.copy(
                    running = false,
                    current = null,
                    completed = items.size,
                    result = HealthCheckResult(
                        items = items.toList(),
                        completedAtMillis = clockMillis(),
                    ),
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            mutableState.update { it.copy(running = false, current = null) }
            throw error
        }
    }

    fun clearResult() {
        mutableState.value = HealthCheckState()
    }

    private fun publish(items: List<HealthCheckItem>, finished: HealthCheckItemType) {
        mutableState.update {
            it.copy(completed = items.size, items = items.toList(), current = finished)
        }
    }

    private suspend fun checkDeviceInfo(): HealthCheckItem {
        val response = dispatcher.execute(GetDeviceInfoRequest()) as DeviceInfoResponse
        lastDeviceInfo = response
        return HealthCheckItem(
            type = HealthCheckItemType.DEVICE_INFO,
            status = HealthCheckStatus.OK,
            detail = UiMessage(
                StringKey.HEALTH_DETAIL_DEVICE,
                args = mapOf(
                    "vendor" to response.vendor,
                    "model" to response.model,
                    "hardware" to response.hardwareVersion,
                ),
            ),
        )
    }

    private suspend fun checkBattery(): Pair<HealthCheckItem, Int?> {
        val response = dispatcher.execute(GetBatteryRequest()) as BatteryResponse
        val low = response.percent < LOW_BATTERY_THRESHOLD
        val detail = UiMessage(
            key = if (low) StringKey.HEALTH_DETAIL_BATTERY_LOW else StringKey.HEALTH_DETAIL_BATTERY,
            args = mapOf("percent" to response.percent.toString()),
            nested = mapOf(
                "state" to UiMessage.of(
                    if (response.charging) StringKey.HEALTH_CHARGING else StringKey.HEALTH_NOT_CHARGING,
                ),
            ),
        )
        return HealthCheckItem(
            type = HealthCheckItemType.BATTERY,
            status = if (low) HealthCheckStatus.ABNORMAL else HealthCheckStatus.OK,
            detail = detail,
            suggestion = if (low) UiMessage.of(StringKey.HEALTH_SUGGEST_CHARGE) else null,
        ) to response.percent
    }

    private suspend fun checkFirmware(): HealthCheckItem {
        val response = dispatcher.execute(GetFirmwareVersionRequest()) as FirmwareVersionResponse
        return HealthCheckItem(
            type = HealthCheckItemType.FIRMWARE,
            status = HealthCheckStatus.OK,
            detail = UiMessage(
                StringKey.HEALTH_DETAIL_FIRMWARE,
                args = mapOf(
                    "version" to response.version.toString(),
                    "channel" to response.version.channel,
                ),
            ),
        )
    }

    private suspend fun checkHeartbeat(batteryPercent: Int?): HealthCheckItem {
        val uptimeSeconds = knownUptimeSeconds().coerceIn(0L, 0xFFFFFFFFL)
        val response = dispatcher.execute(
            HeartbeatRequest(uptimeSeconds = uptimeSeconds),
        ) as HeartbeatResponse
        val previous = batteryPercent
        val mismatch = previous != null && kotlin.math.abs(previous - response.batteryPercent) >= BATTERY_MISMATCH_THRESHOLD
        return HealthCheckItem(
            type = HealthCheckItemType.HEARTBEAT,
            status = if (mismatch) HealthCheckStatus.ABNORMAL else HealthCheckStatus.OK,
            detail = if (mismatch) {
                UiMessage(
                    StringKey.HEALTH_DETAIL_HEARTBEAT_MISMATCH,
                    args = mapOf(
                        "first" to previous.toString(),
                        "second" to response.batteryPercent.toString(),
                    ),
                )
            } else {
                UiMessage(
                    StringKey.HEALTH_DETAIL_HEARTBEAT,
                    args = mapOf(
                        "uptime" to response.uptimeSeconds.toString(),
                        "percent" to response.batteryPercent.toString(),
                    ),
                )
            },
            suggestion = if (mismatch) UiMessage.of(StringKey.HEALTH_SUGGEST_REFRESH) else null,
        )
    }

    private suspend fun checkConfig(): HealthCheckItem {
        val info = lastDeviceInfo
        val visible = configCatalog.visibleKeys(
            DeviceConfigTarget(
                firmwareVersion = info?.firmwareVersion,
                model = info?.model,
                vendor = info?.vendor,
            ),
        )
        if (visible.isEmpty()) {
            return HealthCheckItem(
                type = HealthCheckItemType.CONFIG,
                status = HealthCheckStatus.SKIPPED,
                detail = UiMessage.of(StringKey.HEALTH_SKIP_CONFIG),
            )
        }
        var ok = 0
        var failed = 0
        var unsupported = 0
        for (spec in visible) {
            currentCoroutineContext().ensureActive()
            if (!isLinkAlive()) break
            try {
                dispatcher.execute(GetConfigRequest(spec.key))
                ok++
            } catch (rejected: CommandFailure.DeviceRejected) {
                if (rejected.status == DeviceStatus.UNSUPPORTED) unsupported++ else failed++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                failed++
            }
        }
        return countItem(
            type = HealthCheckItemType.CONFIG,
            total = visible.size,
            ok = ok,
            failed = failed,
            unsupported = unsupported,
        )
    }

    private suspend fun checkParam(): HealthCheckItem {
        val params = paramCatalog.params
        if (params.isEmpty()) {
            return HealthCheckItem(
                type = HealthCheckItemType.PARAM,
                status = HealthCheckStatus.SKIPPED,
                detail = UiMessage.of(StringKey.HEALTH_SKIP_PARAM),
            )
        }
        var ok = 0
        var failed = 0
        var unsupported = 0
        for (spec in params) {
            currentCoroutineContext().ensureActive()
            if (!isLinkAlive()) break
            try {
                dispatcher.execute(GetParamRequest(spec.id))
                ok++
            } catch (rejected: CommandFailure.DeviceRejected) {
                if (rejected.status == DeviceStatus.UNSUPPORTED) unsupported++ else failed++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                failed++
            }
        }
        return countItem(
            type = HealthCheckItemType.PARAM,
            total = params.size,
            ok = ok,
            failed = failed,
            unsupported = unsupported,
        )
    }

    private fun countItem(
        type: HealthCheckItemType,
        total: Int,
        ok: Int,
        failed: Int,
        unsupported: Int,
    ): HealthCheckItem {
        val skipped = unsupported
        val status = when {
            failed > 0 -> HealthCheckStatus.ABNORMAL
            ok == 0 && skipped > 0 -> HealthCheckStatus.SKIPPED
            else -> HealthCheckStatus.OK
        }
        val detailKey = if (failed > 0) {
            if (type == HealthCheckItemType.CONFIG) {
                StringKey.HEALTH_DETAIL_CONFIG_PARTIAL
            } else {
                StringKey.HEALTH_DETAIL_PARAM_PARTIAL
            }
        } else if (type == HealthCheckItemType.CONFIG) {
            StringKey.HEALTH_DETAIL_CONFIG
        } else {
            StringKey.HEALTH_DETAIL_PARAM
        }
        val detail = if (ok == 0 && skipped > 0 && failed == 0) {
            UiMessage.of(StringKey.HEALTH_SKIP_DEVICE_UNSUPPORTED)
        } else {
            UiMessage(
                detailKey,
                args = mapOf(
                    "ok" to ok.toString(),
                    "total" to total.toString(),
                    "failed" to failed.toString(),
                    "skipped" to skipped.toString(),
                ),
            )
        }
        return HealthCheckItem(
            type = type,
            status = status,
            detail = detail,
            suggestion = if (failed > 0) UiMessage.of(StringKey.HEALTH_SUGGEST_CONFIG) else null,
        )
    }

    private fun checkOtaHistory(): HealthCheckItem {
        val records = otaHistoryProvider()
        if (records.isEmpty()) {
            return HealthCheckItem(
                type = HealthCheckItemType.OTA_HISTORY,
                status = HealthCheckStatus.SKIPPED,
                detail = UiMessage.of(StringKey.HEALTH_SKIP_OTA),
            )
        }
        val latest = records.first()
        return HealthCheckItem(
            type = HealthCheckItemType.OTA_HISTORY,
            status = HealthCheckStatus.OK,
            detail = UiMessage(
                StringKey.HEALTH_DETAIL_OTA,
                args = mapOf(
                    "count" to records.size.toString(),
                    "version" to latest.firmwareVersion,
                    "outcome" to latest.outcome.name,
                ),
            ),
        )
    }

    /** 把单项异常统一转换为可展示的结论；设备拒绝给出原始状态码。 */
    companion object {
        const val LOW_BATTERY_THRESHOLD: Int = 20
        const val BATTERY_MISMATCH_THRESHOLD: Int = 15

        fun failureItem(type: HealthCheckItemType, error: Throwable): HealthCheckItem = when (error) {
            is CommandFailure.DeviceRejected -> {
                if (error.status == DeviceStatus.UNSUPPORTED) {
                    HealthCheckItem(
                        type = type,
                        status = HealthCheckStatus.SKIPPED,
                        detail = UiMessage(
                            StringKey.HEALTH_SKIP_UNSUPPORTED,
                            args = mapOf("code" to error.status.describe()),
                        ),
                    )
                } else {
                    HealthCheckItem(
                        type = type,
                        status = HealthCheckStatus.ABNORMAL,
                        errorCode = "0x${error.status.code.toString(16).uppercase().padStart(2, '0')}",
                        detail = UiMessage.of(StringKey.HEALTH_DETAIL_DEVICE_REJECTED),
                        suggestion = UiMessage.of(StringKey.HEALTH_SUGGEST_DEVICE),
                    )
                }
            }

            is CommandFailure.Timeout -> HealthCheckItem(
                type = type,
                status = HealthCheckStatus.FAILED,
                detail = UiMessage.of(StringKey.HEALTH_FAIL_TIMEOUT),
                suggestion = UiMessage.of(StringKey.HEALTH_SUGGEST_TIMEOUT),
            )

            is CommandFailure.TransportFailure -> HealthCheckItem(
                type = type,
                status = HealthCheckStatus.FAILED,
                detail = UiMessage.of(StringKey.HEALTH_FAIL_TRANSPORT),
                suggestion = UiMessage.of(StringKey.HEALTH_SUGGEST_TRANSPORT),
            )

            else -> HealthCheckItem(
                type = type,
                status = HealthCheckStatus.FAILED,
                detail = UiMessage.of(StringKey.HEALTH_FAIL_UNEXPECTED),
                suggestion = UiMessage.of(StringKey.HEALTH_SUGGEST_UNEXPECTED),
            )
        }
    }
}
