package com.valhalla.core.batch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchOperationTest {

    @Test
    fun `汇总只统计终态且区分失败与跳过`() {
        val items = listOf(
            BatchItemState(BatchTarget("dev-1", "A"), BatchItemStatus.SUCCEEDED),
            BatchItemState(BatchTarget("dev-2", "B"), BatchItemStatus.FAILED, reason = "timeout"),
            BatchItemState(BatchTarget("dev-3", "C"), BatchItemStatus.SKIPPED, reason = "locked"),
            BatchItemState(BatchTarget("dev-4", "D"), BatchItemStatus.CANCELLED),
            BatchItemState(BatchTarget("dev-5", "E"), BatchItemStatus.REMOVED),
        )
        val summary = BatchSummary(BatchOperationKind.HEALTH_CHECK, items)

        assertEquals(5, summary.total)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.cancelled)
        assertEquals(1, summary.removed)
        assertTrue(summary.finished)
    }

    @Test
    fun `导出文本不包含原始设备标识`() {
        val rawId = "AA:BB:CC:DD:EE:FF"
        val summary = BatchSummary(
            kind = BatchOperationKind.APPLY_PRESET,
            items = listOf(
                BatchItemState(BatchTarget(rawId, "设备 A"), BatchItemStatus.SUCCEEDED),
            ),
        )
        val text = summary.toText()

        assertFalse(text.contains(rawId), "导出文本不得包含原始 deviceId")
        assertTrue(text.contains("Valhalla batch summary"))
        assertTrue(text.contains("succeeded=1"))
    }

    @Test
    fun `锁定范围只覆盖参与设备且释放后恢复`() {
        val locks = DeviceOperationLocks()
        val result = locks.tryLock(listOf("dev-1", "dev-2"))

        assertEquals(setOf("dev-1", "dev-2"), result.locked)
        assertTrue(result.busy.isEmpty())
        assertTrue(locks.isLocked("dev-1"))
        assertFalse(locks.isLocked("dev-3"))

        val second = locks.tryLock(listOf("dev-2", "dev-3"))
        assertEquals(setOf("dev-3"), second.locked)
        assertEquals(setOf("dev-2"), second.busy)

        locks.release(listOf("dev-1", "dev-2"))
        assertFalse(locks.isLocked("dev-1"))
        assertTrue(locks.isLocked("dev-3"))
        locks.release(listOf("dev-3"))
        assertTrue(locks.lockedIds().isEmpty())
    }
}
