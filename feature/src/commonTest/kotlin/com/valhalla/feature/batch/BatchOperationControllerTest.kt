package com.valhalla.feature.batch

import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.batch.DeviceOperationLocks
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchOperationControllerTest {

    private fun target(id: String) = BatchTarget(deviceId = id, displayName = "设备 $id")

    @Test
    fun `串行执行且单台失败继续下一台`() = runTest {
        val locks = DeviceOperationLocks()
        val controller = BatchOperationController(locks)
        val executed = mutableListOf<String>()

        val summary = controller.run(
            kind = BatchOperationKind.HEALTH_CHECK,
            targets = listOf(target("dev-1"), target("dev-2"), target("dev-3")),
        ) { target, _ ->
            executed += target.deviceId
            if (target.deviceId == "dev-2") {
                error("timeout")
            }
            BatchDeviceOutcome(BatchItemStatus.SUCCEEDED)
        }

        assertEquals(listOf("dev-1", "dev-2", "dev-3"), executed)
        assertEquals(2, summary.succeeded)
        assertEquals(1, summary.failed)
        assertTrue(summary.items.first { it.target.deviceId == "dev-2" }.reason == "batch.execution_error")
        assertTrue(locks.lockedIds().isEmpty(), "批量结束后必须释放锁")
    }

    @Test
    fun `取消剩余任务只影响尚未开始的条目`() = runTest {
        val controller = BatchOperationController(DeviceOperationLocks())
        val executed = mutableListOf<String>()

        val summary = controller.run(
            kind = BatchOperationKind.HEALTH_CHECK,
            targets = listOf(target("dev-1"), target("dev-2"), target("dev-3")),
        ) { target, _ ->
            executed += target.deviceId
            if (target.deviceId == "dev-1") controller.cancelRemaining()
            BatchDeviceOutcome(BatchItemStatus.SUCCEEDED)
        }

        assertEquals(listOf("dev-1"), executed)
        assertEquals(1, summary.succeeded)
        assertEquals(2, summary.cancelled)
        assertTrue(summary.items.drop(1).all { it.reason == "batch.cancelled_remaining" })
    }

    @Test
    fun `参与设备加锁且未参与设备不受影响`() = runTest {
        val locks = DeviceOperationLocks()
        locks.tryLock(listOf("dev-2"))
        val controller = BatchOperationController(locks)

        val summary = controller.run(
            kind = BatchOperationKind.APPLY_PRESET,
            targets = listOf(target("dev-1"), target("dev-2")),
        ) { _, _ -> BatchDeviceOutcome(BatchItemStatus.SUCCEEDED) }

        assertEquals(BatchItemStatus.SUCCEEDED, summary.items[0].status)
        assertEquals(BatchItemStatus.SKIPPED, summary.items[1].status)
        assertEquals("batch.device_locked", summary.items[1].reason)
        assertFalse(locks.isLocked("dev-1"), "批量锁应在结束时释放")
        assertTrue(locks.isLocked("dev-2"), "原本已被占用的锁不能误释放")
        locks.release(listOf("dev-2"))
    }

    @Test
    fun `掉线设备从队列移除且不执行`() = runTest {
        val controller = BatchOperationController(
            locks = DeviceOperationLocks(),
            isConnected = { it != "dev-2" },
        )
        val executed = mutableListOf<String>()

        val summary = controller.run(
            kind = BatchOperationKind.HEALTH_CHECK,
            targets = listOf(target("dev-1"), target("dev-2"), target("dev-3")),
        ) { target, _ ->
            executed += target.deviceId
            BatchDeviceOutcome(BatchItemStatus.SUCCEEDED)
        }

        assertEquals(listOf("dev-1", "dev-3"), executed)
        assertEquals(1, summary.removed)
        assertEquals("batch.disconnected", summary.items[1].reason)
    }

    @Test
    fun `执行中掉线按移除处理而非成功`() = runTest {
        var connected = true
        val controller = BatchOperationController(
            locks = DeviceOperationLocks(),
            isConnected = { connected },
        )

        val summary = controller.run(
            kind = BatchOperationKind.HEALTH_CHECK,
            targets = listOf(target("dev-1")),
        ) { _, _ ->
            connected = false
            BatchDeviceOutcome(BatchItemStatus.SUCCEEDED)
        }

        assertEquals(1, summary.removed)
        assertEquals("batch.disconnected_after_step", summary.items[0].reason)
    }
}
