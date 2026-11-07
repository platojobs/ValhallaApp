package com.valhalla.core.diagnostics

import com.valhalla.core.logging.CommandQuality
import com.valhalla.core.logging.ConnectionQualityStats
import com.valhalla.core.logging.FrameDirection
import com.valhalla.core.logging.PacketLogEntry
import com.valhalla.core.protocol.CommandType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReportTest {
    private fun input(
        errors: List<DiagnosticReportError> = emptyList(),
        packetLog: List<PacketLogEntry> = emptyList(),
    ): DiagnosticReportInput =
        DiagnosticReportInput(
            generatedAtEpochMillis = 1_700_000_000_000,
            zoneOffsetMinutes = 480,
            appVersion = "2.5.0",
            platform = "Android",
            deviceId = "device-abcdef",
            deviceModel = "Valhalla X1",
            firmwareVersion = "2.4.0 release",
            batteryPercent = 42,
            charging = false,
            configValues =
                listOf(
                    DiagnosticReportValue("广播开关", "开启"),
                    DiagnosticReportValue("设备名称", "X1"),
                ),
            paramValues =
                listOf(
                    DiagnosticReportValue("采样间隔", "1000 ms"),
                    DiagnosticReportValue("未知参数", null, "设备不支持"),
                ),
            recentErrors = errors,
            quality =
                ConnectionQualityStats(
                    requests = 30,
                    responses = 28,
                    successes = 27,
                    deviceErrors = 1,
                    noResponses = 2,
                    retransmissions = 1,
                    outOfOrderResponses = 0,
                    unmatchedResponses = 1,
                    averageLatencyMillis = 18.5,
                    maxLatencyMillis = 64,
                    successRate = 0.9,
                    errorCodes = emptyList(),
                    commands = emptyList<CommandQuality>(),
                    recentFailures = emptyList(),
                    insufficientSample = false,
                ),
            otaHistory =
                listOf(
                    DiagnosticReportOta(
                        firmwareVersion = "2.4.0 release",
                        finishedAtEpochMillis = 1_699_000_000_000,
                        outcome = "FAILED",
                        durationMillis = 12_000,
                        errorCode = "0x05",
                        sentBytes = 512,
                        totalBytes = 1024,
                    ),
                ),
            packetLog = packetLog,
        )

    @Test
    fun `报告包含全部章节与来源声明`() =
        runTest {
            val report = DiagnosticReportComposer.build(input())
            val text = report.text

            DiagnosticReportSection.entries.forEach { section ->
                assertTrue(report.sectionCounts.containsKey(section), "缺少章节计数：$section")
            }
            assertTrue(text.contains("# Valhalla 设备诊断报告"))
            assertTrue(text.contains("数据来源：本机日志"))
            assertTrue(text.contains("设备 ID（脱敏）"))
            assertTrue(text.contains("42%"))
            assertTrue(text.contains("广播开关"))
            assertTrue(text.contains("设备不支持"))
            assertTrue(text.contains("成功率"))
            assertTrue(text.contains("90%"))
            assertEquals(DiagnosticReportFormat.MARKDOWN, report.format)
            assertTrue(report.byteSize > 0)
        }

    @Test
    fun `生成前预估与最终体积一致`() =
        runTest {
            val estimate = DiagnosticReportComposer.estimate(input())
            val built = DiagnosticReportComposer.build(input())
            assertEquals(built.byteSize, estimate.estimatedBytes)
            assertTrue(estimate.sections.size == DiagnosticReportSection.entries.size)
        }

    @Test
    fun `敏感字段不进入报告`() =
        runTest {
            val report =
                DiagnosticReportComposer.build(
                    input(
                        errors =
                            listOf(
                                DiagnosticReportError(
                                    timestampMillis = 1_699_000_000_000,
                                    command = "AUTH",
                                    detail = "credential=abc-secret token=xyz serialNumber=SN-999",
                                ),
                            ),
                        packetLog =
                            listOf(
                                PacketLogEntry(
                                    id = 1,
                                    timestampMillis = 1_699_000_000_000,
                                    direction = FrameDirection.RX,
                                    command = CommandType.AUTH,
                                    sequence = 1,
                                    isResponse = true,
                                    isNotification = false,
                                    hex = "01 02 03 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                    summary = "token=leaked-token",
                                ),
                            ),
                    ),
                )
            val text = report.text
            assertFalse(text.contains("abc-secret"))
            assertFalse(text.contains("leaked-token"))
            assertFalse(text.contains("SN-999"))
            assertFalse(text.contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        }

    @Test
    fun `缺失数据明确标注暂无数据`() =
        runTest {
            val report =
                DiagnosticReportComposer.build(
                    DiagnosticReportInput(
                        generatedAtEpochMillis = 0,
                        zoneOffsetMinutes = 0,
                        appVersion = "2.5.0",
                        platform = "JVM",
                        deviceId = "device-x",
                    ),
                )
            assertTrue(report.text.contains("暂无数据"))
            assertTrue(report.text.contains("暂无错误记录"))
            assertTrue(report.text.contains("暂无 OTA 记录"))
        }

    @Test
    fun `进度回传覆盖全部章节`() =
        runTest {
            val progress = mutableListOf<DiagnosticReportProgress>()
            DiagnosticReportComposer.build(input()) { progress += it }
            assertEquals(DiagnosticReportSection.entries.size, progress.size)
            assertEquals(1f, progress.last().fraction)
            assertEquals(DiagnosticReportProgressPhase.RENDERING, progress.first().phase)
        }

    @Test
    fun `限制条数与负数上限`() =
        runTest {
            val entries =
                (1..25).map { index ->
                    PacketLogEntry(
                        id = index.toLong(),
                        timestampMillis = index.toLong(),
                        direction = FrameDirection.TX,
                        command = CommandType.GET_BATTERY,
                        sequence = index,
                        isResponse = false,
                        isNotification = false,
                        hex = "00",
                        summary = "entry $index",
                    )
                }
            val report =
                DiagnosticReportComposer.build(
                    input(packetLog = entries),
                    DiagnosticReportOptions(packetLogLimit = 5),
                )
            assertEquals(5, report.packetLogCount)
            assertTrue(report.text.contains("entry 25"), "应保留最新的日志")
            assertFalse(report.text.contains("entry 19"), "超出上限的旧日志不应出现")

            val none =
                DiagnosticReportComposer.build(
                    input(packetLog = entries),
                    DiagnosticReportOptions(packetLogLimit = -1),
                )
            assertEquals(0, none.packetLogCount)
        }
}
