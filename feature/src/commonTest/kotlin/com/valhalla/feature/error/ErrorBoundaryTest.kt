package com.valhalla.feature.error

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.data.ble.BleTimeoutException
import com.valhalla.feature.FakeFrameLink
import com.valhalla.feature.ble.AutoReconnectController
import com.valhalla.feature.ble.ReconnectPhase
import com.valhalla.feature.ble.ReconnectPolicy
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorBoundaryTest {
    @Test
    fun `四类异常分别归类且不互相吞并`() {
        assertEquals(ErrorKind.USER_CANCELLED, classifyError(UserCancelledException()))
        assertEquals(ErrorKind.COROUTINE_CANCELLED, classifyError(CancellationException("scope 销毁")))
        assertEquals(ErrorKind.TIMEOUT, classifyError(BleTimeoutException("建链超时")))
        assertEquals(ErrorKind.TIMEOUT, classifyError(CommandFailure.Timeout(attempts = 3)))
        assertEquals(ErrorKind.FAILURE, classifyError(IllegalStateException("boom")))
    }

    @Test
    fun `withTimeout 超时被识别为 TIMEOUT 而不是普通取消`() =
        runTest {
            val error =
                try {
                    withTimeout(10) { kotlinx.coroutines.delay(100) }
                    error("预期超时")
                } catch (timeout: TimeoutCancellationException) {
                    timeout
                }
            assertEquals(ErrorKind.TIMEOUT, classifyError(error))
        }

    @Test
    fun `结构化记录保留原始异常类型并受容量上限约束`() {
        val reporter = ErrorReporter(capacity = 3, clockMillis = { 1_700_000_000_000 })
        repeat(5) { index ->
            reporter.record("unit", IllegalStateException("失败-$index"))
        }
        assertEquals(3, reporter.items.value.size)
        assertEquals(listOf("失败-2", "失败-3", "失败-4"), reporter.items.value.map { it.message })
        val latest = reporter.latest!!
        assertEquals("IllegalStateException", latest.type)
        assertEquals(ErrorKind.FAILURE, latest.kind)
        assertTrue(latest.stackTrace.isNotBlank())
    }

    @Test
    fun `launchSafely 记录失败但不崩溃且取消语义保持`() =
        runTest {
            val reporter = ErrorReporter()
            var handled: AppError? = null

            val failed =
                launchSafely(reporter, "ui-action", onError = { handled = it }) {
                    error("界面异常")
                }
            failed.join()
            assertEquals("IllegalStateException", handled?.type)
            assertEquals(1, reporter.items.value.size)

            // 协程取消是正常控制流，不应记作失败。
            val cancelled =
                launchSafely(reporter, "scope-cancel") {
                    throw CancellationException("宿主销毁")
                }
            cancelled.join()
            assertEquals(1, reporter.items.value.size)

            // 用户取消属于业务事件，应记录为 USER_CANCELLED。
            val userCancelled =
                launchSafely(reporter, "user-cancel") {
                    throw UserCancelledException()
                }
            userCancelled.join()
            assertEquals(ErrorKind.USER_CANCELLED, reporter.latest?.kind)
            assertEquals(2, reporter.items.value.size)
        }

    @Test
    fun `重连等待阶段注入异常时进入取消终态而不是死循环`() =
        runTest {
            var errors = 0
            val controller =
                AutoReconnectController(
                    policy = ReconnectPolicy(maxAttempts = 3),
                    scope = backgroundScope,
                    isBluetoothOn = { true },
                    attemptConnect = { null },
                    onConnected = {},
                    delayMillis = { error("退避实现异常") },
                    onError = { errors++ },
                )

            controller.start()
            runCurrent()

            assertEquals(1, errors)
            assertEquals(ReconnectPhase.CANCELLED, controller.state.value.phase)
        }

    @Test
    fun `OTA 写入抛出非协议异常时状态收敛为 Failed`() =
        runTest {
            val link = FakeFrameLink()
            link.onSend = { index, _ ->
                if (index == 1) error("GATT 写入异常")
            }
            val controller =
                OtaController(
                    dispatcher = ConcurrentCommandDispatcher(link, scope = backgroundScope),
                    chunkSize = 64,
                    scope = backgroundScope,
                )

            val result = controller.start(ByteArray(128) { it.toByte() })

            assertTrue(result is OtaState.Failed, "异常后必须进入终态，实际为 $result")
        }
}
