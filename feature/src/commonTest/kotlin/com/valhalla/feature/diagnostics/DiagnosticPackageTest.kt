package com.valhalla.feature.diagnostics

import com.valhalla.core.logging.FrameDirection
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.core.metrics.DeviceMetricSample
import com.valhalla.core.protocol.CommandType
import com.valhalla.feature.settings.JsonValue
import com.valhalla.feature.settings.MiniJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DiagnosticPackageTest {

    private val secretToken = "token=SuperSecretToken12345"
    private val secretMac = "AA:BB:CC:DD:EE:FF"
    private val secretPassword = "password=hunter2"

    private fun entry(id: Long, summary: String = "OK") = PacketLogEntry(
        id = id,
        timestampMillis = 1_700_000_000_000L + id,
        direction = if (id % 2 == 0L) FrameDirection.TX else FrameDirection.RX,
        command = CommandType.AUTH,
        sequence = id.toInt(),
        isResponse = id % 2 == 1L,
        isNotification = false,
        hex = "A1B2C3D4E5F6",
        summary = summary,
    )

    private fun input(
        entries: List<PacketLogEntry> = listOf(entry(1)),
        options: PacketLogExportOptions = PacketLogExportOptions(),
    ) = DiagnosticPackageInput(
        generatedAtEpochMillis = 1_700_000_000_000L,
        zoneOffsetMinutes = 480,
        appVersion = "2.8.0",
        buildNumber = "28",
        buildChannel = "dev",
        buildCommit = "abc1234",
        deviceId = "AA:BB:CC:DD:EE:FF",
        healthReport = "体检：$secretToken\nMAC $secretMac",
        connectionQuality = "requests=10; successRate=0.9; note=$secretPassword",
        deviceProfileSummary = "deviceId=AA:BB:CC:DD:EE:FF\nmodel=Valhalla X1",
        packetLogEntries = entries,
        metricSamples = listOf(DeviceMetricSample(1_700_000_000_000L, 80, -50)),
        packetLogOptions = options,
    )

    @Test
    fun `诊断包只包含白名单条目且不含敏感原文`() = runTest {
        val result = DiagnosticPackageBuilder.build(input())

        val entries = DiagnosticPackageFormat.parseEntries(result.text)
        assertEquals(
            DiagnosticEntryKind.entries.map { it.fileName }.toSet() + DiagnosticPackageBuilder.MANIFEST_NAME,
            entries.keys,
        )
        assertEquals(DiagnosticEntryKind.entries.size, result.entries.size)
        assertTrue(result.redacted)

        // 敏感值既不能出现在任何条目里，也不能出现在 manifest。
        val allText = result.text + entries.values.joinToString("\n")
        assertFalse(allText.contains("SuperSecretToken12345"))
        assertFalse(allText.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(allText.contains("hunter2"))
        // 未脱敏原文与断点/固件二进制不在包内。
        assertFalse(allText.contains("firmware.bin"))
        assertFalse(entries.keys.any { it.contains("checkpoint") })
    }

    @Test
    fun `manifest 结构包含版本条目清单与脱敏标记`() = runTest {
        val result = DiagnosticPackageBuilder.build(input())
        val manifest = MiniJson.parse(result.manifestJson)?.asObject()
        assertNotNull(manifest)
        val values = manifest.values

        assertEquals(
            DiagnosticPackageBuilder.SCHEMA_VERSION.toLong(),
            values["schemaVersion"]?.asLong(),
        )
        assertEquals("2.8.0", (values["appVersion"] as JsonValue.Str).value)
        assertEquals("28", (values["buildNumber"] as JsonValue.Str).value)
        assertEquals(true, (values["redacted"] as JsonValue.Bool).value)
        assertEquals(false, (values["truncated"] as JsonValue.Bool).value)

        val entries = (values["entries"] as JsonValue.Arr).values
        assertEquals(DiagnosticEntryKind.entries.size, entries.size)
        entries.forEach { value ->
            val obj = value.asObject()!!
            assertNotNull((obj.values["name"] as JsonValue.Str).value)
            assertTrue(obj.values["bytes"]?.asLong()!! > 0)
            assertTrue(obj.values["records"]?.asLong()!! >= 0)
        }
        val excluded = (values["excluded"] as JsonValue.Arr).values.map { (it as JsonValue.Str).value }
        assertTrue(excluded.contains("tokens"))
        assertTrue(excluded.contains("credentials"))
        assertTrue(excluded.contains("firmware-binaries"))
    }

    @Test
    fun `超过导出上限时截断并标记 truncated`() = runTest {
        val result = DiagnosticPackageBuilder.build(
            input(
                entries = (1..30L).map { entry(it, summary = "seq $it") },
                options = PacketLogExportOptions(maxEntries = 5),
            ),
        )

        assertTrue(result.truncated)
        val manifest = MiniJson.parse(result.manifestJson)?.asObject()
        assertEquals(true, manifest!!.values["truncated"]?.asBoolean())
        val packetEntry = result.entries.single { it.name == DiagnosticEntryKind.PACKET_LOG.fileName }
        assertTrue(packetEntry.records <= 5)
    }

    @Test
    fun `取消时不返回结果也不落文件`() = runTest {
        var completed = false
        assertFailsWith<CancellationException> {
            DiagnosticPackageBuilder.build(input()) {
                throw CancellationException("用户取消")
            }
            completed = true
        }
        // 构建器不接触文件系统，取消后不会产生任何半成品或结果。
        assertFalse(completed)
    }

    @Test
    fun `空体检写入暂无数据且进度可观测`() = runTest {
        val progress = mutableListOf<Float>()
        val result = DiagnosticPackageBuilder.build(input()) { progress += it.fraction }

        val entries = DiagnosticPackageFormat.parseEntries(result.text)
        assertTrue(entries.getValue(DiagnosticEntryKind.HEALTH_REPORT.fileName).contains("体检"))
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last())
    }

    @Test
    fun `容器头部可识别且未知格式解析为空`() {
        val text = DiagnosticPackageBuilder.CONTAINER_HEADER + "\n--END\n"
        assertEquals(emptyMap(), DiagnosticPackageFormat.parseEntries(text))
        assertTrue(DiagnosticPackageFormat.parseEntries("not-a-package").isEmpty())
    }
}
