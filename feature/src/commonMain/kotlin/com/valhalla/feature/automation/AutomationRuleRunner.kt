package com.valhalla.feature.automation

import com.valhalla.core.AppBuildInfo
import com.valhalla.core.automation.AutomationAction
import com.valhalla.core.automation.AutomationRule
import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.AutomationTrigger
import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ConfigPresetDiffer
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.config.PresetFieldStatus
import com.valhalla.core.diagnostics.DiagnosticReportComposer
import com.valhalla.core.diagnostics.DiagnosticReportError
import com.valhalla.core.diagnostics.DiagnosticReportFormat
import com.valhalla.core.diagnostics.DiagnosticReportInput
import com.valhalla.core.diagnostics.DiagnosticReportOptions
import com.valhalla.core.diagnostics.DiagnosticReportOta
import com.valhalla.core.event.DeviceEventResult
import com.valhalla.core.event.DeviceEventTimeline
import com.valhalla.core.event.DeviceEventType
import com.valhalla.core.profile.DeviceProfile
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.data.share.currentTimeZoneOffsetMinutes
import com.valhalla.feature.config.ConfigPresetApplier
import com.valhalla.feature.config.PresetApplyStatus
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.health.DeviceHealthCheckController
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 自动化执行上下文；全部依赖当前前台会话。 */
class AutomationContext(
    val deviceId: String,
    val dispatcher: ConcurrentCommandDispatcher,
    val detail: DeviceDetailController,
    val events: DeviceEventTimeline,
    val profileProvider: () -> DeviceProfile?,
    val healthCheckFactory: () -> DeviceHealthCheckController,
    val isLinkAlive: () -> Boolean,
    val platformDisplayName: String,
) {
    val target: DeviceConfigTarget
        get() {
            val state = detail.state.value
            val profile = profileProvider()
            return DeviceConfigTarget(
                firmwareVersion = state.firmware,
                model = state.deviceInfo?.model ?: profile?.model,
                vendor = state.deviceInfo?.vendor ?: profile?.vendor,
            )
        }
}

data class AutomationExecution(
    val ruleId: String,
    val action: AutomationAction,
    val success: Boolean,
    val detail: String? = null,
    val errorCode: String? = null,
)

data class AutomationRunReport(
    val trigger: AutomationTrigger,
    val executions: List<AutomationExecution>,
    val skippedBecauseNotForeground: Boolean = false,
) {
    val failedCount: Int get() = executions.count { !it.success }
}

/**
 * 前台自动化规则执行器。
 *
 * 边界：只有 App 前台且连接/OTA 成功回调发生时才会调用这里；没有后台唤醒、
 * 没有 WorkManager/后台任务。单条规则失败会写入事件时间线并继续后续规则，
 * 不向主流程抛异常。
 */
