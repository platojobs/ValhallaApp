package com.valhalla.core.config

/**
 * 参数值的基础类型。
 *
 * 与配置项不同，参数由 `paramId` 标识，值的编码方式由命令表约定：
 * - [INTEGER] 按大端 u32 编码（最多 4 字节）；
 * - [BOOLEAN] 下发 1/0；
 * - [ENUM] 下发单字节枚举码；
 * - [STRING] 按 UTF-8 原样下发。
 *
 * 注意：当前参数表是**约定值**，固件尚未逐项确认；界面必须标注「需固件确认」，
 * 不得当作设备上报的真实能力。
 */
enum class ParamValueType {
    INTEGER,
    BOOLEAN,
    ENUM,
    STRING,
}

/** 大端 u32 可表示的最大整数（用 Long 表示避免符号位问题）。 */
const val MAX_PARAM_INT: Long = 0xFFFF_FFFFL

/**
 * 命令表中的参数项定义。
 *
 * [confirmedByFirmware] 为 false 表示该条目来自约定值而非固件确认，
 * 界面与文档都要显式标注。
 */
data class ParamSpec(
    val id: Int,
    val displayName: String,
    val type: ParamValueType,
    val writable: Boolean,
    val intRange: IntRange = 0..0,
    val maxLength: Int = 64,
    val enumValues: Map<Int, String> = emptyMap(),
    val unit: String? = null,
    val readOnlyReason: String? = null,
    val confirmedByFirmware: Boolean = false,
) {

    init {
        require(id in 0..0xFFFF) { "paramId 超出范围: $id" }
        require(displayName.isNotBlank()) { "参数名称不能为空" }
        require(maxLength > 0) { "maxLength 必须大于 0" }
        if (type == ParamValueType.INTEGER && writable) {
            require(intRange.first <= intRange.last) { "整数区间非法: $intRange" }
            require(intRange.last.toLong() <= MAX_PARAM_INT) { "整数超出 4 字节上限: $intRange" }
        }
        if (type == ParamValueType.ENUM) {
            require(enumValues.isNotEmpty()) { "枚举参数必须给出取值表: $id" }
        }
    }

    val firmwareConfirmed: Boolean get() = confirmedByFirmware
}

sealed interface ParamValidation {

    data class Valid(
        val bytes: ByteArray,
        val canonicalText: String,
    ) : ParamValidation

    data class Invalid(val message: String) : ParamValidation
}

/**
 * 参数命令表（约定值，固件未确认）。
 *
 * 只覆盖协议字典中 `SET_PARAM(0x09)` / `GET_PARAM(0x0A)` 的常见条目；
 * 不在表内的 paramId 一律不展示、不允许下发，避免把未知参数写到设备。
 */
object ParamCatalog {
    /** 0x0001 上报间隔，与配置项 `sample_interval_ms` 的边界一致。 */
    private val reportingInterval = ParamSpec(
        id = 0x0001,
        displayName = "上报间隔",
        type = ParamValueType.INTEGER,
        writable = true,
        intRange = 100..60_000,
        unit = "毫秒",
    )

    private val workMode = ParamSpec(
        id = 0x0002,
        displayName = "工作模式",
        type = ParamValueType.ENUM,
        writable = true,
        enumValues = mapOf(0 to "待机", 1 to "采集", 2 to "调试"),
    )

    private val sensitivity = ParamSpec(
        id = 0x0003,
        displayName = "灵敏度等级",
        type = ParamValueType.INTEGER,
        writable = true,
        intRange = 1..5,
    )

    private val deviceTag = ParamSpec(
        id = 0x0004,
        displayName = "设备标识",
        type = ParamValueType.STRING,
        writable = false,
        maxLength = 16,
        readOnlyReason = "设备只读参数",
    )

    private val memoryUsed = ParamSpec(
        id = 0x0005,
        displayName = "内存占用",
        type = ParamValueType.INTEGER,
        writable = false,
        intRange = 0..16_777_215,
        unit = "字节",
        readOnlyReason = "设备只读参数",
    )

    val params: List<ParamSpec> = listOf(
        reportingInterval,
        workMode,
        sensitivity,
        deviceTag,
        memoryUsed,
    )

    fun find(paramId: Int): ParamSpec? = params.firstOrNull { it.id == paramId }

