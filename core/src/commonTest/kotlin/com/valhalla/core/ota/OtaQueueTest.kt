package com.valhalla.core.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaQueueTest {
    private fun target(
        deviceId: String,
        firmware: String = "fw-1",
    ) = OtaQueueTarget(
        deviceId = deviceId,
        displayName = "设备 ${deviceId.takeLast(1)}",
        firmwareId = firmware,
        firmwareLabel = "1.2.0",
    )

    @Test
    fun `串行入队拒绝同一设备重复项`() {
        val queue = OtaQueue()
        assertTrue(queue.enqueue(target("dev-1")))
        assertFalse(queue.enqueue(target("dev-1")))
        assertTrue(queue.enqueue(target("dev-2")))

        assertEquals(2, queue.items.size)
        assertEquals("dev-1", queue.nextQueued()!!.target.deviceId)
    }

    @Test
    fun `暂停停在安全点且恢复后重新可执行`() {
        val queue = OtaQueue()
        queue.enqueue(target("dev-1"))
        queue.markRunning("dev-1")
        queue.updateProgress("dev-1", OtaQueueStage.TRANSFER, 0.4f)
        queue.requestPause()

        assertTrue(queue.markPausedAtSafePoint("dev-1", 0.4f, checkpointRetained = true))
        val paused = queue.byDevice("dev-1")!!
        assertEquals(OtaQueueItemStatus.PAUSED, paused.status)
        assertEquals(0.4f, paused.progress)
        assertTrue(paused.checkpointRetained)
        assertNull(queue.nextQueued(), "暂停项不应被自动继续")

        assertTrue(queue.resumePaused())
        assertEquals(OtaQueueItemStatus.QUEUED, queue.byDevice("dev-1")!!.status)
        assertEquals("dev-1", queue.nextQueued()!!.target.deviceId)
    }

    @Test
    fun `取消当前后继续下一台且保留断点信息`() {
        val queue = OtaQueue()
        queue.enqueue(target("dev-1"))
        queue.enqueue(target("dev-2"))
        queue.markRunning("dev-1")

        assertTrue(queue.markAborted("dev-1", "user.cancel", checkpointRetained = true))
        assertEquals(OtaQueueItemStatus.ABORTED, queue.byDevice("dev-1")!!.status)
        assertTrue(queue.byDevice("dev-1")!!.checkpointRetained)
        assertEquals("dev-2", queue.nextQueued()!!.target.deviceId)
    }

    @Test
    fun `失败设备保留断点且可跳过失败`() {
        val queue = OtaQueue()
        queue.enqueue(target("dev-1"))
        queue.enqueue(target("dev-2"))
        queue.markRunning("dev-1")

        assertTrue(queue.markFailed("dev-1", "device.nack", checkpointRetained = true))
        assertEquals(OtaQueueItemStatus.FAILED, queue.byDevice("dev-1")!!.status)
        assertTrue(queue.byDevice("dev-1")!!.checkpointRetained)

        assertTrue(queue.skipFailed("dev-1"))
        assertEquals(OtaQueueItemStatus.SKIPPED, queue.byDevice("dev-1")!!.status)
        assertTrue(queue.byDevice("dev-1")!!.checkpointRetained, "跳过不宣称断点被清除")
    }

    @Test
    fun `移除只对排队项生效`() {
        val queue = OtaQueue()
        queue.enqueue(target("dev-1"))
        assertTrue(queue.removeQueued("dev-1"))
        assertTrue(queue.items.isEmpty())

        queue.enqueue(target("dev-1"))
        queue.markRunning("dev-1")
        assertFalse(queue.removeQueued("dev-1"), "运行中的条目不能直接移除")
    }

    @Test
    fun `汇总区分成功失败跳过与断点保留`() {
        val queue = OtaQueue()
        listOf("dev-1", "dev-2", "dev-3").forEach { queue.enqueue(target(it)) }
        queue.markRunning("dev-1")
        queue.markSucceeded("dev-1")
        queue.markRunning("dev-2")
        queue.markFailed("dev-2", "timeout", checkpointRetained = true)
        queue.markRunning("dev-3")
        queue.markAborted("dev-3", "user.cancel", checkpointRetained = false)

        val summary = queue.summary
        assertEquals(3, summary.total)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.aborted)
        assertEquals(1, summary.checkpointRetained)
        assertTrue(summary.finished)
        assertFalse(summary.toText().contains("dev-1"), "汇总不得暴露原始 deviceId")
    }

    @Test
    fun `队列不提供跨进程续跑`() {
        val first = OtaQueue()
        first.enqueue(target("dev-1"))
        first.markRunning("dev-1")

        val second = OtaQueue()
        assertTrue(second.items.isEmpty(), "新进程的新队列必须为空")
        assertNull(second.nextQueued())
    }
}
