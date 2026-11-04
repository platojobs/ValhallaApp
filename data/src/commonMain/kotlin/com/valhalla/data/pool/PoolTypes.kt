package com.valhalla.data.pool

/** 连接失败的归类，便于 UI 与埋点区分「排队超时」与「链路异常」。 */
sealed interface PoolFailure {
    /** 排队等待或建链过程超出 [MultiDeviceConnectionPool.connectTimeoutMillis]。 */
    data object Timeout : PoolFailure

    /** 底层链路异常，携带原始错误信息。 */
    data class Transport(val cause: String) : PoolFailure
}

/** 连接请求结果。 */
sealed interface ConnectResult {
    /** 本次请求成功建立链路。 */
    data object Connected : ConnectResult

    /** 该设备已连接，请求被幂等忽略。 */
    data object AlreadyConnected : ConnectResult

    /** 已有同设备连接请求在执行中。 */
    data object InProgress : ConnectResult

    /** 连接失败，具体原因见 [failure]。 */
    data class Failed(val failure: PoolFailure) : ConnectResult
}

/** 连接池广播事件，供日志与设备列表页订阅。 */
sealed interface PoolEvent {
    data class ConnectionStarted(val deviceId: String) : PoolEvent
    data class ConnectionEstablished(val deviceId: String) : PoolEvent
    data class ConnectionFailed(val deviceId: String, val failure: PoolFailure) : PoolEvent
    data class Disconnected(val deviceId: String) : PoolEvent
}
