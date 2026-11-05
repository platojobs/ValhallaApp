package com.valhalla.feature.ota

import com.valhalla.core.ota.OtaQueueItemStatus
import com.valhalla.core.ota.OtaQueueStage
import com.valhalla.core.ota.OtaQueueTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OtaQueueControllerTest {

    private fun target(id: String) = OtaQueueTarget(
        deviceId = id,
        displayName = "设备 $id",
        firmwareId = "fw",
        firmwareLabel = "1.2.0",
    )

    @Test
    fun `队列串行执行并汇总成功`() = runTest {
        val executed = mutableListOf<String>()
        val controller = OtaQueueController(
            scope = this,
            executor = object : OtaQueueExecutor {
                override suspend fun execute(
                    item: com.valhalla.core.ota.OtaQueueItem,
                    progress: (OtaQueueStage, Float) -> Unit,
                    pauseRequested: () -> Boolean,
                    cancelRequested: () -> Boolean,
                ): OtaQueueExecutionResult {
                    executed += item.target.deviceId
                    progress(OtaQueueStage.TRANSFER, 0.5f)
                    return OtaQueueExecutionResult.Succeeded
                }
            },
        )
        controller.enqueue(target("dev-1"))
        controller.enqueue(target("dev-2"))
        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("dev-1", "dev-2"), executed)
        assertEquals(2, controller.state.value.summary.succeeded)
        assertFalse(controller.state.value.running)
    }

    @Test
    fun `暂停停在安全点且恢复后继续`() = runTest {
        var pauseSeen = false
        var runs = 0
        val controller = OtaQueueController(
            scope = this,
            executor = object : OtaQueueExecutor {
                override suspend fun execute(
                    item: com.valhalla.core.ota.OtaQueueItem,
                    progress: (OtaQueueStage, Float) -> Unit,
                    pauseRequested: () -> Boolean,
                    cancelRequested: () -> Boolean,
                ): OtaQueueExecutionResult {
                    runs++
                    progress(OtaQueueStage.TRANSFER, 0.3f)
                    if (runs == 1) {
                        pauseSeen = true
                        return OtaQueueExecutionResult.Paused(0.3f, checkpointRetained = true)
                    }
                    return OtaQueueExecutionResult.Succeeded
                }
            },
        )
        controller.enqueue(target("dev-1"))
        controller.enqueue(target("dev-2"))
        controller.start()
        advanceUntilIdle()

        assertEquals(OtaQueueItemStatus.PAUSED, controller.state.value.items.first().status)
        assertEquals(1, runs, "暂停后不应自动执行下一台")
        assertTrue(controller.state.value.items.first().checkpointRetained)
        assertTrue(pauseSeen)

        controller.resume()
        advanceUntilIdle()
        assertEquals(3, runs, "恢复后应重跑暂停设备并继续下一台")
        assertEquals(2, controller.state.value.summary.succeeded)
    }

    @Test
    fun `取消当前后继续下一台`() = runTest {
        val executed = mutableListOf<String>()
        val controller = OtaQueueController(
            scope = this,
            executor = object : OtaQueueExecutor {
                override suspend fun execute(
                    item: com.valhalla.core.ota.OtaQueueItem,
                    progress: (OtaQueueStage, Float) -> Unit,
                    pauseRequested: () -> Boolean,
                    cancelRequested: () -> Boolean,
                ): OtaQueueExecutionResult {
                    executed += item.target.deviceId
                    return if (item.target.deviceId == "dev-1") {
                        OtaQueueExecutionResult.Aborted("user.cancel", checkpointRetained = false)
                    } else {
                        OtaQueueExecutionResult.Succeeded
                    }
                }
            },
        )
        controller.enqueue(target("dev-1"))
        controller.enqueue(target("dev-2"))
        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("dev-1", "dev-2"), executed)
        assertEquals(1, controller.state.value.summary.aborted)
        assertEquals(1, controller.state.value.summary.succeeded)
    }

    @Test
    fun `失败设备保留断点且队列继续`() = runTest {
        val executed = mutableListOf<String>()
        val controller = OtaQueueController(
            scope = this,
            executor = object : OtaQueueExecutor {
                override suspend fun execute(
                    item: com.valhalla.core.ota.OtaQueueItem,
                    progress: (OtaQueueStage, Float) -> Unit,
                    pauseRequested: () -> Boolean,
                    cancelRequested: () -> Boolean,
                ): OtaQueueExecutionResult {
                    executed += item.target.deviceId
                    return if (item.target.deviceId == "dev-1") {
                        OtaQueueExecutionResult.Failed("device.nack", checkpointRetained = true)
                    } else {
                        OtaQueueExecutionResult.Succeeded
                    }
                }
            },
        )
        controller.enqueue(target("dev-1"))
        controller.enqueue(target("dev-2"))
        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("dev-1", "dev-2"), executed)
        assertEquals(1, controller.state.value.summary.failed)
        assertEquals(1, controller.state.value.summary.succeeded)
        assertTrue(controller.state.value.items.first().checkpointRetained)

        assertTrue(controller.skipFailed("dev-1"))
        assertEquals(OtaQueueItemStatus.SKIPPED, controller.state.value.items.first().status)
    }

    @Test
    fun `异常按失败处理并继续`() = runTest {
        val executed = mutableListOf<String>()
        val controller = OtaQueueController(
            scope = this,
            executor = object : OtaQueueExecutor {
                override suspend fun execute(
                    item: com.valhalla.core.ota.OtaQueueItem,
                    progress: (OtaQueueStage, Float) -> Unit,
                    pauseRequested: () -> Boolean,
                    cancelRequested: () -> Boolean,
                ): OtaQueueExecutionResult {
                    executed += item.target.deviceId
                    if (item.target.deviceId == "dev-1") error("boom")
                    return OtaQueueExecutionResult.Succeeded
                }
            },
        )
        controller.enqueue(target("dev-1"))
        controller.enqueue(target("dev-2"))
        controller.start()
        advanceUntilIdle()

        assertEquals(listOf("dev-1", "dev-2"), executed)
        assertEquals(1, controller.state.value.summary.failed)
        assertEquals(1, controller.state.value.summary.succeeded)
    }
}
