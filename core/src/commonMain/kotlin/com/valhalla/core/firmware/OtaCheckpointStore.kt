package com.valhalla.core.firmware

import com.valhalla.core.storage.StorageMigrationStatus

/**
 * OTA 断点持久化接口。
 *
 * 存储层只保存经过 [OtaResumePointCodec] 编码的字符串；读取时若数据损坏或失效返回 null，
 * 不向调用方抛出平台相关异常。平台实现由 data 层的 `createOtaCheckpointStore()` 提供。
 *
 * [loadWithStatus] 暴露迁移/损坏状态，供启动恢复与界面提示使用；平台实现应覆盖它。
 */
interface OtaCheckpointStore {
    fun load(): OtaResumePoint?

    fun loadWithStatus(): OtaCheckpointDecodeResult = OtaCheckpointDecodeResult(load(), StorageMigrationStatus.Current)

    fun save(point: OtaResumePoint)

    fun clear()
}

/** 内存实现：用于 JVM / 单元测试，进程退出即丢失。[initialRaw] 可注入损坏/未来版本数据。 */
class InMemoryOtaCheckpointStore(
    initialRaw: String? = null,
) : OtaCheckpointStore {
    private var raw: String? = initialRaw

    override fun load(): OtaResumePoint? = loadWithStatus().point

    override fun loadWithStatus(): OtaCheckpointDecodeResult = OtaResumePointCodec.decodeWithStatus(raw)

    override fun save(point: OtaResumePoint) {
        raw = OtaResumePointCodec.encode(point)
    }

    override fun clear() {
        raw = null
    }
}
