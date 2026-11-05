package com.valhalla.feature.ota

import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePrecheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaWizardTest {

    @Test
    fun stepsOnlyAdvanceWhenTheCurrentStepIsSatisfied() {
        assertEquals(
            OtaWizardStep.SELECT,
            OtaWizardFlow.next(OtaWizardStep.SELECT, selectionReady = false, precheckPassed = false),
        )
        assertEquals(
            OtaWizardStep.PRECHECK,
            OtaWizardFlow.next(OtaWizardStep.SELECT, selectionReady = true, precheckPassed = false),
        )
        assertEquals(
            OtaWizardStep.PRECHECK,
            OtaWizardFlow.next(OtaWizardStep.PRECHECK, selectionReady = true, precheckPassed = false),
        )
        assertEquals(
            OtaWizardStep.CONFIRM,
            OtaWizardFlow.next(OtaWizardStep.PRECHECK, selectionReady = true, precheckPassed = true),
        )
        assertEquals(
            OtaWizardStep.TRANSFER,
            OtaWizardFlow.next(OtaWizardStep.CONFIRM, selectionReady = true, precheckPassed = true),
        )
    }

    @Test
    fun transferAndResultStepsNeverAdvanceByNext() {
        assertEquals(
            OtaWizardStep.TRANSFER,
            OtaWizardFlow.next(OtaWizardStep.TRANSFER, selectionReady = true, precheckPassed = true),
        )
        assertEquals(
            OtaWizardStep.RESULT,
            OtaWizardFlow.next(OtaWizardStep.RESULT, selectionReady = true, precheckPassed = true),
        )
    }

    @Test
    fun previousWalksBackButNeverLeavesTransferWhileRunning() {
        assertEquals(OtaWizardStep.PRECHECK, OtaWizardFlow.previous(OtaWizardStep.CONFIRM, running = false))
        assertEquals(OtaWizardStep.SELECT, OtaWizardFlow.previous(OtaWizardStep.PRECHECK, running = false))
        assertEquals(OtaWizardStep.SELECT, OtaWizardFlow.previous(OtaWizardStep.SELECT, running = false))
        assertEquals(OtaWizardStep.TRANSFER, OtaWizardFlow.previous(OtaWizardStep.TRANSFER, running = true))
        assertEquals(OtaWizardStep.PRECHECK, OtaWizardFlow.previous(OtaWizardStep.TRANSFER, running = false))
        assertEquals(OtaWizardStep.SELECT, OtaWizardFlow.previous(OtaWizardStep.RESULT, running = false))
    }

    @Test
    fun cancelSemanticsFollowTheExistingStateMachine() {
        assertEquals(OtaWizardCancel.ABORT, OtaWizardFlow.cancel(OtaWizardStep.TRANSFER, running = true))
        assertEquals(OtaWizardCancel.CLEAR_AND_EXIT, OtaWizardFlow.cancel(OtaWizardStep.SELECT, running = false))
        assertEquals(OtaWizardCancel.CLEAR_AND_EXIT, OtaWizardFlow.cancel(OtaWizardStep.PRECHECK, running = false))
        assertEquals(OtaWizardCancel.CLEAR_AND_EXIT, OtaWizardFlow.cancel(OtaWizardStep.CONFIRM, running = false))
        assertEquals(OtaWizardCancel.EXIT, OtaWizardFlow.cancel(OtaWizardStep.RESULT, running = false))
    }

    @Test
    fun runStartAndTerminalStatesDriveTheWizard() {
        assertEquals(OtaWizardStep.TRANSFER, OtaWizardFlow.onRunStarted())
        assertEquals(OtaWizardStep.RESULT, OtaWizardFlow.onTerminal())
    }

    @Test
    fun resumePercentIsBoundedAndSafe() {
        assertEquals(0, OtaWizardFlow.resumePercent(0, 0))
        assertEquals(0, OtaWizardFlow.resumePercent(10, 0))
        assertEquals(50, OtaWizardFlow.resumePercent(512, 1024))
        assertEquals(100, OtaWizardFlow.resumePercent(2048, 1024))
    }

    @Test
    fun precheckRulesMapEveryErrorToExactlyOneRule() {
        val result = FirmwarePrecheckResult(
            errors = listOf(
                FirmwareException.HardwareModelMismatch("VX-1", listOf("VX-2")),
                FirmwareException.PayloadChecksumMismatch(1, 2),
            ),
        )
        val rules = OtaPrecheckRuleEvaluator.evaluate(result)
        assertEquals(OtaPrecheckRule.ordered, rules.map { it.rule })
        assertEquals(OtaPrecheckRuleStatus.BLOCKING, rules.first { it.rule == OtaPrecheckRule.DEVICE_MODEL }.status)
        assertEquals(OtaPrecheckRuleStatus.BLOCKING, rules.first { it.rule == OtaPrecheckRule.PAYLOAD_CRC }.status)
        assertTrue(rules.filterNot { it.rule == OtaPrecheckRule.DEVICE_MODEL || it.rule == OtaPrecheckRule.PAYLOAD_CRC }
            .all { it.status == OtaPrecheckRuleStatus.PASSED })
    }

    @Test
    fun passedPrecheckReportsEveryRuleAsPassed() {
        val rules = OtaPrecheckRuleEvaluator.evaluate(FirmwarePrecheckResult(payloadCrc32 = 0, payloadCrc16 = 0))
        assertTrue(rules.all { it.status == OtaPrecheckRuleStatus.PASSED })
        assertFalse(rules.any { it.status == OtaPrecheckRuleStatus.BLOCKING })
    }

    @Test
    fun ruleCategoriesCoverStableErrorCodes() {
        assertEquals(OtaPrecheckRule.SIZE_LIMIT, OtaPrecheckRuleEvaluator.ruleOf("firmware.too_large"))
        assertEquals(OtaPrecheckRule.FILE_STRUCTURE, OtaPrecheckRuleEvaluator.ruleOf("firmware.header_crc"))
        assertEquals(OtaPrecheckRule.VERSION_DIRECTION, OtaPrecheckRuleEvaluator.ruleOf("firmware.version_rollback"))
        assertEquals(OtaPrecheckRule.SIGNATURE, OtaPrecheckRuleEvaluator.ruleOf("firmware.signature_invalid"))
    }
}
