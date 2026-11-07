package com.valhalla.feature.settings

/**
 * 备份用最小 JSON 编解码。
 *
 * 只支持对象、数组、字符串、数字、布尔与 null，足够覆盖偏好备份；
 * 解析失败返回 null，调用方按「结构非法」处理。
 */
sealed interface JsonValue {
    data class Obj(
        val values: Map<String, JsonValue>,
    ) : JsonValue

    data class Arr(
        val values: List<JsonValue>,
    ) : JsonValue

    data class Str(
        val value: String,
    ) : JsonValue

    data class Num(
        val value: Double,
        val raw: String,
    ) : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    data object Null : JsonValue

    fun asObject(): Obj? = this as? Obj

    fun asArray(): Arr? = this as? Arr

    fun asString(): String? = (this as? Str)?.value

    fun asLong(): Long? = (this as? Num)?.let { it.value.toLong() }

    fun asDouble(): Double? = (this as? Num)?.value

    fun asBoolean(): Boolean? = (this as? Bool)?.value
}

object MiniJson {
    fun encode(value: JsonValue): String = buildString { write(value, this) }

    fun parse(text: String): JsonValue? = runCatching { Parser(text).parseValue() }.getOrNull()

    private fun write(
        value: JsonValue,
        out: StringBuilder,
    ) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.values.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    writeString(entry.key, out)
                    out.append(':')
                    write(entry.value, out)
                }
                out.append('}')
            }

            is JsonValue.Arr -> {
                out.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(
        value: String,
        out: StringBuilder,
    ) {
        out.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (char.code < 0x20) {
                        out.append("\\u")
                        out.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
    }

    private class Parser(
        private val text: String,
    ) {
        private var index = 0

        fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < text.length) { "意外的输入结尾" }
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else error("非法字符 $char")
            }.also {
                skipWhitespace()
                require(index == text.length) { "存在多余内容" }
            }
        }

        private fun parseObject(): JsonValue.Obj {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(values)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                require(peek() == ':') { "缺少冒号" }
                index++
                values[key] = parseNested()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                    }

                    '}' -> {
                        index++
                        return JsonValue.Obj(values)
                    }

                    else -> error("对象分隔符非法")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(values)
            }
            while (true) {
                values += parseNested()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                    }

                    ']' -> {
                        index++
                        return JsonValue.Arr(values)
                    }

                    else -> error("数组分隔符非法")
                }
            }
        }

        private fun parseNested(): JsonValue {
            skipWhitespace()
            require(index < text.length) { "意外的输入结尾" }
            val char = text[index]
            return when (char) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else error("非法值 $char")
            }
        }

        private fun parseString(): String {
            require(peek() == '"') { "缺少字符串引号" }
            index++
            val builder = StringBuilder()
            while (index < text.length) {
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        require(index < text.length) { "转义不完整" }
                        when (val escaped = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "Unicode 转义不完整" }
                                val code = text.substring(index, index + 4).toInt(16)
                                builder.append(code.toChar())
                                index += 4
                            }

                            else -> error("非法转义 $escaped")
                        }
                    }

                    else -> builder.append(char)
                }
            }
            error("字符串未闭合")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return JsonValue.Num(raw.toDouble(), raw)
        }

        private fun parseLiteral(
            literal: String,
            value: JsonValue,
        ): JsonValue {
            require(text.startsWith(literal, index)) { "非法字面量" }
            index += literal.length
            return value
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
