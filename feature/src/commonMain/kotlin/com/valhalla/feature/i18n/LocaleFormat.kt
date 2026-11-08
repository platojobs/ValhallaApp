package com.valhalla.feature.i18n

import com.valhalla.core.logging.formatAbsoluteTime
import com.valhalla.core.logging.formatRelativeTime
import kotlin.math.roundToLong

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_KIB_DOUBLE = 1024.0
private const val ONE_DECIMAL_SCALE = 10.0
private const val ONE_DECIMAL_BASE = 10

/**
 * 按语言本地化的数字/时间格式。
 *
 * 时间统一复用 [formatAbsoluteTime]（绝对时间）与 [formatRelativeTime]（相对时间），
 * 只把标签传给 core，避免 UI 层再写一套时间计算。
 */
class LocaleFormat(
    val language: ResolvedLanguage,
) {
    private val strings = StringResolver(language)

    /** 整数：英文使用千位分隔符，中文不使用（中文界面习惯直接展示原始数字）。 */
    fun number(value: Long): String = if (language == ResolvedLanguage.ENGLISH) groupDigits(value.toString()) else value.toString()

    fun number(value: Int): String = number(value.toLong())

    fun percent(
        current: Long,
        total: Long,
    ): String {
        if (total <= 0L) return "0%"
        return "${(current * 100.0 / total).roundToLong()}%"
    }

    /** 字节数：按 1024 进位，单位文案取自本地化表。 */
    fun bytes(value: Long): String {
        if (value < BYTES_PER_KIB) {
            return strings.t(StringKey.UNIT_BYTES, "value" to number(value))
        }
        if (value < BYTES_PER_KIB * BYTES_PER_KIB) {
            return strings.t(
                StringKey.UNIT_KIB,
                "value" to oneDecimal(value / BYTES_PER_KIB_DOUBLE),
                "bytes" to number(value),
            )
        }
        return strings.t(
            StringKey.UNIT_MIB,
            "value" to oneDecimal(value / (BYTES_PER_KIB_DOUBLE * BYTES_PER_KIB_DOUBLE)),
            "bytes" to number(value),
        )
    }

    /** 相对时间，标签按语言切换，时长计算仍走 core。 */
    fun relativeTime(
        entryMillis: Long,
        nowMillis: Long,
    ): String =
        formatRelativeTime(
            entryMillis = entryMillis,
            nowMillis = nowMillis,
            justNow = strings.t(StringKey.TIME_JUST_NOW),
            agoSuffix = strings.t(StringKey.TIME_AGO_SUFFIX),
            futurePrefix = strings.t(StringKey.TIME_FUTURE_PREFIX),
        )

    /** 绝对时间，复用 core 的本地时间格式化。 */
    fun absoluteTime(
        epochMillis: Long,
        zoneOffsetMinutes: Int,
    ): String = formatAbsoluteTime(epochMillis, zoneOffsetMinutes)

    private fun oneDecimal(value: Double): String {
        val scaled = (value * ONE_DECIMAL_SCALE).roundToLong()
        return "${scaled / ONE_DECIMAL_BASE}.${(scaled % ONE_DECIMAL_BASE).let { if (it < 0) -it else it }}"
    }

    private fun groupDigits(raw: String): String {
        val negative = raw.startsWith('-')
        val digits = if (negative) raw.substring(1) else raw
        val sb = StringBuilder()
        digits.forEachIndexed { index, ch ->
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return if (negative) "-$sb" else sb.toString()
    }
}
