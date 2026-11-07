package com.valhalla.feature

import com.valhalla.core.command.FrameLink
import com.valhalla.core.protocol.Frame
import com.valhalla.core.protocol.message.DeviceNotification
import com.valhalla.core.protocol.message.DeviceRequest
import com.valhalla.core.protocol.message.DeviceResponse
import com.valhalla.core.protocol.message.MessageCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 假的 [FrameLink]：记录下行帧，按协议解码请求后由 [responder] 生成应答帧回灌上行。
 *
 * 上行使用无界 Channel 而不是 SharedFlow，保证应答在收集器尚未启动时也不会丢失，
 * 让并发/乱序用例在测试调度器下保持确定性。
 */
class FakeFrameLink(
    var responder: suspend (DeviceRequest) -> DeviceResponse? = { null },
) : FrameLink {
    private val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    override val incoming: Flow<Frame> = incomingChannel.receiveAsFlow()

    /** 按发送顺序记录的完整帧。 */
    val frames: MutableList<Frame> = mutableListOf()

    /** 成功解码后的请求模型，便于断言强类型字段。 */
    val requests: MutableList<DeviceRequest> = mutableListOf()

    /** 每次发送前注入行为，index 从 1 开始；可用于模拟 GATT 写失败或延迟。 */
    var onSend: suspend (index: Int, frame: Frame) -> Unit = { _, _ -> }

    override suspend fun send(frame: Frame) {
        frames +=
            Frame(
                type = frame.type,
                sequence = frame.sequence,
                payload = frame.payloadCopy(),
                isResponse = frame.isResponse,
                isNotification = frame.isNotification,
            )
        onSend(frames.size, frame)
        val request = MessageCodec.decodeRequest(frame.type, frame.payload)
        requests += request
        val response = responder(request) ?: return
        incomingChannel.trySend(
            Frame(
                type = response.command,
                sequence = frame.sequence,
                payload = response.encode(),
                isResponse = true,
            ),
        )
    }

    /** 模拟设备主动通知（如 OTA 进度）。 */
    fun emitNotification(notification: DeviceNotification) {
        incomingChannel.trySend(
            Frame(
                type = notification.command,
                sequence = 0,
                payload = notification.encode(),
                isNotification = true,
            ),
        )
    }
}
