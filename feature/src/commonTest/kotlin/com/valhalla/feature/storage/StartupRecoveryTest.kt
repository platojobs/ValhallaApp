package com.valhalla.feature.storage

import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.firmware.OtaResumePointCodec
import com.valhalla.core.profile.InMemoryDeviceProfileStore
import com.valhalla.core.storage.StorageMigrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 进程死亡后重新启动的状态校正：
 * 有断点 → 可续传；无断点 → 空闲；断点损坏/未来版本 → 明确失效原因。
 */
class StartupRecoveryTest {
    private val point =
        OtaResumePoint(
            sessionId = 42,
            sentBytes = 1024,
            totalBytes = 4096,
            crc16 = 0x1234,
            firmwareVersion =
                com.valhalla.core.protocol.message
                    .FirmwareVersion(3, 0, 0, 1, "release"),
            crc32 = 0xABCDEF12L,
        )

    private fun inspect(checkpointRaw: String? = null): StartupStorageRecovery =
        StartupRecovery.inspect(
            profileStore = InMemoryDeviceProfileStore(),
            checkpointStore = InMemoryOtaCheckpointStore(checkpointRaw),
            preferencesStatus = StorageMigrationStatus.Current,
        )

    @Test
    fun `有断点重启进入可续传`() {
        val store = InMemoryOtaCheckpointStore()
        store.save(point)

        val result =
            StartupRecovery.inspect(
                profileStore = InMemoryDeviceProfileStore(),
                checkpointStore = store,
                preferencesStatus = StorageMigrationStatus.Current,
            )

        val checkpoint = assertIs<StartupOtaCheckpoint.Resumable>(result.otaCheckpoint)
        assertEquals(1024, checkpoint.point.sentBytes)
        assertEquals(4096, checkpoint.point.totalBytes)
        val notice = assertNotNull(result.notice)
        assertTrue(notice.contains("可续传"))
        // 可续传不会清空断点。
        assertNotNull(store.loadWithStatus().point)
    }

    @Test
    fun `无断点重启保持空闲`() {
        val result = inspect()

        assertEquals(StartupOtaCheckpoint.None, result.otaCheckpoint)
        assertNull(result.notice)
        assertEquals(StorageMigrationStatus.Current, result.profileStatus)
    }

    @Test
    fun `断点数据损坏重启给出失效原因且不崩溃`() {
        val result = inspect("not-a-checkpoint")

        val invalid = assertIs<StartupOtaCheckpoint.Invalid>(result.otaCheckpoint)
        assertTrue(invalid.reason.contains("schemaVersion"))
        val notice = assertNotNull(result.notice)
        assertTrue(notice.contains("断点无效"))
        assertTrue(notice.contains("从头开始"))
    }

    @Test
    fun `未来版本断点安全回退并提示`() {
        val raw = "v9" + OtaResumePointCodec.encode(point).substringAfter("v1")
        val result = inspect(raw)

        val invalid = assertIs<StartupOtaCheckpoint.Invalid>(result.otaCheckpoint)
        assertTrue(invalid.reason.contains("高于当前支持版本"))
        val notice = assertNotNull(result.notice)
        assertTrue(notice.contains("v9"))
    }
}
