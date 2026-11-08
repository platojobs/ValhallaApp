package com.valhalla.feature.diagnostics

import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportFormat
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.logging.PacketLogExporter
import com.valhalla.core.logging.PacketLogRedaction
import com.valhalla.core.logging.SensitiveTextRedaction
import com.valhalla.core.metrics.DeviceMetricSample
import com.valhalla.feature.settings.JsonValue
import com.valhalla.feature.settings.MiniJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val PROGRESS_HEALTH_REPORT = 0.15f
private const val PROGRESS_CONNECTION_QUALITY = 0.3f
private const val PROGRESS_DEVICE_PROFILE = 0.45f
private const val PROGRESS_DEVICE_METRICS = 0.6f
private const val PROGRESS_PACKET_LOG = 0.75f

/** 诊断包内固定条目；顺序即写入顺序。 */
enum class DiagnosticEntryKind(
    val fileName: String,
) {
    APP_INFO("app-info.json"),
    HEALTH_REPORT("health-report.md"),
    CONNECTION_QUALITY("connection-quality.json"),
    PACKET_LOG("packet-log.json"),
    DEVICE_PROFILE("device-profile.json"),
    DEVICE_METRICS("device-metrics.json"),
}

/**
 * 诊断包生成的输入。
 *
 * 只接收已经存在于本机的真实数据；[healthReport] 为 null 时写「暂无数据」。
 * 所有文本在写入前都会再次经过 [SensitiveTextRedaction]。
 */
data class DiagnosticPackageInput(
    val generatedAtEpochMillis: Long,
    val zoneOffsetMinutes: Int,
    val appVersion: String,
    val buildNumber: String,
    val buildChannel: String,
    val buildCommit: String,
    val deviceId: String,
    val healthReport: String?,
    val connectionQuality: String,
    val deviceProfileSummary: String,
    val packetLogEntries: List<PacketLogEntry>,
    val metricSamples: List<DeviceMetricSample>,
    val packetLogOptions: PacketLogExportOptions = PacketLogExportOptions(),
)

/** 生成进度；[fraction] 0..1。 */
data class DiagnosticPackageProgress(
    val fraction: Float,
    val entry: DiagnosticEntryKind?,
)

/** 单条目元数据（写入 manifest）。 */
data class DiagnosticPackageEntry(
    val name: String,
    val bytes: Int,
    val records: Int,
)

/** 诊断包结果；只包含内存中的文本，是否落盘由调用方决定。 */
data class DiagnosticPackageResult(
    val fileName: String,
    val text: String,
    val byteSize: Int,
    val manifestJson: String,
    val entries: List<DiagnosticPackageEntry>,
    val truncated: Boolean,
    val redacted: Boolean = true,
)

/**
 * 一键诊断包：把本机可导出的排障信息合并为单个 `.vhdiag` 容器。
 *
 * 明确排除：令牌、密钥、凭据、断点文件、固件二进制、未脱敏原文。
 * 构建完成前不触碰文件系统，因此取消或失败都不会留下半成品。
 */
object DiagnosticPackageBuilder {
    const val SCHEMA_VERSION: Int = 1
    const val FILE_EXTENSION: String = "vhdiag"
    const val MANIFEST_NAME: String = "manifest.json"

    /** 容器头；解析方可据此拒绝未知版本。 */
    const val CONTAINER_HEADER: String = "VHDIAG|1"

