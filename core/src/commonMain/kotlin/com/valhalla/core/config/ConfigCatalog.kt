package com.valhalla.core.config

import com.valhalla.core.protocol.message.FirmwareVersion

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
    /** 固件版本/型号能力约束；默认通用键，对所有设备可见。 */
    val scope: ConfigScope = ConfigScope.UNRESTRICTED,
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

    /** 能力协商用的版本锚点：固件 v1.0.0 起支持 LED，v1.2.0 起支持采样间隔，v1.3.0 起支持发射功率。 */
    private val v1_0_0 = FirmwareVersion(1, 0, 0, 0, "stable")
    private val v1_2_0 = FirmwareVersion(1, 2, 0, 0, "stable")
    private val v1_3_0 = FirmwareVersion(1, 3, 0, 0, "stable")

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
            scope = ConfigScope(minVersion = v1_0_0),
        ),
        ConfigKeySpec(
            key = "sample_interval_ms",
            displayName = "采样间隔（毫秒）",
            type = ConfigValueType.INTEGER,
            editable = true,
            intRange = 100..60_000,
            scope = ConfigScope(minVersion = v1_2_0),
        ),
        ConfigKeySpec(
            key = "tx_power_dbm",
            displayName = "发射功率（dBm）",
            type = ConfigValueType.INTEGER,
            editable = true,
            intRange = 0..8,
            scope = ConfigScope(
                minVersion = v1_3_0,
                models = setOf("VH-1000", "VH-2000"),
            ),
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

    /**
     * 按设备快照判定单个配置键的能力。
     *
     * 规则：
     * - 通用键（无版本/型号约束）始终可用，不依赖是否读到固件版本；
     * - 声明了约束但固件版本或型号缺失时返回 `TARGET_UNKNOWN`，UI 隐藏该键并提示刷新设备信息；
     * - 版本低于下限/高于上限、型号不在白名单时返回对应状态，UI 隐藏并展示原因。
     */
    fun capability(spec: ConfigKeySpec, target: DeviceConfigTarget): ConfigCapabilityResult {
        val scope = spec.scope
        if (!scope.restricted) return ConfigCapabilityResult.SUPPORTED

        val version = target.firmwareVersion
        if (version == null) {
            return ConfigCapabilityResult(
                status = ConfigCapabilityStatus.TARGET_UNKNOWN,
                supported = false,
                visible = false,
                reason = "未读取固件版本，先刷新设备信息后再判定「${spec.displayName}」",
            )
        }
        scope.minVersion?.let { min ->
            if (compareFirmwareVersions(version, min) < 0) {
                return ConfigCapabilityResult(
                    status = ConfigCapabilityStatus.VERSION_TOO_OLD,
                    supported = false,
                    visible = false,
                    reason = "固件 ${version.shortText()} 低于最低要求 ${min.shortText()}，不支持「${spec.displayName}」",
                )
            }
        }
        scope.maxVersion?.let { max ->
            if (compareFirmwareVersions(version, max) > 0) {
                return ConfigCapabilityResult(
                    status = ConfigCapabilityStatus.VERSION_TOO_NEW,
                    supported = false,
                    visible = false,
                    reason = "固件 ${version.shortText()} 高于支持上限 ${max.shortText()}，不支持「${spec.displayName}」",
                )
            }
        }
        if (scope.models.isNotEmpty()) {
            val model = target.model?.trim().orEmpty()
            if (model.isEmpty()) {
                return ConfigCapabilityResult(
                    status = ConfigCapabilityStatus.TARGET_UNKNOWN,
                    supported = false,
                    visible = false,
                    reason = "未读取设备型号，先刷新设备信息后再判定「${spec.displayName}」",
                )
            }
            if (scope.models.none { it.equals(model, ignoreCase = true) }) {
                return ConfigCapabilityResult(
                    status = ConfigCapabilityStatus.MODEL_UNSUPPORTED,
                    supported = false,
                    visible = false,
                    reason = "型号 $model 不支持「${spec.displayName}」（支持 ${scope.models.joinToString("/")}）",
                )
            }
        }
        return ConfigCapabilityResult.SUPPORTED
    }

    /** 按设备快照返回可见配置键；顺序与 [keys] 一致。 */
    fun visibleKeys(target: DeviceConfigTarget): List<ConfigKeySpec> =
        keys.filter { capability(it, target).visible }

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
