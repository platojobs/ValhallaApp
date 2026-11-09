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
        val requests = entries.filter(::isRequestEntry)
        val responses = entries.filter(::isResponseEntry)
        val pairing = pairRequests(requests, responses)
        val tally = tallyOutcomes(pairing)
        val requestIndexBySequence =
            pairing.requestOrder.withIndex().associate { (index, sequence) -> sequence to index }
        val outOfOrder = countOutOfOrder(pairing.responseBySequence, requestIndexBySequence)
        val errorCodes =
            countErrorCodes(pairing.responseBySequence)
                .values
                .sortedWith(compareByDescending<ErrorCodeCount> { it.count }.thenBy { it.code })

        return ConnectionQualityStats(
            requests = pairing.requestOrder.size,
            responses = responses.size,
            successes = tally.successes,
            deviceErrors = tally.deviceErrors,
            noResponses = tally.noResponses,
            retransmissions = tally.retransmissions,
            outOfOrderResponses = outOfOrder,
            unmatchedResponses = pairing.unmatchedResponses,
            averageLatencyMillis = average(tally.latencySum, tally.latencyCount),
            maxLatencyMillis = tally.maxLatency,
            successRate = successRate(tally.successes, pairing.requestOrder.size),
            errorCodes = errorCodes,
            commands = buildCommandStats(pairing),
            recentFailures = buildRecentFailures(tally.failures, recentFailureLimit),
            insufficientSample = pairing.requestOrder.size < minSamples,
        )
    }

    private fun isRequestEntry(entry: PacketLogEntry): Boolean = entry.direction == FrameDirection.TX && !entry.isNotification && !entry.isResponse

    private fun isResponseEntry(entry: PacketLogEntry): Boolean = entry.direction == FrameDirection.RX && entry.isResponse

    /** 请求按 sequence 归组并配对，保留首次出现顺序用于乱序判断。 */
    private fun pairRequests(
        requests: List<PacketLogEntry>,
        responses: List<PacketLogEntry>,
    ): RequestPairing {
        val requestGroups = LinkedHashMap<Int, MutableList<PacketLogEntry>>()
        requests.forEach { entry ->
            requestGroups.getOrPut(entry.sequence) { mutableListOf() } += entry
        }
        val requestOrder = requestGroups.keys.toList()

        val responseBySequence = HashMap<Int, PacketLogEntry>()
        var unmatchedResponses = 0
        responses.forEach { response ->
            val request = requestGroups[response.sequence]
            when {
                request == null || request.first().command != response.command -> unmatchedResponses++
                // 同一 sequence 的重复应答只取第一条，避免重复计入成功数。
                responseBySequence[response.sequence] == null -> responseBySequence[response.sequence] = response
            }
        }
        return RequestPairing(requestGroups, requestOrder, responseBySequence, unmatchedResponses)
    }

    /** 逐请求汇总成功/失败/无应答/重传与时延。 */
    private fun tallyOutcomes(pairing: RequestPairing): OutcomeTally {
        var successes = 0
        var deviceErrors = 0
        var noResponses = 0
        var retransmissions = 0
        var latencySum = 0L
        var latencyCount = 0
        var maxLatency: Long? = null
        val failures = mutableListOf<Pair<PacketLogEntry, PacketLogEntry?>>()

        pairing.requestOrder.forEach { sequence ->
            val group = pairing.requestGroups.getValue(sequence)
            retransmissions += group.size - 1
            val lastSend = group.last()
            val response = pairing.responseBySequence[sequence]
            when {
                response == null -> {
                    noResponses++
                    failures += lastSend to null
                }

                response.error != null -> {
                    deviceErrors++
                    failures += lastSend to response
                }

                else -> successes++
            }
            if (response != null) {
                val latency = (response.timestampMillis - lastSend.timestampMillis).coerceAtLeast(0L)
                latencySum += latency
                latencyCount++
                maxLatency = maxLatency?.let { maxOf(it, latency) } ?: latency
            }
        }
        return OutcomeTally(
            successes = successes,
            deviceErrors = deviceErrors,
            noResponses = noResponses,
            retransmissions = retransmissions,
            latencySum = latencySum,
            latencyCount = latencyCount,
            maxLatency = maxLatency,
            failures = failures,
        )
    }

    /** 乱序：应答时间顺序与请求发起顺序不一致。 */
    private fun countOutOfOrder(
        responseBySequence: Map<Int, PacketLogEntry>,
        requestIndexBySequence: Map<Int, Int>,
    ): Int {
        var outOfOrder = 0
        var highestRequestIndex = -1
        responseBySequence.values.sortedBy { it.timestampMillis }.forEach { response ->
            val index = requestIndexBySequence[response.sequence] ?: return@forEach
            if (index < highestRequestIndex) {
                outOfOrder++
            } else {
                highestRequestIndex = index
            }
        }
        return outOfOrder
    }

    private fun countErrorCodes(responseBySequence: Map<Int, PacketLogEntry>): Map<String, ErrorCodeCount> {
        val errorCodeCounts = LinkedHashMap<String, ErrorCodeCount>()
        responseBySequence.values
            .filter { it.error != null }
            .forEach { entry ->
                val parsed = parseErrorCode(entry.error!!)
                val existing = errorCodeCounts[parsed.code]
                errorCodeCounts[parsed.code] = existing?.copy(count = existing.count + 1)
                    ?: ErrorCodeCount(parsed.code, parsed.title, 1)
            }
        return errorCodeCounts
    }

    private fun buildRecentFailures(
        failures: List<Pair<PacketLogEntry, PacketLogEntry?>>,
        limit: Int,
    ): List<QualityFailure> =
        failures
            .sortedByDescending { it.first.timestampMillis }
            .take(limit)
            .map(::toQualityFailure)

    private fun toQualityFailure(pair: Pair<PacketLogEntry, PacketLogEntry?>): QualityFailure {
        val (request, response) = pair
        return if (response == null) {
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

    private fun buildCommandStats(pairing: RequestPairing): List<CommandQuality> =
        pairing.requestOrder
            .groupBy {
                pairing.requestGroups
                    .getValue(it)
                    .first()
                    .command
            }.map { (command, sequences) -> commandQuality(command, sequences, pairing) }
            .sortedWith(compareByDescending<CommandQuality> { it.requests }.thenBy { it.command.name })

    private fun commandQuality(
        command: CommandType,
        sequences: List<Int>,
        pairing: RequestPairing,
    ): CommandQuality {
        var successes = 0
        var errors = 0
        var noResponses = 0
        var retransmissions = 0
        var latencySum = 0L
        var latencyCount = 0
        var maxLatency: Long? = null
        sequences.forEach { sequence ->
            val group = pairing.requestGroups.getValue(sequence)
            retransmissions += group.size - 1
            val response = pairing.responseBySequence[sequence]
            when {
                response == null -> noResponses++
                response.error != null -> errors++
                else -> successes++
            }
            if (response != null) {
                val latency = (response.timestampMillis - group.last().timestampMillis).coerceAtLeast(0L)
                latencySum += latency
                latencyCount++
                maxLatency = maxLatency?.let { maxOf(it, latency) } ?: latency
            }
        }
        return CommandQuality(
            command = command,
            requests = sequences.size,
            responses = successes + errors,
            successes = successes,
            deviceErrors = errors,
            noResponses = noResponses,
            retransmissions = retransmissions,
            averageLatencyMillis = average(latencySum, latencyCount),
            maxLatencyMillis = maxLatency,
        )
    }

    private fun average(
        sum: Long,
        count: Int,
    ): Double? = if (count == 0) null else sum.toDouble() / count

    private fun successRate(
        successes: Int,
        requests: Int,
    ): Double? = if (requests == 0) null else successes.toDouble() / requests

    private data class RequestPairing(
        val requestGroups: Map<Int, List<PacketLogEntry>>,
        val requestOrder: List<Int>,
        val responseBySequence: Map<Int, PacketLogEntry>,
        val unmatchedResponses: Int,
    )

    private data class OutcomeTally(
        val successes: Int,
        val deviceErrors: Int,
        val noResponses: Int,
        val retransmissions: Int,
        val latencySum: Long,
        val latencyCount: Int,
        val maxLatency: Long?,
        val failures: List<Pair<PacketLogEntry, PacketLogEntry?>>,
    )

    /** 把 `0x05 鉴权失败` 形式的错误文本解析为稳定错误码与标题。 */
    internal fun parseErrorCode(error: String): ErrorCodeCount {
        val match = errorCodePattern.find(error)
        if (match == null) {
            return ErrorCodeCount(code = error.trim(), title = "", count = 1)
        }
        val normalized =
            "0x" +
                match.value
                    .removePrefix("0x")
                    .removePrefix("0X")
                    .uppercase()
        val status =
            DeviceStatus.fromCode(
                normalized.removePrefix("0x").toIntOrNull(16) ?: DeviceStatus.UNKNOWN_ERROR.code,
            )
        return ErrorCodeCount(code = normalized, title = status.title, count = 1)
    }
}
