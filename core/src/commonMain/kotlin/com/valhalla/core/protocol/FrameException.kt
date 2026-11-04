package com.valhalla.core.protocol

/** 协议层异常。 */
open class FrameException(message: String) : Exception(message)

/** 编码阶段错误，例如 payload 超长。 */
class FrameEncodeException(message: String) : FrameException(message)

/** 解码阶段错误，例如 SOF 不匹配、长度非法、CRC 校验失败。 */
class FrameDecodeException(message: String) : FrameException(message)
