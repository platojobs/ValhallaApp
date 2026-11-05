package com.valhalla.core.logging

import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.message.DeviceStatus

/** 单个命令维度的质量统计。 */
data class CommandQuality(
    val command: CommandType,
    val requests: Int,
    val responses: Int,
    val successes: Int,
    val deviceErrors: Int,
    val noResponses: Int,
    val retransmissions: Int,
    val averageLatencyMillis: Double?,
    val maxLatencyMillis: Long?,
)

/** 设备错误码聚合（按出现次数降序）。 */
data class ErrorCodeCount(
    val code: String,
    val title: String,
    val count: Int,
)

/**
 * 一条最近失败记录。
 *
 * [noResponse] 为 true 表示请求在日志窗口内没有配对到应答（超时或链路中断），
 * 此时 [detail] 为「无应答」说明，而不是设备返回的错误码。
 */
data class QualityFailure(
    val command: CommandType,
    val timestampMillis: Long,
    val detail: String,
    val noResponse: Boolean,
)

/**
 * 连接质量摘要（全部由真实报文日志计算，不含任何模拟值）。
 *
 * 口径：
 * - 请求 = TX 且非通知帧；重传 = 同一 sequence 被发送多次时，除首次外的次数；
 * - 应答 = RX 应答帧，按 sequence 与请求配对；
 * - 成功 = 配对成功且应答没有错误；设备错误 = 配对成功但应答带错误码；
 * - 无应答 = 窗口内没有配对到应答（可能仍在途或已超时，统计不区分）；
 * - 时延 = 配对应答时间 - 该 sequence 最后一次发送时间（负数按 0 处理）；
 * - 乱序 = 按应答时间排序后，应答对应的请求序号不是单调递增的次数；
 * - 样本不足 = 请求数少于 [MIN_SAMPLES]，界面应只展示「样本不足（X 条）」。
 */
data class ConnectionQualityStats(
    val requests: Int,
    val responses: Int,
    val successes: Int,
    val deviceErrors: Int,
    val noResponses: Int,
    val retransmissions: Int,
    val outOfOrderResponses: Int,
    val unmatchedResponses: Int,
    val averageLatencyMillis: Double?,
    val maxLatencyMillis: Long?,
    val successRate: Double?,
    val errorCodes: List<ErrorCodeCount>,
    val commands: List<CommandQuality>,
    val recentFailures: List<QualityFailure>,
    val insufficientSample: Boolean,
) {

    val sampleCount: Int get() = requests
}

/** 连接质量统计器：纯函数，输入任意报文日志快照。 */
object ConnectionQualityAnalyzer {

    /** 低于该样本量时不展示成功率与时延结论，只提示「样本不足（X 条）」。 */
    const val MIN_SAMPLES: Int = 20

    private val errorCodePattern = Regex("^0x[0-9A-Fa-f]{2}")

