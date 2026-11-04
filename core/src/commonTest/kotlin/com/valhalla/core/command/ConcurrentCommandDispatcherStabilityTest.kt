package com.valhalla.core.command

import com.valhalla.core.logging.InMemoryDeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.MessageCodec
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.OtaDataResponse
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConcurrentCommandDispatcherStabilityTest {

    private val retry = RetryPolicy(maxAttempts = 1, timeoutMillis = 2_000)

    @Test
    fun `上行流异常被异常边界拦截且下次请求可重建收集器`() = runTest {
        val logger = InMemoryDeviceLogger()
        val link = FailingIncomingLink()
        // 外层再用 SupervisorJob + 记录型 handler 兜住「万一没拦住」的异常，
        // 断言外层 handler 始终未被调用，即可证明 dispatcher 自身完成了拦截。
        val outerErrors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> outerErrors += error },
        )
        val dispatcher = ConcurrentCommandDispatcher(
            link = link,
            logger = logger,
            scope = scope,
        )

        // 第一次：收集流立即抛异常，收集协程应被 handler 结束而不是冒泡到外层。
        val first = async { runCatching { dispatcher.execute(GetBatteryRequest(), retry) } }
        val firstResult = first.await()
        assertTrue(firstResult.isFailure, "收集器死亡期间请求应超时收敛")
        assertTrue(outerErrors.isEmpty(), "异常不应逃逸到外层作用域")
        assertTrue(
            logger.entries().any { it.level == LogLevel.ERROR && it.message.contains("上行收集协程异常终止") },
            "应结构化记录原始异常类型",
        )

        // 第二次：重建收集器，正常收到应答。
        link.replyEnabled = true
        val second = dispatcher.execute(GetBatteryRequest(), retry)
        assertTrue(second is BatteryResponse)
        assertEquals(42, (second as BatteryResponse).percent)
        // 重建后收集协程仍在工作，reason 日志中应包含重建提示。
        assertTrue(logger.entries().any { it.message.contains("已重建上行收集协程") })
    }

    @Test
    fun `并发指令的序号配对在同连接上正确`() = runTest {
        val link = ShuffledReplyLink(seed = 20260101)
        val dispatcher = ConcurrentCommandDispatcher(link = link, scope = backgroundScope)
        val total = 200

        val results = (0 until total).map { index ->
            async {
                val response = dispatcher.execute(
                    OtaDataRequest(offset = index.toLong(), data = byteArrayOf(index.toByte())),
                    retry,
                )
                check(response is OtaDataResponse)
                index to response.receivedBytes
            }
        }.awaitAll()

        assertEquals(total, results.size)
        // 应答携带请求里的 offset；若序号配对错乱，receivedBytes 会与发起方不一致。
        results.forEach { (index, received) ->
            assertEquals(index.toLong(), received, "指令 $index 的应答被错误配对")
        }
        assertEquals(total, link.sentSequences.toSet().size, "每个请求应分配独立序号")
    }

    private class FailingIncomingLink : FrameLink {
        private val channel = Channel<Frame>(Channel.UNLIMITED)
        private var failNextCollection = true

        var replyEnabled = false

        override val incoming: Flow<Frame> = flow {
            if (failNextCollection) {
                failNextCollection = false
                throw IllegalStateException("模拟上行收集异常")
            }
            for (frame in channel) emit(frame)
        }

        override suspend fun send(frame: Frame) {
            if (replyEnabled) {
                channel.trySend(
                    Frame(
                        type = CommandType.GET_BATTERY,
                        sequence = frame.sequence,
                        payload = BatteryResponse(DeviceStatus.OK, 42, true, 4_000).encode(),
                        isResponse = true,
                    ),
                )
            }
        }
    }

    private class ShuffledReplyLink(seed: Int) : FrameLink {
        private val random = Random(seed)
        private val channel = Channel<Frame>(Channel.UNLIMITED)
        val sentSequences = mutableListOf<Int>()

        override val incoming: Flow<Frame> = channel.receiveAsFlow()

        override suspend fun send(frame: Frame) {
            sentSequences += frame.sequence
            val offset = MessageCodec.decodeRequest(CommandType.OTA_DATA, frame.payload) as OtaDataRequest
            // 模拟设备按乱序响应：不同指令的应答顺序与发送顺序无关。
            delay(random.nextLong(0, 10))
            channel.trySend(
                Frame(
                    type = CommandType.OTA_DATA,
                    sequence = frame.sequence,
                    payload = OtaDataResponse(DeviceStatus.OK, offset.offset).encode(),
                    isResponse = true,
                ),
            )
        }
    }
}
