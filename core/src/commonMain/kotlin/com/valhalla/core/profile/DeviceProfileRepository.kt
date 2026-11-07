package com.valhalla.core.profile

import com.valhalla.core.storage.StorageMigrationStatus
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 设备档案仓储：纯 Kotlin 逻辑 + 平台 KV driver。
 *
 * 设计取舍：
 * - 档案数量小（默认最多 [maxProfiles] 条）、没有关联查询需求，使用「整表读写的键值存储」
 *   即可满足按最近连接排序、单条查询、删除、清空；
 * - 数据库（SQLDelight）需要按平台引入 schema、驱动与迁移，对当前数据规模收益有限；
 * - 平台实现只负责存取字符串（Android SharedPreferences / iOS NSUserDefaults / JVM 内存），
 *   编解码与排序全部在 `commonMain`，可在 `jvmTest` 完整验证。
 *
 * 保留策略：
 * - 设备档案最多保留 [maxProfiles] 条，超出时淘汰「最后连接时间最早」的档案；
 * - 每个档案最多保留 [maxOtaHistory] 条 OTA 历史，新的在前。
 */
@OptIn(ExperimentalTime::class)
class DeviceProfileRepository(
    private val store: DeviceProfileStore,
    private val maxProfiles: Int = DEFAULT_MAX_PROFILES,
    private val maxOtaHistory: Int = DEFAULT_MAX_OTA_HISTORY,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init {
        require(maxProfiles > 0) { "maxProfiles 必须大于 0" }
        require(maxOtaHistory > 0) { "maxOtaHistory 必须大于 0" }
    }

    private var cache: MutableList<DeviceProfile>? = null

    /** 最近一次解码的 schema 状态；未来版本/损坏时为空档案。 */
    var lastMigrationStatus: StorageMigrationStatus = StorageMigrationStatus.Current
        private set

    /** 按最近连接时间倒序返回全部档案；时间相同按设备 id 升序，保证结果稳定。 */
    fun list(): List<DeviceProfile> = all().sortedWith(PROFILE_ORDER)

    /** 单条查询。 */
    fun get(deviceId: String): DeviceProfile? = all().firstOrNull { it.deviceId == deviceId }

    /** 删除一条档案，返回是否确实存在。 */
    fun delete(deviceId: String): Boolean {
        val removed = all().removeAll { it.deviceId == deviceId }
        if (removed) persist()
        return removed
    }

    /** 清空全部档案。 */
    fun clear() {
        cache = mutableListOf()
        store.clear()
    }

    fun count(): Int = all().size

    /**
     * 记录一次成功连接（或首次建档）。
     *
     * 只更新调用方提供的非空字段；[lastConnectedAtEpochMillis] 始终刷新为当前时间。
     */
    fun recordConnection(
        deviceId: String,
        displayName: String? = null,
        vendor: String? = null,
        model: String? = null,
        lastConnectedAtEpochMillis: Long = clockMillis(),
    ): DeviceProfile {
        val existing = all().firstOrNull { it.deviceId == deviceId }
        val updated =
            DeviceProfile(
                deviceId = deviceId,
                displayName = displayName ?: existing?.displayName,
                vendor = vendor ?: existing?.vendor,
                model = model ?: existing?.model,
                lastConnectedAtEpochMillis = lastConnectedAtEpochMillis,
                lastKnownFirmwareVersion = existing?.lastKnownFirmwareVersion,
                lastKnownBatteryPercent = existing?.lastKnownBatteryPercent,
                lastKnownCharging = existing?.lastKnownCharging,
                otaHistory = existing?.otaHistory.orEmpty(),
            )
        replace(updated)
        persist()
        return updated
    }

    /**
     * 读取设备信息后更新快照。档案不存在时自动建档（以当前时间为最后连接时间）。
     *
     * 传入 null 的字段保持旧值，避免单次刷新失败把已有信息清空。
     */
    fun updateSnapshot(
        deviceId: String,
        displayName: String? = null,
        vendor: String? = null,
        model: String? = null,
        firmwareVersion: String? = null,
        batteryPercent: Int? = null,
        charging: Boolean? = null,
    ): DeviceProfile {
        val existing = all().firstOrNull { it.deviceId == deviceId }
        val updated =
            DeviceProfile(
                deviceId = deviceId,
                displayName = displayName ?: existing?.displayName,
                vendor = vendor ?: existing?.vendor,
                model = model ?: existing?.model,
                lastConnectedAtEpochMillis = existing?.lastConnectedAtEpochMillis ?: clockMillis(),
                lastKnownFirmwareVersion = firmwareVersion ?: existing?.lastKnownFirmwareVersion,
                lastKnownBatteryPercent = batteryPercent ?: existing?.lastKnownBatteryPercent,
                lastKnownCharging = charging ?: existing?.lastKnownCharging,
                otaHistory = existing?.otaHistory.orEmpty(),
            )
        replace(updated)
        persist()
        return updated
    }

    /** 追加一条 OTA 历史记录，返回更新后的档案。 */
    fun recordOta(
        deviceId: String,
        record: OtaHistoryRecord,
    ): DeviceProfile {
        val existing = all().firstOrNull { it.deviceId == deviceId }
        val updated =
            DeviceProfile(
                deviceId = deviceId,
                displayName = existing?.displayName,
                vendor = existing?.vendor,
                model = existing?.model,
                lastConnectedAtEpochMillis =
                    existing?.lastConnectedAtEpochMillis
                        ?: record.finishedAtEpochMillis,
                lastKnownFirmwareVersion = existing?.lastKnownFirmwareVersion,
                lastKnownBatteryPercent = existing?.lastKnownBatteryPercent,
                lastKnownCharging = existing?.lastKnownCharging,
                otaHistory = (listOf(record) + existing?.otaHistory.orEmpty()).take(maxOtaHistory),
            )
        replace(updated)
        persist()
        return updated
    }

    /** 主动丢弃内存缓存，下次访问重新解码存储内容（便于跨进程/外部写入场景）。 */
    fun reload() {
        cache = null
    }

    private fun all(): MutableList<DeviceProfile> {
        cache?.let { return it }
        val decoded = DeviceProfileCodec.decodeWithStatus(store.loadRaw())
        lastMigrationStatus = decoded.status
        if (decoded.status is StorageMigrationStatus.Migrated) {
            store.saveRaw(DeviceProfileCodec.encode(decoded.profiles))
        }
        return decoded.profiles.toMutableList().also { cache = it }
    }

    private fun replace(profile: DeviceProfile) {
        val list = all()
        val index = list.indexOfFirst { it.deviceId == profile.deviceId }
        if (index >= 0) list[index] = profile else list += profile
    }

    private fun persist() {
        val trimmed = all().sortedWith(PROFILE_ORDER).take(maxProfiles)
        cache = trimmed.toMutableList()
        store.saveRaw(DeviceProfileCodec.encode(trimmed))
    }

    private companion object {
        const val DEFAULT_MAX_PROFILES = 50
        const val DEFAULT_MAX_OTA_HISTORY = 10

        val PROFILE_ORDER: Comparator<DeviceProfile> =
            compareByDescending<DeviceProfile> { it.lastConnectedAtEpochMillis }
                .thenBy { it.deviceId }
    }
}
