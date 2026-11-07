package com.valhalla.feature.ota

import com.valhalla.core.firmware.FirmwarePrecheckResult

/** 固件升级向导的五个步骤。 */
enum class OtaWizardStep(
    val index: Int,
) {
    SELECT(1),
    PRECHECK(2),
    CONFIRM(3),
    TRANSFER(4),
    RESULT(5),
    ;

    companion object {
        const val TOTAL: Int = 5
    }
}

/** 当前步骤取消时应该执行的语义。 */
enum class OtaWizardCancel {
    /** 只是离开向导（没有正在进行的传输）。 */
    EXIT,

    /** 丢弃当前选择并离开向导。 */
    CLEAR_AND_EXIT,

    /** 中止正在进行的传输，状态机按原有 Aborted 收敛。 */
    ABORT,
}

/**
 * 向导步骤推进规则（纯逻辑，便于测试）。
 *
 * 关键约束：
 * - 只有预检通过才允许从预检进入确认；
 * - 传输期间不能回退或跳过，只能中止；
 * - 结果页可以重新回到选择步骤开始新一轮。
 */
object OtaWizardFlow {
    fun next(
        current: OtaWizardStep,
        selectionReady: Boolean,
        precheckPassed: Boolean,
    ): OtaWizardStep =
        when (current) {
            OtaWizardStep.SELECT -> if (selectionReady) OtaWizardStep.PRECHECK else OtaWizardStep.SELECT
            OtaWizardStep.PRECHECK -> if (precheckPassed) OtaWizardStep.CONFIRM else OtaWizardStep.PRECHECK
            OtaWizardStep.CONFIRM -> OtaWizardStep.TRANSFER
            OtaWizardStep.TRANSFER -> OtaWizardStep.TRANSFER
            OtaWizardStep.RESULT -> OtaWizardStep.RESULT
        }

    fun previous(
        current: OtaWizardStep,
        running: Boolean,
    ): OtaWizardStep =
        when (current) {
            OtaWizardStep.SELECT -> OtaWizardStep.SELECT
            OtaWizardStep.PRECHECK -> OtaWizardStep.SELECT
            OtaWizardStep.CONFIRM -> OtaWizardStep.PRECHECK
            OtaWizardStep.TRANSFER -> if (running) OtaWizardStep.TRANSFER else OtaWizardStep.PRECHECK
            OtaWizardStep.RESULT -> OtaWizardStep.SELECT
        }

    fun cancel(
        current: OtaWizardStep,
        running: Boolean,
    ): OtaWizardCancel =
        when {
            running -> OtaWizardCancel.ABORT
            current == OtaWizardStep.TRANSFER || current == OtaWizardStep.RESULT -> OtaWizardCancel.EXIT
            else -> OtaWizardCancel.CLEAR_AND_EXIT
        }

    fun onRunStarted(): OtaWizardStep = OtaWizardStep.TRANSFER

    fun onTerminal(): OtaWizardStep = OtaWizardStep.RESULT

    /** 断点进度百分比（0..100）；总量无效时返回 0。 */
    fun resumePercent(
        sentBytes: Long,
        totalBytes: Long,
    ): Int {
        if (totalBytes <= 0L) return 0
        return ((sentBytes.toDouble() / totalBytes.toDouble()) * 100.0)
            .coerceIn(0.0, 100.0)
            .toInt()
    }
}

/** 预检规则分类，用于在向导第二步逐条展示。 */
enum class OtaPrecheckRule {
    FILE_STRUCTURE,
    SIZE_LIMIT,
    DEVICE_MODEL,
    VERSION_DIRECTION,
    PAYLOAD_CRC,
    SIGNATURE,
    ;

    companion object {
        val ordered: List<OtaPrecheckRule> = entries.toList()
    }
}

enum class OtaPrecheckRuleStatus {
    /** 未命中该规则错误，视为通过。 */
    PASSED,

    /** 命中阻断性错误，预检不通过。 */
    BLOCKING,

    /** 命中非阻断提示。 */
    WARNING,
}

data class OtaPrecheckRuleResult(
    val rule: OtaPrecheckRule,
    val status: OtaPrecheckRuleStatus,
    val code: String? = null,
    val detail: String? = null,
)

object OtaPrecheckRuleEvaluator {
    /** 把预检结果映射为固定的逐条规则列表；[FirmwarePrecheckResult.warnings] 另作非阻断提示展示。 */
    fun evaluate(result: FirmwarePrecheckResult): List<OtaPrecheckRuleResult> {
        val blockingByRule = result.errors.groupBy { ruleOf(it.code) }
        return OtaPrecheckRule.ordered.map { rule ->
            val error = blockingByRule[rule].orEmpty().firstOrNull()
            if (error == null) {
                OtaPrecheckRuleResult(rule, OtaPrecheckRuleStatus.PASSED)
            } else {
                OtaPrecheckRuleResult(
                    rule = rule,
                    status = OtaPrecheckRuleStatus.BLOCKING,
                    code = error.code,
                    detail = error.message ?: error.code,
                )
            }
        }
    }

    fun ruleOf(code: String): OtaPrecheckRule =
        when {
            code.startsWith("firmware.signature") -> OtaPrecheckRule.SIGNATURE
            code == "firmware.payload_crc" -> OtaPrecheckRule.PAYLOAD_CRC
            code == "firmware.too_large" -> OtaPrecheckRule.SIZE_LIMIT
            code == "firmware.model" || code == "firmware.hardware_unknown" -> OtaPrecheckRule.DEVICE_MODEL
            code.startsWith("firmware.version") -> OtaPrecheckRule.VERSION_DIRECTION
            else -> OtaPrecheckRule.FILE_STRUCTURE
        }
}