    /** 导出上限沿用报文导出配置，超出时只截断报文条目并标记 truncated。 */
    @OptIn(ExperimentalTime::class)
    suspend fun build(
        input: DiagnosticPackageInput,
        onProgress: (DiagnosticPackageProgress) -> Unit = {},
    ): DiagnosticPackageResult {
        currentCoroutineContext().ensureActive()
        val redacted = { text: String -> SensitiveTextRedaction.redact(text) }

        onProgress(DiagnosticPackageProgress(0f, DiagnosticEntryKind.APP_INFO))
        val appInfo =
            redacted(
                MiniJson.encode(
                    JsonValue.Obj(
                        linkedMapOf(
                            "schemaVersion" to JsonValue.Num(SCHEMA_VERSION.toDouble(), SCHEMA_VERSION.toString()),
                            "appVersion" to JsonValue.Str(input.appVersion),
                            "buildNumber" to JsonValue.Str(input.buildNumber),
                            "buildChannel" to JsonValue.Str(input.buildChannel),
                            "buildCommit" to JsonValue.Str(input.buildCommit),
                            "generatedAtMillis" to
                                JsonValue.Num(
                                    input.generatedAtEpochMillis.toDouble(),
                                    input.generatedAtEpochMillis.toString(),
                                ),
                            "timezoneOffsetMinutes" to
                                JsonValue.Num(
                                    input.zoneOffsetMinutes.toDouble(),
                                    input.zoneOffsetMinutes.toString(),
                                ),
                        ),
                    ),
                ),
            )
        yield()
        currentCoroutineContext().ensureActive()

        onProgress(DiagnosticPackageProgress(PROGRESS_HEALTH_REPORT, DiagnosticEntryKind.HEALTH_REPORT))
        val health = redacted(input.healthReport ?: "暂无数据 / no data")
        yield()
        currentCoroutineContext().ensureActive()

        onProgress(DiagnosticPackageProgress(PROGRESS_CONNECTION_QUALITY, DiagnosticEntryKind.CONNECTION_QUALITY))
        val quality = redacted(input.connectionQuality)
        yield()
        currentCoroutineContext().ensureActive()

        onProgress(DiagnosticPackageProgress(PROGRESS_DEVICE_PROFILE, DiagnosticEntryKind.DEVICE_PROFILE))
        val profile = redacted(input.deviceProfileSummary)
        yield()
        currentCoroutineContext().ensureActive()

        onProgress(DiagnosticPackageProgress(PROGRESS_DEVICE_METRICS, DiagnosticEntryKind.DEVICE_METRICS))
        val metrics = redacted(renderMetrics(input.metricSamples))
        yield()
        currentCoroutineContext().ensureActive()

        onProgress(DiagnosticPackageProgress(PROGRESS_PACKET_LOG, DiagnosticEntryKind.PACKET_LOG))
        val packetLog =
            PacketLogExporter.build(
                entries = input.packetLogEntries,
                nowMillis = input.generatedAtEpochMillis,
                zoneOffsetMinutes = input.zoneOffsetMinutes,
                options = input.packetLogOptions,
                format = PacketLogExportFormat.JSON,
            )
        val packetText = redacted(packetLog.text)
        currentCoroutineContext().ensureActive()

        val payloads =
            listOf(
                DiagnosticEntryKind.APP_INFO to (appInfo to 1),
                DiagnosticEntryKind.HEALTH_REPORT to (health to (if (input.healthReport == null) 0 else 1)),
                DiagnosticEntryKind.CONNECTION_QUALITY to (quality to 1),
                DiagnosticEntryKind.PACKET_LOG to (packetText to packetLog.entryCount),
                DiagnosticEntryKind.DEVICE_PROFILE to (profile to 1),
                DiagnosticEntryKind.DEVICE_METRICS to (metrics to input.metricSamples.size),
            )

        val entries =
            payloads.map { (kind, payload) ->
                DiagnosticPackageEntry(
                    name = kind.fileName,
                    bytes = payload.first.encodeToByteArray().size,
                    records = payload.second,
                )
            }
        val manifest = buildManifest(input, entries, packetLog.truncated)
        val text = assemble(manifest, payloads)
        onProgress(DiagnosticPackageProgress(1f, null))

        val fileName =
            "valhalla-diagnostic-" +
                input.generatedAtEpochMillis.toString() + "." + FILE_EXTENSION
        return DiagnosticPackageResult(
            fileName = fileName,
            text = text,
            byteSize = text.encodeToByteArray().size,
            manifestJson = manifest,
            entries = entries,
            truncated = packetLog.truncated,
            redacted = true,
        )
    }

