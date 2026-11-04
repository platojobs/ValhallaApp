package com.valhalla.core.firmware

/**
 * OTA 断点持久化接口。
 *
 * 存储层只保存经过 [OtaResumePointCodec] 编码的字符串；读取时若数据损坏或失效返回 null，
 * 不向调用方抛出平台相关异常。平台实现由 data 层的 `createOtaCheckpointStore()` 提供。
 */
interface OtaCheckpointStore {

    fun load(): OtaResumePoint?

    fun save(point: OtaResumePoint)

    fun clear()
}

/** 内存实现：用于 JVM / 单元测试，进程退出即丢失。 */
class InMemoryOtaCheckpointStore : OtaCheckpointStore {

    private var raw: String? = null

    override fun load(): OtaResumePoint? = OtaResumePointCodec.decode(raw)

    override fun save(point: OtaResumePoint) {
        raw = OtaResumePointCodec.encode(point)
    }

    override fun clear() {
        raw = null
    }
}
