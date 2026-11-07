package com.valhalla.data.pool

/**
 * 一条设备连接会话。
 *
 * 连接池只关心「建立/释放链路」两件事，GATT 读写与鉴权由上层 feature 层各自持有会话扩展能力。
 */
interface ConnectionSession {
    /** 设备唯一标识（BLE MAC / iOS UUID 归一化后的值）。 */
    val deviceId: String

    /** 建立链路。实现方需自行处理平台差异，并在断开时释放底层资源。 */
    suspend fun connect()

    /** 释放链路。要求幂等：重复调用不抛异常。 */
    suspend fun disconnect()
}
