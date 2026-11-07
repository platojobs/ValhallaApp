package com.valhalla.core.profile

import com.valhalla.core.storage.StorageMigrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProfileRepositoryTest {
    @Test
    fun `按最近连接时间倒序返回档案`() {
        val store = InMemoryDeviceProfileStore()
        val repository = DeviceProfileRepository(store)
        repository.recordConnection("a", lastConnectedAtEpochMillis = 100)
        repository.recordConnection("b", lastConnectedAtEpochMillis = 300)
        repository.recordConnection("c", lastConnectedAtEpochMillis = 200)

        assertEquals(listOf("b", "c", "a"), repository.list().map { it.deviceId })
        assertEquals(3, repository.count())
        assertEquals(300, repository.get("b")?.lastConnectedAtEpochMillis)
    }

    @Test
    fun `连接与信息刷新更新档案且不清空旧字段`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), clockMillis = { 1_000 })
        repository.recordConnection("dev-1", displayName = "手柄", vendor = "Valhalla", model = "X1")

        val updated =
            repository.updateSnapshot(
                deviceId = "dev-1",
                firmwareVersion = "1.2.3 (build 10, stable)",
                batteryPercent = 85,
                charging = true,
            )
        assertEquals("手柄", updated.displayName)
        assertEquals("X1", updated.model)
        assertEquals(85, updated.lastKnownBatteryPercent)
        assertEquals(1_000, updated.lastConnectedAtEpochMillis)

        val preserved = repository.updateSnapshot(deviceId = "dev-1", batteryPercent = 20)
        assertEquals("1.2.3 (build 10, stable)", preserved.lastKnownFirmwareVersion)
        assertEquals(20, preserved.lastKnownBatteryPercent)
    }

    @Test
    fun `信息刷新遇到未知设备时自动建档`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), clockMillis = { 42 })
        val profile =
            repository.updateSnapshot(
                deviceId = "dev-2",
                model = "X2",
                firmwareVersion = "2.0.0",
                batteryPercent = 66,
            )

        assertEquals(42, profile.lastConnectedAtEpochMillis)
        assertEquals("X2", profile.model)
        assertEquals("dev-2", repository.get("dev-2")?.deviceId)
    }

    @Test
    fun `删除与清空不可逆`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore())
        repository.recordConnection("a")
        repository.recordConnection("b")

        assertTrue(repository.delete("a"))
        assertNull(repository.get("a"))
        assertEquals(1, repository.count())
        assertTrue(!repository.delete("missing"))

        repository.clear()
        assertEquals(0, repository.count())
        assertEquals(emptyList(), repository.list())
    }

    @Test
    fun `超出档案上限时淘汰最久未连接的设备`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), maxProfiles = 2)
        repository.recordConnection("old", lastConnectedAtEpochMillis = 10)
        repository.recordConnection("mid", lastConnectedAtEpochMillis = 20)
        repository.recordConnection("new", lastConnectedAtEpochMillis = 30)

        assertEquals(listOf("new", "mid"), repository.list().map { it.deviceId })
        assertNull(repository.get("old"))
    }

    @Test
    fun `OTA 历史新的在前且受保留条数限制`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), maxOtaHistory = 3)
        repository.recordConnection("dev-1", lastConnectedAtEpochMillis = 10)

        repeat(4) { index ->
            repository.recordOta(
                "dev-1",
                OtaHistoryRecord(
                    firmwareVersion = "1.0.$index",
                    finishedAtEpochMillis = 100L + index,
                    durationMillis = 1_000L + index,
                    outcome = if (index % 2 == 0) OtaOutcome.SUCCEEDED else OtaOutcome.FAILED,
                    errorCode = if (index % 2 == 0) null else "0x0E CRC 校验失败",
                    sentBytes = index * 10,
                    totalBytes = 100,
                ),
            )
        }

        val history = repository.get("dev-1")!!.otaHistory
        assertEquals(3, history.size)
        assertEquals("1.0.3", history[0].firmwareVersion)
        assertEquals(OtaOutcome.FAILED, repository.get("dev-1")!!.lastOtaRecord?.outcome)
        // 被淘汰的是最早一条（1.0.0），保留 1.0.1..1.0.3。
        assertTrue(history.none { it.firmwareVersion == "1.0.0" })
    }

    @Test
    fun `OTA 历史写入不存在的设备时自动建档`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore(), clockMillis = { 7 })
        val record =
            OtaHistoryRecord(
                firmwareVersion = "1.0.0",
                finishedAtEpochMillis = 50,
                durationMillis = 2_000,
                outcome = OtaOutcome.ABORTED,
                detail = "USER_CANCELLED",
            )

        val profile = repository.recordOta("dev-9", record)

        assertEquals(50, profile.lastConnectedAtEpochMillis)
        assertEquals(record, profile.lastOtaRecord)
        assertNotNull(repository.get("dev-9"))
    }

    @Test
    fun `档案编码可往返并保留空值与历史`() {
        val profiles =
            listOf(
                DeviceProfile(
                    deviceId = "设备-001",
                    displayName = "主控 手柄",
                    vendor = null,
                    model = "X1",
                    lastConnectedAtEpochMillis = 1_700_000_000_000,
                    lastKnownFirmwareVersion = "2.1.0 (build 5, beta)",
                    lastKnownBatteryPercent = 77,
                    lastKnownCharging = false,
                    otaHistory =
                        listOf(
                            OtaHistoryRecord(
                                firmwareVersion = "2.1.0 (build 5, beta)",
                                finishedAtEpochMillis = 1_700_000_001_000,
                                durationMillis = 9_000,
                                outcome = OtaOutcome.FAILED,
                                errorCode = "0x0E CRC 校验失败",
                                detail = "链路已断开：请重新连接设备后使用「继续上次升级」",
                                sentBytes = 1_024,
                                totalBytes = 4_096,
                            ),
                        ),
                ),
                DeviceProfile(deviceId = "dev-2", lastConnectedAtEpochMillis = 10),
            )

        val raw = DeviceProfileCodec.encode(profiles)
        val decoded = DeviceProfileCodec.decode(raw)

        assertEquals(profiles, decoded)
    }

    @Test
    fun `损坏或旧版本的存储内容安全回退为空`() {
        val repository = DeviceProfileRepository(InMemoryDeviceProfileStore("not-valid"))
        assertEquals(emptyList(), repository.list())
        assertIs<StorageMigrationStatus.Corrupted>(repository.lastMigrationStatus)

        val versionMismatch = DeviceProfileRepository(InMemoryDeviceProfileStore("v0\nP|dev|"))
        assertEquals(emptyList(), versionMismatch.list())
        assertIs<StorageMigrationStatus.Corrupted>(versionMismatch.lastMigrationStatus)

        val future = DeviceProfileRepository(InMemoryDeviceProfileStore("v9\nP|dev|"))
        assertEquals(emptyList(), future.list())
        assertEquals(StorageMigrationStatus.FutureVersion(9, 1), future.lastMigrationStatus)

        val current = DeviceProfileRepository(InMemoryDeviceProfileStore("v1\nP|dev|name|||100|1.0.0|80|1"))
        assertEquals(listOf("dev"), current.list().map { it.deviceId })
        assertEquals(StorageMigrationStatus.Current, current.lastMigrationStatus)

        val partial =
            DeviceProfileCodec.decode(
                "v1\nP|dev-1|name|||100|1.0.0|80|1\nO|broken\nP|dev-2|name|||200|2.0.0||",
            )
        assertEquals(listOf("dev-1", "dev-2"), partial.map { it.deviceId })
        assertTrue(partial.first().otaHistory.isEmpty())
        assertEquals(null, partial.last().lastKnownBatteryPercent)
    }
}
