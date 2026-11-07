package com.valhalla.core.command

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandDispatcherTest {
    private class FakeTransport(
        private val handler: suspend (Frame) -> Frame,
    ) : CommandTransport {
        val sent = mutableListOf<Frame>()

        override suspend fun send(frame: Frame): Frame {
            sent += frame
            return handler(frame)
        }
    }

    private fun responseOf(
        frame: Frame,
        payload: ByteArray = byteArrayOf(0x01),
    ): Frame = Frame(type = frame.type, sequence = frame.sequence, payload = payload, isResponse = true)

    @Test
    fun `成功请求返回响应且序号自增`() =
        runTest {
            val transport = FakeTransport { frame -> responseOf(frame) }
            val dispatcher = CommandDispatcher(transport)

            val first = dispatcher.execute(CommandType.AUTH)
            val second = dispatcher.execute(CommandType.GET_DEVICE_INFO)

            assertEquals(0, first.sequence)
            assertEquals(1, second.sequence)
            assertTrue(first.isResponse)
            assertEquals(2, transport.sent.size)
        }

    @Test
    fun `首次超时后重试成功`() =
        runTest {
            var calls = 0
            val transport =
                FakeTransport { frame ->
                    calls++
                    if (calls == 1) delay(5_000)
                    responseOf(frame)
                }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 3, timeoutMillis = 1_000, initialRetryDelayMillis = 10)

            val response = dispatcher.execute(CommandType.HEARTBEAT, policy = policy)

            assertEquals(2, calls)
            assertEquals(0, response.sequence)
        }

    @Test
    fun `多次超时最终抛出 Timeout`() =
        runTest {
            val transport =
                FakeTransport { frame ->
                    delay(5_000)
                    responseOf(frame)
                }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 3, timeoutMillis = 500, initialRetryDelayMillis = 10)

            val failure =
                assertFailsWith<CommandFailure.Timeout> {
                    dispatcher.execute(CommandType.AUTH, policy = policy)
                }

            assertEquals(3, failure.attempts)
            assertEquals(3, transport.sent.size)
        }

    @Test
    fun `传输异常会重试并最终抛出 TransportFailure`() =
        runTest {
            val transport = FakeTransport { error("gatt 写入失败") }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 2, timeoutMillis = 1_000, initialRetryDelayMillis = 10)

            val failure =
                assertFailsWith<CommandFailure.TransportFailure> {
                    dispatcher.execute(CommandType.REBOOT, policy = policy)
                }

            assertEquals(2, failure.attempts)
            assertEquals(2, transport.sent.size)
        }

    @Test
    fun `传输异常一次后重试成功`() =
        runTest {
            var calls = 0
            val transport =
                FakeTransport { frame ->
                    calls++
                    if (calls == 1) error("瞬时失败")
                    responseOf(frame)
                }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 3, timeoutMillis = 1_000, initialRetryDelayMillis = 10)

            val response = dispatcher.execute(CommandType.AUTH, policy = policy)

            assertEquals(2, calls)
            assertEquals(0, response.sequence)
        }

    @Test
    fun `响应帧标记或序号非法时抛出 InvalidResponse`() =
        runTest {
            val transport =
                FakeTransport { frame ->
                    Frame(type = frame.type, sequence = frame.sequence + 1, isResponse = false, payload = byteArrayOf(0x02))
                }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 1, timeoutMillis = 1_000)

            assertFailsWith<CommandFailure.InvalidResponse> {
                dispatcher.execute(CommandType.GET_DEVICE_INFO, policy = policy)
            }
        }

    @Test
    fun `请求携带 payload 且重试沿用同一序号`() =
        runTest {
            val transport = FakeTransport { frame -> responseOf(frame) }
            val dispatcher = CommandDispatcher(transport)
            val policy = RetryPolicy(maxAttempts = 2, timeoutMillis = 1_000)

            dispatcher.execute(CommandType.SET_CONFIG, payload = byteArrayOf(0x0A, 0x0B), policy = policy)

            assertEquals(1, transport.sent.size)
            assertEquals(0, transport.sent.single().sequence)
            assertTrue(
                transport.sent
                    .single()
                    .payload
                    .contentEquals(byteArrayOf(0x0A, 0x0B)),
            )
        }

    @Test
    fun `重试策略退避时间递增`() {
        val policy = RetryPolicy(initialRetryDelayMillis = 100, backoffMultiplier = 2.0)
        assertEquals(100, policy.delayForAttempt(1))
        assertEquals(200, policy.delayForAttempt(2))
        assertEquals(400, policy.delayForAttempt(3))
    }
}
