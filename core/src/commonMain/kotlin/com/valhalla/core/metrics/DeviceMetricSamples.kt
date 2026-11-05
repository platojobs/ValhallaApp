package com.valhalla.core.metrics

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 一条本机采样。
 *
 * 只记录时间、电量与 RSSI 三个数值，不含设备 ID、报文或任何可识别信息；
 * 设备 ID 只作为存储分片键，不进入编码内容。
 */
data class DeviceMetricSample(
    val timestampMillis: Long,
    val batteryPercent: Int?,
    val rssi: Int?,
) {

    init {
        require(timestampMillis >= 0) { "采样时间不能为负" }
        require(batteryPercent == null || batteryPercent in 0..100) { "电量应在 0..100" }
        require(rssi == null || rssi in -127..20) { "RSSI 超出合理范围" }
    }

    val hasValue: Boolean get() = batteryPercent != null || rssi != null
}

/** 采样序列；界面据此决定是否绘制折线。 */
data class DeviceMetricSeries(
    val deviceId: String,
    val samples: List<DeviceMetricSample>,
) {

    val batteryPoints: List<Pair<Long, Int>>
        get() = samples.mapNotNull { sample ->
            sample.batteryPercent?.let { sample.timestampMillis to it }
        }

    val rssiPoints: List<Pair<Long, Int>>
        get() = samples.mapNotNull { sample ->
            sample.rssi?.let { sample.timestampMillis to it }
        }

    /** 少于 3 个有效点时只展示当前值，不画折线。 */
    val canDrawBattery: Boolean get() = batteryPoints.size >= MIN_POINTS

    val canDrawRssi: Boolean get() = rssiPoints.size >= MIN_POINTS

    companion object {
        const val MIN_POINTS: Int = 3
    }
}

/** 采样序列 + 解码状态；[legacyIgnored] 表示读到的是旧版本格式。 */
data class DeviceMetricSeriesStatus(
    val series: DeviceMetricSeries,
    val legacyIgnored: Boolean,
)

/** 按设备分开存储采样；平台实现只负责字符串读写。 */
interface DeviceMetricStore {

    fun loadRaw(deviceId: String): String?

    fun saveRaw(deviceId: String, raw: String)

    fun clear(deviceId: String)
}

/** 内存实现：JVM 测试与测试宿主使用。 */
class InMemoryDeviceMetricStore : DeviceMetricStore {

    private val values = mutableMapOf<String, String>()

    override fun loadRaw(deviceId: String): String? = values[deviceId]

    override fun saveRaw(deviceId: String, raw: String) {
        values[deviceId] = raw
    }

    override fun clear(deviceId: String) {
        values.remove(deviceId)
    }
}

/**
 * 采样解码结果。
 *
 * [legacyIgnored] 为 true 表示存储里是旧版本/不可识别格式：按空数据处理，
 * 界面应说明「旧采样格式已忽略」，而不是把它当成损坏的当前数据。
 */
data class DeviceMetricDecodeResult(
    val samples: List<DeviceMetricSample>,
    val legacyIgnored: Boolean,
)

/**
 * 采样编码：`v1` 头 + 每行 `S|timestamp|battery|rssi`。
 *
 * 空值写 `-`；任何一行结构损坏都跳过，全部损坏时回退为空。
 * 读取旧版本格式时按空处理并标记 [DeviceMetricDecodeResult.legacyIgnored]。
 */
object DeviceMetricCodec {

    private const val VERSION = "v1"
    private const val SAMPLE = "S"
    private const val EMPTY = "-"
    private const val SEPARATOR = '|'
    private const val PARTS = 4

    fun encode(samples: List<DeviceMetricSample>): String = buildString {
        append(VERSION)
        append('\n')
        samples.sortedBy { it.timestampMillis }.forEach { sample ->
            append(SAMPLE)
            append(SEPARATOR)
            append(sample.timestampMillis)
            append(SEPARATOR)
            append(sample.batteryPercent?.toString() ?: EMPTY)
            append(SEPARATOR)
            append(sample.rssi?.toString() ?: EMPTY)
            append('\n')
        }
    }

    fun decode(raw: String?): List<DeviceMetricSample> = decodeWithStatus(raw).samples

    fun decodeWithStatus(raw: String?): DeviceMetricDecodeResult {
        if (raw.isNullOrBlank()) return DeviceMetricDecodeResult(emptyList(), legacyIgnored = false)
        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.trim()
        if (header != VERSION) {
            // 旧版本或手改格式统一按空处理，并明确标记，避免界面把「读不懂」误判为没有采样。
            return DeviceMetricDecodeResult(emptyList(), legacyIgnored = true)
        }
        val result = mutableListOf<DeviceMetricSample>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEPARATOR)
            if (parts.size != PARTS || parts[0] != SAMPLE) return@forEach
            val timestamp = parts[1].toLongOrNull() ?: return@forEach
            val battery = parts[2].takeIf { it != EMPTY }?.toIntOrNull()
            val rssi = parts[3].takeIf { it != EMPTY }?.toIntOrNull()
            runCatching {
                DeviceMetricSample(timestampMillis = timestamp, batteryPercent = battery, rssi = rssi)
            }.getOrNull()?.let(result::add)
        }
        return DeviceMetricDecodeResult(result, legacyIgnored = false)
    }
}

/**
 * 采样仓储：保留每设备最近 [maxSamples] 个点。
 *
 * 写入即淘汰，读取总是从平台存储最新读取一次（跨进程可见）；
 * 编码损坏时返回空序列，不把坏数据画成趋势。
 */
@OptIn(ExperimentalTime::class)
class DeviceMetricRepository(
    private val store: DeviceMetricStore,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    init {
        require(maxSamples >= DeviceMetricSeries.MIN_POINTS) {
            "maxSamples 至少为 ${DeviceMetricSeries.MIN_POINTS}"
        }
    }

    fun series(deviceId: String): DeviceMetricSeries = seriesWithStatus(deviceId).series

    /** 读采样并保留「旧格式被忽略」标记，供界面说明。 */
    fun seriesWithStatus(deviceId: String): DeviceMetricSeriesStatus {
        require(deviceId.isNotBlank()) { "设备 id 不能为空" }
        val decoded = DeviceMetricCodec.decodeWithStatus(store.loadRaw(deviceId))
        return DeviceMetricSeriesStatus(
            series = DeviceMetricSeries(deviceId, decoded.samples),
            legacyIgnored = decoded.legacyIgnored,
        )
    }

    /** 记录一次采样并立即淘汰超限点；电池与 RSSI 都为空时不写入。 */
    fun record(deviceId: String, batteryPercent: Int?, rssi: Int?): DeviceMetricSeries {
        require(deviceId.isNotBlank()) { "设备 id 不能为空" }
        val sample = DeviceMetricSample(
            timestampMillis = clockMillis(),
            batteryPercent = batteryPercent,
            rssi = rssi,
        )
        if (!sample.hasValue) return series(deviceId)
        val samples = (series(deviceId).samples + sample).takeLast(maxSamples)
        store.saveRaw(deviceId, DeviceMetricCodec.encode(samples))
        return DeviceMetricSeries(deviceId, samples)
    }

    fun clear(deviceId: String) {
        store.clear(deviceId)
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES: Int = 50
    }
}