    fun analyze(
        entries: List<PacketLogEntry>,
        recentFailureLimit: Int = 5,
        minSamples: Int = MIN_SAMPLES,
    ): ConnectionQualityStats {
        val requests = entries.filter {
            it.direction == FrameDirection.TX && !it.isNotification && !it.isResponse
        }
        val responses = entries.filter {
            it.direction == FrameDirection.RX && it.isResponse
        }

        // 请求按 sequence 归组，保留首次出现顺序用于乱序判断。
        val requestGroups = LinkedHashMap<Int, MutableList<PacketLogEntry>>()
        requests.forEach { entry ->
            requestGroups.getOrPut(entry.sequence) { mutableListOf() } += entry
        }
        val requestOrder = requestGroups.keys.toList()

        val responseBySequence = HashMap<Int, PacketLogEntry>()
        var unmatchedResponses = 0
        responses.forEach { response ->
            val request = requestGroups[response.sequence]
            if (request == null || request.first().command != response.command) {
                unmatchedResponses++
            } else {
                // 同一 sequence 的重复应答只取第一条，避免重复计入成功数。
                if (responseBySequence[response.sequence] == null) {
                    responseBySequence[response.sequence] = response
                }
            }
        }

        var successes = 0
        var deviceErrors = 0
        var noResponses = 0
        var retransmissions = 0
        var latencySum = 0L
        var latencyCount = 0
        var maxLatency: Long? = null
        val failures = mutableListOf<Pair<PacketLogEntry, PacketLogEntry?>>()

        requestOrder.forEach { sequence ->
            val group = requestGroups.getValue(sequence)
            retransmissions += group.size - 1
            val lastSend = group.last()
            val response = responseBySequence[sequence]
            if (response == null) {
                noResponses++
                failures += lastSend to null
            } else if (response.error != null) {
                deviceErrors++
                failures += lastSend to response
            } else {
                successes++
            }
            if (response != null) {
                val latency = (response.timestampMillis - lastSend.timestampMillis).coerceAtLeast(0L)
                latencySum += latency
                latencyCount++
                maxLatency = maxLatency?.let { maxOf(it, latency) } ?: latency
            }
        }

        // 乱序：应答时间顺序与请求发起顺序不一致。
        val orderedResponses = responseBySequence.values.sortedBy { it.timestampMillis }
        var outOfOrder = 0
        var highestRequestIndex = -1
        val requestIndexBySequence = requestOrder.withIndex().associate { (index, seq) -> seq to index }
        orderedResponses.forEach { response ->
            val index = requestIndexBySequence[response.sequence] ?: return@forEach
            if (index < highestRequestIndex) {
                outOfOrder++
            } else {
                highestRequestIndex = index
            }
        }

        val errorCodeCounts = LinkedHashMap<String, ErrorCodeCount>()
        responseBySequence.values
            .filter { it.error != null }
            .forEach { entry ->
                val parsed = parseErrorCode(entry.error!!)
                val existing = errorCodeCounts[parsed.code]
                errorCodeCounts[parsed.code] = existing?.copy(count = existing.count + 1)
                    ?: ErrorCodeCount(parsed.code, parsed.title, 1)
            }

        val recent = failures
            .sortedByDescending { it.first.timestampMillis }
            .take(recentFailureLimit)
            .map { (request, response) ->
                if (response == null) {
                    QualityFailure(
                        command = request.command,
                        timestampMillis = request.timestampMillis,
                        detail = "无应答",
                        noResponse = true,
                    )
                } else {
                    QualityFailure(
                        command = request.command,
                        timestampMillis = response.timestampMillis,
                        detail = response.error ?: "设备返回错误",
                        noResponse = false,
                    )
                }
            }

        val commandStats = requestOrder
            .groupBy { requestGroups.getValue(it).first().command }
            .map { (command, sequences) ->
                var commandSuccesses = 0
                var commandErrors = 0
                var commandNoResponses = 0
                var commandRetransmissions = 0
                var commandLatencySum = 0L
                var commandLatencyCount = 0
                var commandMaxLatency: Long? = null
                sequences.forEach { sequence ->
                    val group = requestGroups.getValue(sequence)
                    commandRetransmissions += group.size - 1
                    val response = responseBySequence[sequence]
                    when {
                        response == null -> commandNoResponses++
                        response.error != null -> commandErrors++
                        else -> commandSuccesses++
                    }
                    if (response != null) {
                        val latency = (response.timestampMillis - group.last().timestampMillis)
                            .coerceAtLeast(0L)
                        commandLatencySum += latency
                        commandLatencyCount++
                        commandMaxLatency = commandMaxLatency?.let { maxOf(it, latency) } ?: latency
                    }
                }
                CommandQuality(
                    command = command,
                    requests = sequences.size,
                    responses = commandSuccesses + commandErrors,
                    successes = commandSuccesses,
                    deviceErrors = commandErrors,
                    noResponses = commandNoResponses,
                    retransmissions = commandRetransmissions,
                    averageLatencyMillis = if (commandLatencyCount == 0) {
                        null
                    } else {
                        commandLatencySum.toDouble() / commandLatencyCount
                    },
                    maxLatencyMillis = commandMaxLatency,
                )
            }
            .sortedWith(compareByDescending<CommandQuality> { it.requests }.thenBy { it.command.name })

        return ConnectionQualityStats(
            requests = requestOrder.size,
            responses = responses.size,
            successes = successes,
            deviceErrors = deviceErrors,
            noResponses = noResponses,
            retransmissions = retransmissions,
            outOfOrderResponses = outOfOrder,
            unmatchedResponses = unmatchedResponses,
            averageLatencyMillis = if (latencyCount == 0) {
                null
            } else {
                latencySum.toDouble() / latencyCount
            },
            maxLatencyMillis = maxLatency,
            successRate = if (requestOrder.isEmpty()) {
                null
            } else {
                successes.toDouble() / requestOrder.size
            },
            errorCodes = errorCodeCounts.values.sortedWith(
                compareByDescending<ErrorCodeCount> { it.count }.thenBy { it.code },
            ),
            commands = commandStats,
            recentFailures = recent,
            insufficientSample = requestOrder.size < minSamples,
        )
    }

    /** 把 `0x05 鉴权失败` 形式的错误文本解析为稳定错误码与标题。 */
    internal fun parseErrorCode(error: String): ErrorCodeCount {
        val match = errorCodePattern.find(error)
        if (match == null) {
            return ErrorCodeCount(code = error.trim(), title = "", count = 1)
        }
        val normalized = "0x" + match.value.removePrefix("0x").removePrefix("0X").uppercase()
        val status = DeviceStatus.fromCode(
            normalized.removePrefix("0x").toIntOrNull(16) ?: DeviceStatus.UNKNOWN_ERROR.code,
        )
        return ErrorCodeCount(code = normalized, title = status.title, count = 1)
    }
}
