package com.valhalla.core.config

private const val UINT8_MAX = 0xFF
private const val ASCII_DIGIT_START = 0x30
private const val ASCII_DIGIT_END = 0x39
private const val ASCII_UPPER_START = 0x41
private const val ASCII_UPPER_END = 0x5A
private const val ASCII_LOWER_START = 0x61
private const val ASCII_LOWER_END = 0x7A
private const val ASCII_PRINTABLE_MAX = 0x7E

/**
 * 一套命名配置预设（场景方案）。
 *
 * [values] 只保存能力内可见且可写的配置键文本值；写入设备前仍会重新校验能力与取值，
 * 因此旧预设遇到新固件/型号不支持的键不会整包失败。
 */
data class ConfigPreset(
    val id: String,
    val name: String,
    val values: Map<String, String>,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "预设 id 不能为空" }
        require(name.isNotBlank()) { "预设名称不能为空" }
        require(createdAtMillis >= 0) { "创建时间不能为负" }
        require(lastUsedAtMillis >= 0) { "最近使用时间不能为负" }
        require(values.keys.none { it.isBlank() }) { "预设键不能为空" }
    }
}

/** 预设原始字符串存储；平台实现只负责整表读写。 */
interface ConfigPresetStore {
    fun loadRaw(): String?

    fun saveRaw(raw: String)
}

/** 内存实现：JVM 测试与测试宿主使用。 */
class InMemoryConfigPresetStore(
    private var raw: String? = null,
) : ConfigPresetStore {
    override fun loadRaw(): String? = raw

    override fun saveRaw(raw: String) {
        this.raw = raw
    }
}

/**
 * 预设的版本化行式编码。
 *
 * 格式：
 * ```
 * v1
 * P|id|name|created|lastUsed
 * V|key|value
 * ```
 * 字段使用百分号转义；任何结构损坏的预设整体跳过，读取不抛异常。
 */
object ConfigPresetCodec {
    private const val VERSION = "v1"
    private const val PRESET = "P"
    private const val VALUE = "V"
    private const val SEPARATOR = '|'
    private const val PRESET_PARTS = 5
    private const val VALUE_PARTS = 3
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(presets: List<ConfigPreset>): String =
        buildString {
            append(VERSION)
            append('\n')
            presets.forEach { preset ->
                append(PRESET)
                append(SEPARATOR)
                append(escape(preset.id))
                append(SEPARATOR)
                append(escape(preset.name))
                append(SEPARATOR)
                append(preset.createdAtMillis)
                append(SEPARATOR)
                append(preset.lastUsedAtMillis)
                append('\n')
                preset.values.forEach { (key, value) ->
                    append(VALUE)
                    append(SEPARATOR)
                    append(escape(key))
                    append(SEPARATOR)
                    append(escape(value))
                    append('\n')
                }
            }
        }

    fun decode(raw: String?): List<ConfigPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()

        val result = mutableListOf<ConfigPreset>()
        var current: ConfigPreset? = null
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEPARATOR)
            when (parts.firstOrNull()) {
                PRESET -> {
                    current?.let(result::add)
                    current = parsePreset(parts)
                }

                VALUE -> {
                    val preset = current ?: return@forEach
                    if (parts.size != VALUE_PARTS) return@forEach
                    val key = unescape(parts[1]) ?: return@forEach
                    val value = unescape(parts[2]) ?: return@forEach
                    current = preset.copy(values = preset.values + (key to value))
                }

                else -> Unit
            }
        }
        current?.let(result::add)
        return result
    }

    private fun parsePreset(parts: List<String>): ConfigPreset? {
        if (parts.size != PRESET_PARTS) return null
        val id = unescape(parts[1]) ?: return null
        val name = unescape(parts[2]) ?: return null
        val created = parts[3].toLongOrNull() ?: return null
        val lastUsed = parts[4].toLongOrNull() ?: return null
        return runCatching {
            ConfigPreset(
                id = id,
                name = name,
                values = emptyMap(),
                createdAtMillis = created,
                lastUsedAtMillis = lastUsed,
            )
        }.getOrNull()
    }

    private fun escape(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val code = byte.toInt() and UINT8_MAX
                if (isAsciiSafe(code)) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[code shr 4])
                    append(HEX_DIGITS[code and 0x0F])
                }
            }
        }

    private fun isAsciiSafe(code: Int): Boolean =
        code in ASCII_DIGIT_START..ASCII_DIGIT_END ||
            code in ASCII_UPPER_START..ASCII_UPPER_END ||
            code in ASCII_LOWER_START..ASCII_LOWER_END ||
            code == '.'.code ||
            code == '_'.code ||
            code == '~'.code ||
            code == '-'.code

    private fun unescape(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = value[index + 1].digitToIntOrNull(16) ?: return null
                val low = value[index + 2].digitToIntOrNull(16) ?: return null
                bytes += ((high shl 4) or low).toByte()
                index += 3
            } else {
                if (char.code > ASCII_PRINTABLE_MAX) return null
                bytes += char.code.toByte()
                index++
            }
        }
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }
}

