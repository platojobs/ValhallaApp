package com.valhalla.core.command

import com.valhalla.core.logging.InMemoryDeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.AbortOtaRequest
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.HeartbeatReplyNotification
import com.valhalla.core.protocol.message.HeartbeatRequest
import com.valhalla.core.protocol.message.HeartbeatResponse
import com.valhalla.core.protocol.message.OtaProgressNotification
import com.valhalla.core.protocol.message.OtaStage
import com.valhalla.core.protocol.message.StartOtaRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentCommandDispatcherTest {

    private class FakeFrameLink : FrameLink {
        private val channel = Channel<Frame>(Channel.UNLIMITED)

        override val incoming: Flow<Frame> = channel.receiveAsFlow()

        val sent = mutableListOf<Frame>()

        var onSend: (suspend (Frame) -> Unit)? = null

        override suspend fun send(frame: Frame) {
            sent += frame
            onSend?.invoke(frame)
        }

        fun emit(frame: Frame) {
            channel.trySend(frame)
        }
    }

    private fun frameOf(sequence: Int, command: CommandType, payload: ByteArray): Frame =
        Frame(type = command, sequence = sequence, payload = payload, isResponse = true)

    private fun batteryFrame(sequence: Int, percent: Int = 80): Frame =
        frameOf(
            sequence,
            CommandType.GET_BATTERY,
            BatteryResponse(DeviceStatus.OK, percent, true, 4_000).encode(),
        )

    private fun infoFrame(sequence: Int): Frame =
        frameOf(
            sequence,
            CommandType.GET_DEVICE_INFO,
            DeviceInfoResponse(
                status = DeviceStatus.OK,
                vendor = "Valhalla",
                model = "VH-100",
                serialNumber = "SN-1",
                hardwareVersion = "HW-1",
                firmwareVersion = FirmwareVersion(1, 0, 0, 1, "stable"),
            ).encode(),
        )

    private suspend fun <T> Deferred<T>.errorOrNull(): Throwable? =
        try {
            await()
            null
        } catch (error: Throwable) {
            error
        }

    /**
     * 预期失败的请求需要放在独立的 SupervisorJob 作用域里，
     * 否则 async 的异常会被 runTest 当作未捕获异常上报。
     */
    private fun TestScope.failureScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun `并发请求按 sequence 隔离且乱序应答正确配对`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            scope = backgroundScope,
        )

        val battery = async { dispatcher.execute(GetBatteryRequest()) }
        val info = async { dispatcher.execute(GetDeviceInfoRequest()) }
        runCurrent()

        assertEquals(2, link.sent.size)
        val batteryRequest = link.sent.first { it.type == CommandType.GET_BATTERY }
        val infoRequest = link.sent.first { it.type == CommandType.GET_DEVICE_INFO }
        assertTrue(batteryRequest.sequence != infoRequest.sequence, "并发请求必须分配不同序号")

        // 故意乱序回复：先回后发的设备信息，再回先发的电量
        link.emit(infoFrame(infoRequest.sequence))
        link.emit(batteryFrame(batteryRequest.sequence))
        runCurrent()

        val batteryResult = battery.await() as BatteryResponse
        val infoResult = info.await() as DeviceInfoResponse
        assertEquals(80, batteryResult.percent)
        assertEquals("Valhalla", infoResult.vendor)
    }

    @Test
    fun `重复应答只生效一次并被记录`() = runTest {
        val logger = InMemoryDeviceLogger()
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            logger = logger,
            scope = backgroundScope,
        )

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        runCurrent()
        val sequence = link.sent.single().sequence

        link.emit(batteryFrame(sequence, percent = 80))
        link.emit(batteryFrame(sequence, percent = 55))
        runCurrent()

        assertEquals(80, (request.await() as BatteryResponse).percent)
        assertTrue(
            logger.entries().any { it.message.contains("重复应答") || it.message.contains("无主或迟到") },
            "重复应答应被记录：${logger.entries().map { it.message }}",
        )
    }

    @Test
    fun `超时后沿用同一序号重试并成功`() = runTest {
        val link = FakeFrameLink()
        var attempts = 0
        link.onSend = { frame ->
            attempts++
            if (attempts == 2) {
                link.emit(batteryFrame(frame.sequence))
            }
        }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 2, timeoutMillis = 100, initialRetryDelayMillis = 10),
            scope = backgroundScope,
        )

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        assertEquals(2, link.sent.size)
        assertEquals(link.sent[0].sequence, link.sent[1].sequence, "重试必须沿用首次分配的序号")
        assertEquals(80, (request.await() as BatteryResponse).percent)
    }

    @Test
    fun `多次超时抛出结构化 Timeout`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 3, timeoutMillis = 50, initialRetryDelayMillis = 10),
            scope = backgroundScope,
        )

        val requestScope = failureScope()
        val request = requestScope.async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        val error = request.errorOrNull()
        assertTrue(error is CommandFailure.Timeout, "实际类型: ${error?.let { it::class.simpleName }}")
        assertEquals(3, (error as CommandFailure.Timeout).attempts)
        assertEquals(3, link.sent.size)
        assertTrue(link.sent.all { it.sequence == link.sent.first().sequence })
        requestScope.cancel()
    }

    @Test
    fun `传输异常重试后成功`() = runTest {
        val link = FakeFrameLink()
        var attempts = 0
        link.onSend = { frame ->
            attempts++
            if (attempts == 1) throw IllegalStateException("gatt 写入失败")
            link.emit(batteryFrame(frame.sequence))
        }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 2, timeoutMillis = 100, initialRetryDelayMillis = 10),
            scope = backgroundScope,
        )

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(80, (request.await() as BatteryResponse).percent)
    }

    @Test
    fun `持续传输异常抛出 TransportFailure`() = runTest {
        val link = FakeFrameLink()
        link.onSend = { throw IllegalStateException("链路断开") }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 2, timeoutMillis = 100, initialRetryDelayMillis = 10),
            scope = backgroundScope,
        )

        val requestScope = failureScope()
        val request = requestScope.async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        val error = request.errorOrNull()
        assertTrue(error is CommandFailure.TransportFailure)
        assertEquals(2, (error as CommandFailure.TransportFailure).attempts)
        requestScope.cancel()
    }

    @Test
    fun `设备错误码映射为结构化 DeviceRejected`() = runTest {
        val link = FakeFrameLink()
        link.onSend = { frame ->
            link.emit(
                frameOf(
                    frame.sequence,
                    CommandType.START_OTA,
                    byteArrayOf(DeviceStatus.OTA_VERIFY_FAILED.code.toByte()),
                ),
            )
        }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 5_000),
            scope = backgroundScope,
        )

        val requestScope = failureScope()
        val request = requestScope.async {
            dispatcher.execute(
                StartOtaRequest(2_048L, 0x1234, 64, FirmwareVersion(1, 2, 3, 4, "stable")),
            )
        }
        advanceUntilIdle()

        val error = request.errorOrNull()
        assertTrue(error is CommandFailure.DeviceRejected)
        error as CommandFailure.DeviceRejected
        assertEquals(CommandType.START_OTA, error.command)
        assertEquals(DeviceStatus.OTA_VERIFY_FAILED, error.status)
        requestScope.cancel()
    }

    @Test
    fun `通知帧不会误配给同序号请求`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            scope = backgroundScope,
        )

        val notification = async { dispatcher.notifications.first() }
        runCurrent()

        val request = async { dispatcher.execute(HeartbeatRequest(1_000L)) }
        runCurrent()
        val sequence = link.sent.single().sequence

        link.emit(
            Frame(
                type = CommandType.HEARTBEAT_REPLY,
                sequence = sequence,
                payload = HeartbeatReplyNotification(70, 2_000L).encode(),
                isNotification = true,
            ),
        )
        runCurrent()

        assertEquals(70, (notification.await() as HeartbeatReplyNotification).batteryPercent)
        assertFalse(request.isCompleted, "通知帧不应完成业务请求")

        link.emit(
            frameOf(
                sequence,
                CommandType.HEARTBEAT,
                HeartbeatResponse(DeviceStatus.OK, 1_000L, 68).encode(),
            ),
        )
        runCurrent()

        val result = request.await() as HeartbeatResponse
        assertEquals(68, result.batteryPercent)
    }

    @Test
    fun `无主应答被忽略且不影响后续请求`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            scope = backgroundScope,
        )

        link.emit(batteryFrame(9_999))
        runCurrent()

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        runCurrent()
        link.emit(batteryFrame(link.sent.single().sequence))
        runCurrent()

        assertEquals(80, (request.await() as BatteryResponse).percent)
    }

    @Test
    fun `畸形应答返回结构化 InvalidResponse`() = runTest {
        val link = FakeFrameLink()
        link.onSend = { frame ->
            // 状态码 + 电量百分比，缺少充电状态与电压字段
            link.emit(frameOf(frame.sequence, CommandType.GET_BATTERY, byteArrayOf(0x00, 0x50)))
        }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 5_000),
            scope = backgroundScope,
        )

        val requestScope = failureScope()
        val request = requestScope.async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        val error = request.errorOrNull()
        assertTrue(error is CommandFailure.InvalidResponse, "实际类型: ${error?.let { it::class.simpleName }}")
        requestScope.cancel()
    }

    @Test
    fun `应答指令类型不匹配时请求超时`() = runTest {
        val link = FakeFrameLink()
        link.onSend = { frame ->
            link.emit(infoFrame(frame.sequence))
        }
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 50),
            scope = backgroundScope,
        )

        val requestScope = failureScope()
        val request = requestScope.async { dispatcher.execute(GetBatteryRequest()) }
        advanceUntilIdle()

        assertTrue(request.errorOrNull() is CommandFailure.Timeout)
        requestScope.cancel()
    }

    @Test
    fun `并发 OTA 分片请求各自独立完成`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            scope = backgroundScope,
        )

        val first = async { dispatcher.executeRaw(CommandType.OTA_DATA, byteArrayOf(0x01, 0x00)) }
        val second = async { dispatcher.executeRaw(CommandType.OTA_DATA, byteArrayOf(0x02, 0x00)) }
        runCurrent()

        assertEquals(2, link.sent.size)
        val firstRequest = link.sent[0]
        val secondRequest = link.sent[1]
        assertTrue(firstRequest.sequence != secondRequest.sequence)

        link.emit(
            frameOf(
                secondRequest.sequence,
                CommandType.OTA_DATA,
                com.valhalla.core.protocol.message.OtaDataResponse(DeviceStatus.OK, 2L).encode(),
            ),
        )
        link.emit(
            frameOf(
                firstRequest.sequence,
                CommandType.OTA_DATA,
                com.valhalla.core.protocol.message.OtaDataResponse(DeviceStatus.OK, 1L).encode(),
            ),
        )
        runCurrent()

        assertEquals(1L, (first.await().payload.let { com.valhalla.core.protocol.message.MessageCodec.decodeResponse(CommandType.OTA_DATA, it) } as com.valhalla.core.protocol.message.OtaDataResponse).receivedBytes)
        assertEquals(2L, (second.await().payload.let { com.valhalla.core.protocol.message.MessageCodec.decodeResponse(CommandType.OTA_DATA, it) } as com.valhalla.core.protocol.message.OtaDataResponse).receivedBytes)
    }

    @Test
    fun `通知解码失败不会打断后续请求`() = runTest {
        val link = FakeFrameLink()
        val logger = InMemoryDeviceLogger()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            logger = logger,
            scope = backgroundScope,
        )

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        runCurrent()

        link.emit(
            Frame(
                type = CommandType.OTA_PROGRESS_NOTIFY,
                sequence = 1,
                payload = byteArrayOf(0x01),
                isNotification = true,
            ),
        )
        runCurrent()
        assertTrue(logger.entries().any { it.level == LogLevel.WARN })

        link.emit(batteryFrame(link.sent.single().sequence))
        runCurrent()

        assertEquals(80, (request.await() as BatteryResponse).percent)
    }

    @Test
    fun `close 会终结在途请求`() = runTest {
        val link = FakeFrameLink()
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            defaultPolicy = RetryPolicy(maxAttempts = 1, timeoutMillis = 10_000),
            scope = backgroundScope,
        )

        val request = async { dispatcher.execute(GetBatteryRequest()) }
        runCurrent()
        dispatcher.close()

        assertTrue(request.errorOrNull() is kotlinx.coroutines.CancellationException)
    }
}
