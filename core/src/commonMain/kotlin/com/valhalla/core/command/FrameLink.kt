package com.valhalla.core.command

import com.valhalla.core.protocol.Frame
import kotlinx.coroutines.flow.Flow

/**
 * 帧链路抽象：持续产出设备上行帧（应答 + 通知），并提供发送入口。
 *
 * 与 [CommandTransport] 的区别：
 * - [CommandTransport] 是「一问一答」的简单模型，同一时刻只能有一个在途请求；
 * - [FrameLink] 把上行流与下行写拆开，允许调度层并发发起多个请求，
 *   再按 sequence 把应答分发给各自的等待者。
 *
 * 实现方（BLE 适配层）需保证 [send] 内部对写入串行化，避免并发写导致 GATT 忙。
 */
interface FrameLink {
    /** 设备上行帧流，应至少包含所有应答帧与通知帧。 */
    val incoming: Flow<Frame>

    /** 发送一帧请求。实现方无需等待应答，应答会从 [incoming] 返回。 */
    suspend fun send(frame: Frame)
}