    private fun buildManifest(
        input: DiagnosticPackageInput,
        entries: List<DiagnosticPackageEntry>,
        truncated: Boolean,
    ): String =
        MiniJson.encode(
            JsonValue.Obj(
                linkedMapOf(
                    "schemaVersion" to JsonValue.Num(SCHEMA_VERSION.toDouble(), SCHEMA_VERSION.toString()),
                    "generatedAtMillis" to
                        JsonValue.Num(
                            input.generatedAtEpochMillis.toDouble(),
                            input.generatedAtEpochMillis.toString(),
                        ),
                    "appVersion" to JsonValue.Str(input.appVersion),
                    "buildNumber" to JsonValue.Str(input.buildNumber),
                    "buildChannel" to JsonValue.Str(input.buildChannel),
                    "buildCommit" to JsonValue.Str(input.buildCommit),
                    "deviceIdHash" to JsonValue.Str(PacketLogRedaction.hashDeviceId(input.deviceId)),
                    "redacted" to JsonValue.Bool(true),
                    "truncated" to JsonValue.Bool(truncated),
                    "excluded" to
                        JsonValue.Arr(
                            listOf(
                                JsonValue.Str("tokens"),
                                JsonValue.Str("keys"),
                                JsonValue.Str("credentials"),
                                JsonValue.Str("checkpoints"),
                                JsonValue.Str("firmware-binaries"),
                                JsonValue.Str("unredacted-raw"),
                            ),
                        ),
                    "entries" to
                        JsonValue.Arr(
                            entries.map { entry ->
                                JsonValue.Obj(
                                    linkedMapOf(
                                        "name" to JsonValue.Str(entry.name),
                                        "bytes" to JsonValue.Num(entry.bytes.toDouble(), entry.bytes.toString()),
                                        "records" to JsonValue.Num(entry.records.toDouble(), entry.records.toString()),
                                    ),
                                )
                            },
                        ),
                ),
            ),
        )

    private fun assemble(
        manifest: String,
        payloads: List<Pair<DiagnosticEntryKind, Pair<String, Int>>>,
    ): String =
        buildString {
            append(CONTAINER_HEADER).append('\n')
            appendEntry(MANIFEST_NAME, manifest, 0)
            payloads.forEach { (kind, payload) ->
                appendEntry(kind.fileName, payload.first, payload.second)
            }
            append("--END\n")
        }

    private fun StringBuilder.appendEntry(
        name: String,
        content: String,
        records: Int,
    ) {
        append("--ENTRY|")
            .append(name)
            .append('|')
            .append(content.encodeToByteArray().size)
            .append('|')
            .append(records)
            .append('\n')
        append(content)
        if (!content.endsWith('\n')) append('\n')
    }

    private fun renderMetrics(samples: List<DeviceMetricSample>): String =
        buildString {
            append("{\"schemaVersion\":1,\"samples\":[")
            samples.forEachIndexed { index, sample ->
                if (index > 0) append(',')
                append("{\"timestampMillis\":").append(sample.timestampMillis)
                append(",\"batteryPercent\":").append(sample.batteryPercent?.toString() ?: "null")
                append(",\"rssi\":").append(sample.rssi?.toString() ?: "null")
                append('}')
            }
            append("]}")
        }

    @OptIn(ExperimentalTime::class)
    fun proposedFileName(nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String = "valhalla-diagnostic-$nowMillis.$FILE_EXTENSION"
}

/**
 * 容器解析辅助（供自检与测试使用）：只拆出条目，不执行任何反序列化。
 */
object DiagnosticPackageFormat {
    /** 解析失败返回空 Map；manifest 始终以 [DiagnosticPackageBuilder.MANIFEST_NAME] 为键。 */
    fun parseEntries(text: String): Map<String, String> {
        if (!text.startsWith(DiagnosticPackageBuilder.CONTAINER_HEADER)) return emptyMap()
        val result = linkedMapOf<String, String>()
        val chunks = text.split("\n--ENTRY|")
        for (chunk in chunks.drop(1)) {
            val headerEnd = chunk.indexOf('\n')
            if (headerEnd < 0) continue
            val header = chunk.substring(0, headerEnd).split('|')
            if (header.size < 3) continue
            val name = header[0]
            var body = chunk.substring(headerEnd + 1)
            if (body.endsWith("--END\n")) body = body.removeSuffix("--END\n")
            if (body.endsWith('\n')) body = body.dropLast(1)
            result[name] = body
        }
        return result
    }
}
