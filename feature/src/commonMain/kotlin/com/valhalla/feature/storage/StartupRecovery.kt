package com.valhalla.feature.storage

import com.valhalla.core.firmware.OtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.profile.DeviceProfileCodec
import com.valhalla.core.profile.DeviceProfileStore
import com.valhalla.core.storage.StorageMigrationStatus

/** 启动时对持久化 OTA 断点的校正结果；只描述真实持久化数据，不恢复「传输中」等运行态。 */
sealed interface StartupOtaCheckpoint {
    data object None : StartupOtaCheckpoint

    data class Resumable(
        val point: OtaResumePoint,
    ) : StartupOtaCheckpoint

    data class Invalid(
        val reason: String,
    ) : StartupOtaCheckpoint
}

/**
 * 启动恢复结果：集中展示各存储的 schema 迁移状态与断点校正结果。
 *
 * 进程被杀后重新打开时，运行态（扫描、传输）本就随进程消失；本结果只根据
 * 持久化断点与存储版本给出安全状态，不会把「传输中」等假状态带回界面。
 */
data class StartupStorageRecovery(
    val preferencesStatus: StorageMigrationStatus,
    val profileStatus: StorageMigrationStatus,
    val otaCheckpoint: StartupOtaCheckpoint,
    val notice: String?,
)

object StartupRecovery {
    /**
     * 读取并迁移各类存储。
     *
     * [preferencesStatus] 由偏好存储的迁移入口提前返回，避免重复迁移。
     * 设备档案若发生升级会写回新格式；断点损坏/未来版本只报告原因，不静默删除。
     */
    fun inspect(
        profileStore: DeviceProfileStore,
        checkpointStore: OtaCheckpointStore,
        preferencesStatus: StorageMigrationStatus,
    ): StartupStorageRecovery {
        val profile = DeviceProfileCodec.decodeWithStatus(profileStore.loadRaw())
        if (profile.status is StorageMigrationStatus.Migrated) {
            profileStore.saveRaw(DeviceProfileCodec.encode(profile.profiles))
        }
        val checkpoint = checkpointStore.loadWithStatus()
        val checkpointStatus = checkpoint.status
        val checkpointPoint = checkpoint.point
        if (checkpointStatus is StorageMigrationStatus.Migrated && checkpointPoint != null) {
            // 断点升级后写回当前编码，避免每次启动重复迁移。
            checkpointStore.save(checkpointPoint)
        }

        val checkpointState =
            when {
                checkpointStatus is StorageMigrationStatus.Corrupted ->
                    StartupOtaCheckpoint.Invalid(checkpointStatus.reason)
                checkpointStatus is StorageMigrationStatus.FutureVersion ->
                    StartupOtaCheckpoint.Invalid(
                        "断点版本 v${checkpointStatus.foundVersion} 高于当前支持版本 v${checkpointStatus.supportedVersion}",
                    )
                checkpointPoint != null -> StartupOtaCheckpoint.Resumable(checkpointPoint)
                else -> StartupOtaCheckpoint.None
            }

        val notices =
            buildList {
                add(preferencesNotice(preferencesStatus))
                add(profileNotice(profile.status))
                add(checkpointNotice(checkpointState))
            }.filterNotNull()

        return StartupStorageRecovery(
            preferencesStatus = preferencesStatus,
            profileStatus = profile.status,
            otaCheckpoint = checkpointState,
            notice = notices.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    private fun preferencesNotice(status: StorageMigrationStatus): String? =
        when (status) {
            StorageMigrationStatus.Current -> null
            is StorageMigrationStatus.Migrated -> "偏好存储已从 v${status.fromVersion} 升级到 v${status.toVersion}。"
            is StorageMigrationStatus.FutureVersion ->
                "偏好存储版本 v${status.foundVersion} 高于当前支持版本 v${status.supportedVersion}，已回退默认值。"
            is StorageMigrationStatus.Corrupted -> "偏好存储版本字段损坏：${status.reason}，已回退默认值。"
        }

    private fun profileNotice(status: StorageMigrationStatus): String? =
        when (status) {
            StorageMigrationStatus.Current -> null
            is StorageMigrationStatus.Migrated -> "设备档案已从 v${status.fromVersion} 升级到 v${status.toVersion}。"
            is StorageMigrationStatus.FutureVersion ->
                "设备档案版本 v${status.foundVersion} 高于当前支持版本 v${status.supportedVersion}，本次按空档案处理。"
            is StorageMigrationStatus.Corrupted -> "设备档案无法解析：${status.reason}，本次按空档案处理。"
        }

    private fun checkpointNotice(state: StartupOtaCheckpoint): String? =
        when (state) {
            StartupOtaCheckpoint.None -> null
            is StartupOtaCheckpoint.Resumable ->
                "检测到未完成的 OTA 断点（${state.point.sentBytes}/${state.point.totalBytes} 字节），重新连接后可续传。"
            is StartupOtaCheckpoint.Invalid -> "上次 OTA 断点无效：${state.reason}；重新升级会从头开始。"
        }
}
