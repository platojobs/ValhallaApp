package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import kotlinx.coroutines.runBlocking

/**
 * 报文日志导出基准（v1.6.0）。
 *
 * 独立于常规单测：通过 `./gradlew :core:benchmarkExport` 手动运行，
 * 生成 5000 条、约 16 MiB 的 JSON 导出，记录生成/导出耗时、输出体积与堆占用概览。
 * 结果用于评估流式导出的性能与内存峰值，不参与 CI 常规测试。
 */
private const val DEFAULT_ENTRIES = 5_000
private const val DEFAULT_ITERATIONS = 3

private data class Sample(
    val generateMillis: Long,
    val exportMillis: Long,
    val entryCount: Int,
    val byteSize: Int,
    val peakBufferChars: Int,
    val heapUsedBefore: Long,
    val heapAfterExport: Long,
    val heapRetainedAfterGc: Long,
)

fun main(args: Array<String>) {
    val entryCount = argValue(args, "--entries")?.toIntOrNull() ?: DEFAULT_ENTRIES
    val iterations = argValue(args, "--iterations")?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_ITERATIONS

    println("ValhallaApp 报文日志导出基准")
    println("条目上限：$entryCount；迭代：$iterations（首轮预热，不计入平均值）")
    println("运行环境：JVM ${System.getProperty("java.version")} / ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println()

    val samples = ArrayList<Sample>(iterations)
    repeat(iterations) { index ->
        val warmup = index == 0
        val sample = measure(entryCount)
        samples += sample
        val prefix = if (warmup) "预热" else "第 ${index} 轮"
        println(
            "$prefix：生成 ${sample.generateMillis}ms | 导出 ${sample.exportMillis}ms | " +
                "条目 ${sample.entryCount} | 输出 ${formatBytes(sample.byteSize)} | " +
                "峰值缓冲 ${sample.peakBufferChars} 字符 | 堆占用 ${formatBytes(sample.heapUsedBefore)} → " +
                "导出后 ${formatBytes(sample.heapAfterExport)} → GC 后 ${formatBytes(sample.heapRetainedAfterGc)}",
        )
    }

    val measured = samples.drop(1).ifEmpty { samples }
    val avgExport = measured.map { it.exportMillis }.average()
    val minExport = measured.minOf { it.exportMillis }
    val avgGenerate = measured.map { it.generateMillis }.average()
    val outputBytes = measured.first().byteSize
    val throughput = if (avgExport > 0) outputBytes / 1024.0 / 1024.0 / (avgExport / 1000.0) else 0.0
    val peakHeap = measured.maxOf { it.heapAfterExport }
    val retainedHeap = measured.maxOf { it.heapRetainedAfterGc }

    println()
    println("| 指标 | 结果 |")
    println("| --- | --- |")
    println("| 样本 | $entryCount 条 / ${formatBytes(outputBytes)} 输出 |")
    println("| 生成耗时（平均） | ${"%.1f".format(avgGenerate)} ms |")
    println("| 导出耗时（平均 / 最小） | ${"%.1f".format(avgExport)} ms / ${minExport} ms |")
    println("| 导出吞吐 | ${"%.1f".format(throughput)} MiB/s |")
    println("| 实际导出条目 | ${measured.first().entryCount} |")
    println("| 峰值单块缓冲 | ${measured.maxOf { it.peakBufferChars }} 字符（上限 ${PacketLogExporter.STREAM_CHUNK_CHARS}） |")
    println("| 导出后堆占用（未 GC，含待回收临时对象） | ${formatBytes(peakHeap)} |")
    println("| 导出后保留堆（GC 后） | ${formatBytes(retainedHeap)} |")
    println("| 输出字节/条 | ${outputBytes / measured.first().entryCount} B |")
    println()
    println("说明：堆占用为 Runtime `totalMemory - freeMemory` 近似值，受 GC 时机影响；")
    println("峰值单块缓冲来自 `PacketLogExportResult.peakBufferChars`。")
}

private fun measure(entryCount: Int): Sample {
    val generateStart = System.nanoTime()
    val entries = generateEntries(entryCount)
    val generateMillis = (System.nanoTime() - generateStart) / 1_000_000

    System.gc()
    val heapBefore = usedHeapBytes()

    val exportStart = System.nanoTime()
    val result = runBlocking {
        PacketLogExporter.build(
            entries = entries,
            nowMillis = NOW,
            zoneOffsetMinutes = 480,
            options = PacketLogExportOptions(
                maxEntries = PacketLogExportOptions.MAX_ENTRIES,
                maxBytes = PacketLogExportOptions.MAX_BYTES,
                maxHexCharsPerEntry = 4096,
            ),
            format = PacketLogExportFormat.JSON,
        )
    }
    val exportMillis = (System.nanoTime() - exportStart) / 1_000_000
    val heapAfterExport = usedHeapBytes()
    System.gc()
    val heapRetained = usedHeapBytes()

    return Sample(
        generateMillis = generateMillis,
        exportMillis = exportMillis,
        entryCount = result.entryCount,
        byteSize = result.byteSize,
        peakBufferChars = result.peakBufferChars,
        heapUsedBefore = heapBefore,
        heapAfterExport = heapAfterExport,
        heapRetainedAfterGc = heapRetained,
    )
}

/**
 * 生成 [count] 条确定性样本：每条 HEX 约 3.6 KiB 字符（含唯一 id 前缀），
 * JSON 输出接近 16 MiB 上限。时间戳按 10ms 递减，保证排序结果稳定可复现。
 */
private fun generateEntries(count: Int): List<PacketLogEntry> {
    val payloadSuffix = "00".repeat(1_800)
    return (1L..count.toLong()).map { id ->
        PacketLogEntry(
            id = id,
            timestampMillis = NOW - id * 10,
            direction = if (id % 2 == 0L) FrameDirection.RX else FrameDirection.TX,
            command = if (id % 3 == 0L) CommandType.SET_CONFIG else CommandType.GET_CONFIG,
            sequence = (id % 65_536).toInt(),
            isResponse = id % 2 == 0L,
            isNotification = id % 7 == 0L,
            hex = "A55A" + id.toString().padStart(8, '0') + payloadSuffix,
            summary = "GET_CONFIG key=device_mode attempt=$id",
            error = if (id % 11 == 0L) "设备拒绝：0x03" else null,
        )
    }
}

private fun usedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.2f".format(bytes / 1024.0 / 1024.0)} MiB"
    bytes >= 1024 -> "${"%.1f".format(bytes / 1024.0)} KiB"
    else -> "$bytes B"
}

private fun argValue(args: Array<String>, name: String): String? =
    args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

private const val NOW = 1_793_809_800_000L