@OptIn(ExperimentalTime::class)
class AutomationRuleRunner(
    private val rules: AutomationRuleRepository,
    private val presets: ConfigPresetRepository,
    private val presetApplier: ConfigPresetApplier,
    private val events: DeviceEventTimeline,
    private val isForeground: () -> Boolean,
    private val eventSummary: (AutomationRule) -> String,
    private val catalog: ConfigCatalog = ConfigCatalog,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onDiagnosticReport: (String) -> Unit = {},
) {
    suspend fun run(
        trigger: AutomationTrigger,
        context: AutomationContext,
    ): AutomationRunReport {
        if (!isForeground()) {
            return AutomationRunReport(trigger, emptyList(), skippedBecauseNotForeground = true)
        }
        val active = rules.list().filter { it.enabled && it.trigger == trigger }
        if (active.isEmpty()) return AutomationRunReport(trigger, emptyList())

        val executions = mutableListOf<AutomationExecution>()
        active.forEach { rule ->
            if (!context.isLinkAlive()) {
                val failure =
                    AutomationExecution(
                        ruleId = rule.id,
                        action = rule.action,
                        success = false,
                        detail = "链路已断开，跳过该规则",
                    )
                executions += failure
                record(context, rule, failure)
                return@forEach
            }
            val execution = execute(rule, context)
            executions += execution
            record(context, rule, execution)
        }
        return AutomationRunReport(trigger, executions)
    }

    private suspend fun execute(
        rule: AutomationRule,
        context: AutomationContext,
    ): AutomationExecution =
        try {
            when (rule.action) {
                AutomationAction.READ_DEVICE_INFO -> {
                    context.detail.refreshInfo()
                    val state = context.detail.state.value
                    AutomationExecution(
                        ruleId = rule.id,
                        action = rule.action,
                        success = state.deviceInfo != null || state.error == null,
                        detail = state.error?.let { "设备信息读取未全部成功" },
                    )
                }

                AutomationAction.READ_BATTERY -> {
                    val response = context.dispatcher.execute(GetBatteryRequest())
                    if (response !is BatteryResponse) {
                        error("电量应答类型异常")
                    }
                    AutomationExecution(rule.id, rule.action, true, "${response.percent}%")
                }

                AutomationAction.RUN_HEALTH_CHECK -> {
                    val result = context.healthCheckFactory().run()
                    if (result != null) {
                        runCatching {
                            events.record(
                                deviceId = context.deviceId,
                                type = DeviceEventType.HEALTH,
                                summary = eventSummary(rule),
                                result =
                                    if (result.abortedByLinkLoss) {
                                        DeviceEventResult.FAILURE
                                    } else {
                                        DeviceEventResult.SUCCESS
                                    },
                                detail =
                                    "ok=${result.okCount}, abnormal=${result.abnormalCount}, " +
                                        "failed=${result.failedCount}, skipped=${result.skippedCount}",
                            )
                        }
                    }
                    AutomationExecution(
                        ruleId = rule.id,
                        action = rule.action,
                        success = result != null && !result.abortedByLinkLoss,
                        detail =
                            result?.let {
                                "异常 ${result.abnormalCount}，失败 ${result.failedCount}，跳过 ${result.skippedCount}"
                            } ?: "体检未启动",
                    )
                }

                AutomationAction.APPLY_PRESET -> applyPreset(rule, context)

                AutomationAction.READ_FIRMWARE_VERSION -> {
                    val response = context.dispatcher.execute(GetFirmwareVersionRequest())
                    if (response !is FirmwareVersionResponse) {
                        error("固件版本应答类型异常")
                    }
                    AutomationExecution(rule.id, rule.action, true, response.version.toString())
                }

                AutomationAction.GENERATE_DIAGNOSTIC -> generateDiagnostic(rule, context)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val rejected = error as? CommandFailure.DeviceRejected
            AutomationExecution(
                ruleId = rule.id,
                action = rule.action,
                success = false,
                detail = error.message,
                errorCode =
                    rejected?.let {
                        "0x${it.status.code.toString(16).uppercase().padStart(2, '0')}"
                    },
            )
        }

    private suspend fun applyPreset(
        rule: AutomationRule,
        context: AutomationContext,
    ): AutomationExecution {
        val preset =
            presets.find(rule.presetId.orEmpty())
                ?: return AutomationExecution(rule.id, rule.action, false, "预设不存在或已删除")
        val current = readVisibleConfigValues(context)
        val diffs = ConfigPresetDiffer.diff(preset, context.target, current, catalog)
        val report = presetApplier.apply(diffs)
        val skipped = diffs.count { it.status == PresetFieldStatus.UNSUPPORTED }
        val failures = report.results.filter { it.status == PresetApplyStatus.FAILED }
        return AutomationExecution(
            ruleId = rule.id,
            action = rule.action,
            success = report.failedCount == 0 && !report.cancelled,
            detail =
                "已写入 ${report.writtenCount}，已是目标 ${report.alreadyCount}，" +
                    "跳过 $skipped，失败 ${report.failedCount}" +
                    if (failures.isEmpty()) {
                        ""
                    } else {
                        "：" +
                            failures.joinToString("；") { "${it.key} ${it.errorCode ?: ""} ${it.detail.orEmpty()}" }
                    },
            errorCode = failures.firstOrNull()?.errorCode,
        )
    }

    private suspend fun readVisibleConfigValues(context: AutomationContext): Map<String, String> {
        val target = context.target
        val values = mutableMapOf<String, String>()
        catalog.keys.forEach { spec ->
            val capability = catalog.capability(spec, target)
            if (!capability.visible || !spec.editable) return@forEach
            runCatching {
                val response = context.dispatcher.execute(GetConfigRequest(spec.key))
                if (response is ConfigResponse) {
                    values[spec.key] = catalog.format(spec, response.value)
                }
            }
        }
        return values
    }

    /**
     * 生成诊断报告：使用当前设备快照、档案与事件时间线中的真实数据，
     * 不发送任何额外估算值；生成结果交给前台 UI，由用户决定保存/分享。
     */
    private suspend fun generateDiagnostic(
        rule: AutomationRule,
        context: AutomationContext,
    ): AutomationExecution {
        val state = context.detail.state.value
        val profile = context.profileProvider()
        val now = clockMillis()
        val recentEvents = runCatching { events.list(context.deviceId) }.getOrDefault(emptyList())
        val input =
            DiagnosticReportInput(
                generatedAtEpochMillis = now,
                zoneOffsetMinutes = currentTimeZoneOffsetMinutes(now),
                appVersion = AppBuildInfo.VERSION_NAME,
                platform = context.platformDisplayName,
                deviceId = context.deviceId,
                deviceModel = state.deviceInfo?.model ?: profile?.model,
                firmwareVersion = state.firmwareVersion,
                batteryPercent = state.batteryPercent,
                charging = state.charging,
                recentErrors =
                    recentEvents
                        .filter { it.result == DeviceEventResult.FAILURE }
                        .takeLast(5)
                        .map { event ->
                            DiagnosticReportError(
                                timestampMillis = event.timestampMillis,
                                command = event.summary,
                                detail = event.detail.orEmpty(),
                                errorCode = event.errorCode,
                            )
                        },
                otaHistory =
                    profile?.otaHistory.orEmpty().takeLast(5).map { record ->
                        DiagnosticReportOta(
                            firmwareVersion = record.firmwareVersion,
                            finishedAtEpochMillis = record.finishedAtEpochMillis,
                            outcome = record.outcome.name,
                            durationMillis = record.durationMillis,
                            errorCode = record.errorCode,
                            sentBytes = record.sentBytes,
                            totalBytes = record.totalBytes,
                        )
                    },
            )
        val result =
            DiagnosticReportComposer.build(
                input = input,
                options = DiagnosticReportOptions(format = DiagnosticReportFormat.TEXT),
            )
        onDiagnosticReport(result.text)
        return AutomationExecution(
            ruleId = rule.id,
            action = rule.action,
            success = result.text.isNotBlank(),
            detail = "${result.text.encodeToByteArray().size} 字节，${result.sectionCounts.size} 个小节",
        )
    }

    private fun record(
        context: AutomationContext,
        rule: AutomationRule,
        execution: AutomationExecution,
    ) {
        runCatching {
            events.record(
                deviceId = context.deviceId,
                type = DeviceEventType.AUTOMATION,
                summary = eventSummary(rule),
                result = if (execution.success) DeviceEventResult.SUCCESS else DeviceEventResult.FAILURE,
                detail = execution.detail,
                errorCode = execution.errorCode,
                timestampMillis = clockMillis(),
            )
        }
    }
}