/**
 * 预设仓储：保存、重命名、删除、复制与按最近使用排序。
 *
 * 所有写入失败只返回 null/false，读取遇到损坏数据一律回退空列表。
 */
class ConfigPresetRepository(
    private val store: ConfigPresetStore,
    private val maxPresets: Int = DEFAULT_MAX_PRESETS,
    private val clockMillis: () -> Long,
) {
    init {
        require(maxPresets > 0) { "maxPresets 必须大于 0" }
    }

    /** 按最近使用倒序；从未使用过的按创建时间倒序。 */
    fun list(): List<ConfigPreset> =
        load().sortedWith(
            compareByDescending<ConfigPreset> { it.lastUsedAtMillis }
                .thenByDescending { it.createdAtMillis }
                .thenBy { it.name },
        )

    fun find(id: String): ConfigPreset? = load().firstOrNull { it.id == id }

    /** 用当前配置值创建一套预设；名称重复时自动追加序号。 */
    fun saveFromValues(
        name: String,
        values: Map<String, String>,
    ): ConfigPreset? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || values.isEmpty()) return null
        val existing = load()
        if (existing.size >= maxPresets) return null
        val uniqueName = uniqueName(trimmed, existing, ignoreId = null)
        val now = clockMillis()
        val preset =
            ConfigPreset(
                id = "preset-$now-${existing.size + 1}",
                name = uniqueName,
                values = values,
                createdAtMillis = now,
                lastUsedAtMillis = now,
            )
        persist(existing + preset)
        return preset
    }

    fun rename(
        id: String,
        name: String,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val existing = load()
        val target = existing.firstOrNull { it.id == id } ?: return false
        val uniqueName = uniqueName(trimmed, existing, ignoreId = id)
        persist(existing.map { if (it.id == target.id) it.copy(name = uniqueName) else it })
        return true
    }

    fun delete(id: String): Boolean {
        val existing = load()
        if (existing.none { it.id == id }) return false
        persist(existing.filterNot { it.id == id })
        return true
    }

    /** 复制预设；副本追加「副本」并沿用最近使用时间。 */
    fun duplicate(id: String): ConfigPreset? {
        val existing = load()
        if (existing.size >= maxPresets) return null
        val source = existing.firstOrNull { it.id == id } ?: return null
        val now = clockMillis()
        val copy =
            source.copy(
                id = "preset-$now-${existing.size + 1}",
                name = uniqueName("${source.name} Copy", existing, ignoreId = null),
                createdAtMillis = now,
            )
        persist(existing + copy)
        return copy
    }

    /** 标记使用时间，影响列表排序；预设不存在时返回 false。 */
    fun markUsed(id: String): Boolean {
        val existing = load()
        if (existing.none { it.id == id }) return false
        val now = clockMillis()
        persist(existing.map { if (it.id == id) it.copy(lastUsedAtMillis = now) else it })
        return true
    }

    fun replaceAll(presets: List<ConfigPreset>): Boolean =
        runCatching {
            store.saveRaw(
                ConfigPresetCodec.encode(
                    presets.take(maxPresets).map { it.copy(name = it.name.trim().ifEmpty { "Preset" }) },
                ),
            )
            true
        }.getOrDefault(false)

    fun clear() {
        runCatching { store.saveRaw(ConfigPresetCodec.encode(emptyList())) }
    }

    private fun load(): List<ConfigPreset> =
        runCatching {
            ConfigPresetCodec.decode(store.loadRaw())
        }.getOrDefault(emptyList())

    private fun persist(presets: List<ConfigPreset>): Boolean =
        runCatching {
            store.saveRaw(ConfigPresetCodec.encode(presets))
            true
        }.getOrDefault(false)

    private fun uniqueName(
        name: String,
        existing: List<ConfigPreset>,
        ignoreId: String?,
    ): String {
        val taken = existing.filter { it.id != ignoreId }.map { it.name }.toSet()
        if (name !in taken) return name
        var index = 2
        while ("$name ($index)" in taken) index++
        return "$name ($index)"
    }

    companion object {
        const val DEFAULT_MAX_PRESETS: Int = 50
        const val MAX_NAME_LENGTH: Int = 40
    }
}

