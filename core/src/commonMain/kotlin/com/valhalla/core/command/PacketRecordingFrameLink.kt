package com.valhalla.core.command

import com.valhalla.core.logging.FrameDirection
import com.valhalla.core.logging.InMemoryPacketLog
import com.valhalla.core.protocol.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 报文日志装饰器：不改变 [FrameLink] 语义，只在下行发送前与上行收到后各记一条日志。
 *
 * - 日志写入发生在调度器之外，不参与请求/应答配对，也不会阻塞传输；
 * - `incoming` 仍是冷流，每条链路应只被一个调度器收集（与 [BleFrameLink] 约束一致）；
 * - 日志容量由 [InMemoryPacketLog] 控制，超出后淘汰最旧记录。
 */
class PacketRecordingFrameLink(
    private val delegate: FrameLink,
    private val packetLog: InMemoryPacketLog,
) : FrameLink {

    override val incoming: Flow<Frame> = delegate.incoming.map { frame ->
        packetLog.record(FrameDirection.RX, frame)
        frame
    }

    override suspend fun send(frame: Frame) {
        packetLog.record(FrameDirection.TX, frame)
        delegate.send(frame)
    }
}
