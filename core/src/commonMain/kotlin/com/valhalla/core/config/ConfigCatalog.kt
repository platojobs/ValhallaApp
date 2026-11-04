package com.valhalla.core.config

/** 配置项的基础类型；协议层 value 仍是裸字节，由命令表约定解释方式。 */
enum class ConfigValueType {
    STRING,
    BOOLEAN,
    INTEGER,
}

/**
 * 命令表中的配置键定义。
 *
 * 可编辑键必须给出类型与边界；只读键用于展示设备信息或未在命令表中定义的键。
 */
data class ConfigKeySpec(
    val key: String,
    val displayName: String,
    val type: ConfigValueType,
    val editable: Boolean,
    val maxLength: Int = 64,
    val intRange: IntRange = 0..0,
    val readOnlyReason: String? = null,
) {

    init {
        require(key.isNotBlank()) { "配置键不能为空" }
        require(maxLength > 0) { "maxLength 必须大于 0" }
        if (type == ConfigValueType.INTEGER && editable) {
            require(intRange.first <= intRange.last) { "整数区间非法: $intRange" }
        }
    }
}

sealed interface ConfigValidation {

    data class Valid(
        val bytes: ByteArray,
        val canonicalText: String,
    ) : ConfigValidation

    data class Invalid(val message: String) : ConfigValidation
}

/**
 * 配置命令表：定义哪些键可编辑、value 的基础类型与长度/范围校验。
 *
 * 约定：
 * - STRING 按 UTF-8 原样下发，长度按字节计算；
 * - BOOLEAN 接受 `true/false`、`1/0`、`on/off`，下发 `1/0`；
 * - INTEGER 以十进制字符串下发，必须在 [ConfigKeySpec.intRange] 内；
 * - 未在表内出现的键一律只读，UI 明确标注原因。
 */
object ConfigCatalog {

    val keys: List<ConfigKeySpec> = listOf(
        ConfigKeySpec(
            key = "device_name",
            displayName = "设备名称",
            type = ConfigValueType.STRING,
            editable = true,
            maxLength = 32,
        ),
        ConfigKeySpec(
            key = "led_enabled",
            displayName = "LED 指示",
            type = ConfigValueType.BOOLEAN,
            editable = true,
        ),
        ConfigKeySpec(
            key = "sample_interval_ms",
            displayName = "采样间隔（毫秒）",
            type = ConfigValueType.INTEGER,
            editable = true,
            intRange = 100..60_000,
        ),
        ConfigKeySpec(
            key = "tx_power_dbm",
            displayName = "发射功率（dBm）",
            type = ConfigValueType.INTEGER,
            editable = true,
            intRange = 0..8,
        ),
        ConfigKeySpec(
            key = "serial_number",
            displayName = "序列号",
            type = ConfigValueType.STRING,
            editable = false,
            maxLength = 64,
            readOnlyReason = "设备只读字段",
        ),
        ConfigKeySpec(
            key = "hardware_version",
            displayName = "硬件版本",
            type = ConfigValueType.STRING,
            editable = false,
            maxLength = 64,
            readOnlyReason = "设备只读字段",
        ),
    )

    fun find(key: String): ConfigKeySpec? = keys.firstOrNull { it.key == key }

    /** 未在命令表定义的键按只读处理，避免 UI 允许下发未知配置。 */
    fun findOrReadOnly(key: String): ConfigKeySpec =
        find(key) ?: ConfigKeySpec(
            key = key,
            displayName = key,
            type = ConfigValueType.STRING,
            editable = false,
            readOnlyReason = "未在命令表定义，仅只读",
        )

    fun validate(spec: ConfigKeySpec, text: String): ConfigValidation {
        if (!spec.editable) {
            return ConfigValidation.Invalid(spec.readOnlyReason ?: "该配置项只读")
        }
        return when (spec.type) {
            ConfigValueType.STRING -> validateString(spec, text)
            ConfigValueType.BOOLEAN -> validateBoolean(text)
            ConfigValueType.INTEGER -> validateInteger(spec, text)
        }
    }

    /** 把设备返回的原始 value 转换为编辑框文本；无法识别时退化为十六进制。 */
    fun format(spec: ConfigKeySpec, raw: ByteArray): String = when (spec.type) {
        ConfigValueType.STRING -> raw.decodeToString()
        ConfigValueType.BOOLEAN -> when (raw.firstOrNull()?.toInt()?.and(0xFF)) {
            1, '1'.code, 't'.code, 'T'.code -> "true"
            0, '0'.code, 'f'.code, 'F'.code -> "false"
            else -> raw.decodeToString()
        }

        ConfigValueType.INTEGER -> raw.decodeToString().trim().ifEmpty {
            raw.firstOrNull()?.toInt()?.and(0xFF)?.toString() ?: ""
        }
    }

    private fun validateString(spec: ConfigKeySpec, text: String): ConfigValidation {
        if (text.isBlank()) return ConfigValidation.Invalid("值不能为空")
        val length = text.encodeToByteArray().size
        if (length > spec.maxLength) {
            return ConfigValidation.Invalid("长度 $length 字节超过上限 ${spec.maxLength} 字节")
        }
        return ConfigValidation.Valid(text.encodeToByteArray(), text)
    }

    private fun validateBoolean(text: String): ConfigValidation =
        when (text.trim().lowercase()) {
            "true", "1", "on" -> ConfigValidation.Valid(byteArrayOf(1), "true")
            "false", "0", "off" -> ConfigValidation.Valid(byteArrayOf(0), "false")
            else -> ConfigValidation.Invalid("布尔值仅支持 true/false、1/0、on/off")
        }

    private fun validateInteger(spec: ConfigKeySpec, text: String): ConfigValidation {
        val value = text.trim().toIntOrNull()
            ?: return ConfigValidation.Invalid("必须为十进制整数")
        if (value !in spec.intRange) {
            return ConfigValidation.Invalid("取值需在 ${spec.intRange.first}..${spec.intRange.last} 之间")
        }
        return ConfigValidation.Valid(value.toString().encodeToByteArray(), value.toString())
    }
}