/** 单键差异状态。 */
enum class PresetFieldStatus {
    /** 当前值与目标不同，确认后会写入。 */
    WILL_CHANGE,

    /** 当前值已经是预设值，不会重复下发。 */
    ALREADY_TARGET,

    /** 设备能力或取值校验不满足，跳过并说明原因。 */
    UNSUPPORTED,
}

/** 一条逐键差异。 */
data class PresetFieldDiff(
    val key: String,
    val currentValue: String?,
    val targetValue: String,
    val status: PresetFieldStatus,
    val reason: String? = null,
)

/**
 * 计算预设与当前设备状态的逐键差异。
 *
 * 只给出差异，不写设备；应用阶段仍要重新做能力/取值校验。
 */
object ConfigPresetDiffer {
    fun diff(
        preset: ConfigPreset,
        target: DeviceConfigTarget,
        currentValues: Map<String, String>,
        catalog: ConfigCatalog = ConfigCatalog,
    ): List<PresetFieldDiff> {
        val knownOrder = catalog.keys.map { it.key }
        val orderedKeys =
            preset.values.keys.sortedWith(
                compareBy<String> { key ->
                    val index = knownOrder.indexOf(key)
                    if (index >= 0) index else Int.MAX_VALUE
                }.thenBy { it },
            )
        return orderedKeys.map { key ->
            val targetValue = preset.values.getValue(key)
            val spec = catalog.find(key)
            val unsupportedReason =
                when {
                    spec == null -> "未在命令表定义，无法写入「$key」"
                    !spec.editable -> spec.readOnlyReason ?: "只读配置项无法写入"
                    else -> {
                        val capability = catalog.capability(spec, target)
                        when {
                            !capability.supported || !capability.visible ->
                                capability.reason ?: "当前设备不支持「$key」"

                            catalog.validate(spec, targetValue) is ConfigValidation.Invalid ->
                                "预设值不合法：" + (
                                    (catalog.validate(spec, targetValue) as ConfigValidation.Invalid).message
                                )

                            else -> null
                        }
                    }
                }
            if (unsupportedReason != null) {
                PresetFieldDiff(
                    key = key,
                    currentValue = currentValues[key],
                    targetValue = targetValue,
                    status = PresetFieldStatus.UNSUPPORTED,
                    reason = unsupportedReason,
                )
            } else {
                val current = currentValues[key]
                val canonical =
                    (catalog.validate(spec!!, targetValue) as ConfigValidation.Valid)
                        .canonicalText
                PresetFieldDiff(
                    key = key,
                    currentValue = current,
                    targetValue = canonical,
                    status =
                        if (current == canonical) {
                            PresetFieldStatus.ALREADY_TARGET
                        } else {
                            PresetFieldStatus.WILL_CHANGE
                        },
                )
            }
        }
    }
}
