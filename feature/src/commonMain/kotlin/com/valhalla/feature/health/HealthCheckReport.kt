package com.valhalla.feature.health

import com.valhalla.core.logging.SensitiveTextRedaction
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage

/** 体检项的可本地化标签。 */
fun HealthCheckItemType.labelKey(): StringKey =
    when (this) {
        HealthCheckItemType.DEVICE_INFO -> StringKey.HEALTH_ITEM_DEVICE_INFO
        HealthCheckItemType.BATTERY -> StringKey.HEALTH_ITEM_BATTERY
        HealthCheckItemType.FIRMWARE -> StringKey.HEALTH_ITEM_FIRMWARE
        HealthCheckItemType.HEARTBEAT -> StringKey.HEALTH_ITEM_HEARTBEAT
        HealthCheckItemType.CONFIG -> StringKey.HEALTH_ITEM_CONFIG
        HealthCheckItemType.PARAM -> StringKey.HEALTH_ITEM_PARAM
        HealthCheckItemType.OTA_HISTORY -> StringKey.HEALTH_ITEM_OTA
    }

fun HealthCheckStatus.labelKey(): StringKey =
    when (this) {
        HealthCheckStatus.OK -> StringKey.HEALTH_STATUS_OK
        HealthCheckStatus.ABNORMAL -> StringKey.HEALTH_STATUS_ABNORMAL
        HealthCheckStatus.SKIPPED -> StringKey.HEALTH_STATUS_SKIPPED
        HealthCheckStatus.FAILED -> StringKey.HEALTH_STATUS_FAIL
    }

/**
 * 把体检结果渲染为可复制/保存的纯文本。
 *
 * - 所有自由文本（设备型号、固件版本、OTA 记录等）都经 [SensitiveTextRedaction]；
 * - 不使用评分或星级，只输出逐项状态与总体结论；
 * - 时间、版本与平台由调用方传入，避免在逻辑层拼装本地化文案。
 */
object HealthCheckReportFormatter {
    fun format(
        result: HealthCheckResult,
        generatedAtText: String,
        appVersion: String,
        platform: String,
        resolve: (UiMessage) -> String,
    ): String =
        buildString {
            appendLine(resolve(UiMessage.of(StringKey.HEALTH_REPORT_TITLE)))
            appendLine(
                resolve(
                    UiMessage(
                        StringKey.HEALTH_REPORT_HEADER,
                        args =
                            mapOf(
                                "time" to generatedAtText,
                                "version" to appVersion,
                                "platform" to platform,
                            ),
                    ),
                ),
            )
            appendLine(resolve(UiMessage.of(StringKey.HEALTH_REPORT_SOURCE)))
            appendLine()
            appendLine(
                resolve(
                    UiMessage(
                        StringKey.HEALTH_REPORT_OVERALL,
                        args =
                            mapOf(
                                "status" to resolve(overallMessage(result)),
                                "ok" to result.okCount.toString(),
                                "abnormal" to result.abnormalCount.toString(),
                                "failed" to result.failedCount.toString(),
                                "skipped" to result.skippedCount.toString(),
                            ),
                    ),
                ),
            )
            appendLine()
            result.items.forEach { item ->
                val status = resolve(UiMessage.of(item.status.labelKey()))
                val label = resolve(UiMessage.of(item.type.labelKey()))
                val detail = item.detail?.let { redact(resolve(it)) }
                append("- [")
                append(status)
                append("] ")
                append(label)
                if (!detail.isNullOrBlank()) {
                    append(": ")
                    append(detail)
                }
                item.errorCode?.let { code ->
                    append(" (")
                    append(redact(code))
                    append(')')
                }
                item.suggestion?.let { suggestion ->
                    append(" | ")
                    append(redact(resolve(suggestion)))
                }
                appendLine()
            }
        }

    private fun overallMessage(result: HealthCheckResult): UiMessage =
        when {
            result.abortedByLinkLoss -> UiMessage.of(StringKey.HEALTH_OVERALL_ABORTED)
            result.abnormalCount > 0 ->
                UiMessage(
                    StringKey.HEALTH_OVERALL_ABNORMAL,
                    args = mapOf("count" to result.abnormalCount.toString()),
                )

            result.failedCount > 0 ->
                UiMessage(
                    StringKey.HEALTH_OVERALL_FAILED,
                    args = mapOf("count" to result.failedCount.toString()),
                )

            else -> UiMessage.of(StringKey.HEALTH_OVERALL_OK)
        }

    private fun redact(text: String): String = SensitiveTextRedaction.redactInline(text)
}