    /** 把设备返回的原始字节转换为编辑框文本；无法识别时退化为十六进制。 */
    fun format(spec: ParamSpec, raw: ByteArray): String = when (spec.type) {
        ParamValueType.INTEGER -> {
            val value = decodeInt(raw)
            value?.toString() ?: raw.joinToString("") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }

        ParamValueType.BOOLEAN -> when (raw.firstOrNull()?.toInt()?.and(0xFF)) {
            1, '1'.code, 't'.code, 'T'.code -> "true"
            0, '0'.code, 'f'.code, 'F'.code -> "false"
            else -> raw.decodeToString()
        }

        ParamValueType.ENUM -> raw.firstOrNull()?.toInt()?.and(0xFF)?.toString()
            ?: raw.decodeToString()

        ParamValueType.STRING -> raw.decodeToString()
    }

    /** 枚举码的可读名称；未知码返回 null，由界面决定如何展示。 */
    fun enumLabel(spec: ParamSpec, code: Int): String? = spec.enumValues[code]

    /**
     * 本地校验并编码参数值；校验失败时不会产生请求。
     *
     * 规则：
     * - 只读参数直接拒绝；
     * - INTEGER 必须是十进制整数且落在 [ParamSpec.intRange]，按大端 u32 编码；
     * - ENUM 必须是取值表中的码；
     * - BOOLEAN 接受 true/false、1/0、on/off；
     * - STRING 非空且 UTF-8 字节长度不超过 [ParamSpec.maxLength]。
     */
    fun validate(spec: ParamSpec, text: String): ParamValidation {
        if (!spec.writable) {
            return ParamValidation.Invalid(spec.readOnlyReason ?: "该参数只读")
        }
        return when (spec.type) {
            ParamValueType.INTEGER -> validateInteger(spec, text)
            ParamValueType.ENUM -> validateEnum(spec, text)
            ParamValueType.BOOLEAN -> validateBoolean(text)
            ParamValueType.STRING -> validateString(spec, text)
        }
    }

    /** 大端 u32 编码；位数不足时补零。 */
    fun encodeInt(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** 大端解码 1..4 字节整数；空值或超长返回 null。 */
    fun decodeInt(raw: ByteArray): Int? {
        if (raw.isEmpty() || raw.size > 4) return null
        var value = 0
        raw.forEach { byte ->
            value = (value shl 8) or (byte.toInt() and 0xFF)
        }
        return value
    }

    private fun validateInteger(spec: ParamSpec, text: String): ParamValidation {
        val value = text.trim().toLongOrNull()
            ?: return ParamValidation.Invalid("必须为十进制整数")
        if (value < spec.intRange.first || value > spec.intRange.last) {
            return ParamValidation.Invalid(
                "取值需在 ${spec.intRange.first}..${spec.intRange.last} 之间",
            )
        }
        if (value > MAX_PARAM_INT) {
            return ParamValidation.Invalid("取值超出 4 字节上限")
        }
        val intValue = value.toInt()
        return ParamValidation.Valid(encodeInt(intValue), intValue.toString())
    }

    private fun validateEnum(spec: ParamSpec, text: String): ParamValidation {
        val code = text.trim().toIntOrNull()
            ?: return ParamValidation.Invalid("请选择有效的枚举码")
        if (code !in spec.enumValues) {
            return ParamValidation.Invalid(
                "枚举码需为 ${spec.enumValues.keys.sorted().joinToString("/")}",
            )
        }
        return ParamValidation.Valid(byteArrayOf(code.toByte()), code.toString())
    }

    private fun validateBoolean(text: String): ParamValidation =
        when (text.trim().lowercase()) {
            "true", "1", "on" -> ParamValidation.Valid(byteArrayOf(1), "true")
            "false", "0", "off" -> ParamValidation.Valid(byteArrayOf(0), "false")
            else -> ParamValidation.Invalid("布尔值仅支持 true/false、1/0、on/off")
        }

    private fun validateString(spec: ParamSpec, text: String): ParamValidation {
        if (text.isBlank()) return ParamValidation.Invalid("值不能为空")
        val bytes = text.encodeToByteArray()
        if (bytes.size > spec.maxLength) {
            return ParamValidation.Invalid(
                "长度 ${bytes.size} 字节超过上限 ${spec.maxLength} 字节",
            )
        }
        return ParamValidation.Valid(bytes, text)
    }
}
