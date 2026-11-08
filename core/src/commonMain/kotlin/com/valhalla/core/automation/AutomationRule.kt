package com.valhalla.core.automation

private const val UINT8_MAX = 0xFF
private const val ASCII_DIGIT_START = 0x30
private const val ASCII_DIGIT_END = 0x39
private const val ASCII_UPPER_START = 0x41
private const val ASCII_UPPER_END = 0x5A
private const val ASCII_LOWER_START = 0x61
private const val ASCII_LOWER_END = 0x7A
private const val ASCII_PRINTABLE_MAX = 0x7E
private const val ORDER_FIELD_INDEX = 5
private const val PRESET_FIELD_INDEX = 6

/** 触发时机；只在本 App 前台运行期间发生。 */
enum class AutomationTrigger {
    /** 连接（含自动重连）成功后。 */
    CONNECTED,

    /** OTA 成功后。 */
    OTA_SUCCESS,
}

/** 可执行的本地动作；全部通过现有协议与本地数据完成，不依赖设备侧规则。 */
enum class AutomationAction {
    READ_DEVICE_INFO,
    READ_BATTERY,
    RUN_HEALTH_CHECK,
    APPLY_PRESET,
    READ_FIRMWARE_VERSION,
    GENERATE_DIAGNOSTIC,
}

/** 一条自动化规则。 */
data class AutomationRule(
    val id: String,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val enabled: Boolean = true,
    /** 预设动作引用的预设 id；其它动作忽略该字段。 */
    val presetId: String? = null,
    /** 展示与执行顺序；数值小的先执行。 */
    val order: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "规则 id 不能为空" }
        require(order >= 0) { "顺序不能为负" }
        require(action != AutomationAction.APPLY_PRESET || !presetId.isNullOrBlank()) {
            "应用预设动作必须指定预设"
        }
    }
}

/** 规则原始字符串存储。 */
interface AutomationRuleStore {
    fun loadRaw(): String?

    fun saveRaw(raw: String)
}

class InMemoryAutomationRuleStore(
    private var raw: String? = null,
) : AutomationRuleStore {
    override fun loadRaw(): String? = raw

    override fun saveRaw(raw: String) {
        this.raw = raw
    }
}

/**
 * 规则的版本化行式编码：`v1` 头 + `R|id|trigger|action|enabled|order|presetId`。
 * 无法解析的行跳过；整表损坏时回退空列表。
 */
object AutomationRuleCodec {
    private const val VERSION = "v1"
    private const val RULE = "R"
    private const val SEPARATOR = '|'
    private const val PARTS = 7
    private const val EMPTY = "-"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(rules: List<AutomationRule>): String =
        buildString {
            append(VERSION)
            append('\n')
            rules.forEach { rule ->
                append(RULE)
                append(SEPARATOR)
                append(escape(rule.id))
                append(SEPARATOR)
                append(rule.trigger.name)
                append(SEPARATOR)
                append(rule.action.name)
                append(SEPARATOR)
                append(if (rule.enabled) "1" else "0")
                append(SEPARATOR)
                append(rule.order)
                append(SEPARATOR)
                append(escape(rule.presetId ?: EMPTY))
                append('\n')
            }
        }

    fun decode(raw: String?): List<AutomationRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) null else parse(line.split(SEPARATOR))
        }
    }

    private fun parse(parts: List<String>): AutomationRule? {
        if (parts.size != PARTS || parts[0] != RULE) return null
        val id = unescape(parts[1]) ?: return null
        val trigger = AutomationTrigger.entries.firstOrNull { it.name == parts[2] } ?: return null
        val action = AutomationAction.entries.firstOrNull { it.name == parts[3] } ?: return null
        val enabled =
            when (parts[4]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
        val order = parts[ORDER_FIELD_INDEX].toIntOrNull() ?: return null
        val presetRaw = unescape(parts[PRESET_FIELD_INDEX])
        val presetId = presetRaw?.takeUnless { it == EMPTY }
        return runCatching {
            AutomationRule(
                id = id,
                trigger = trigger,
                action = action,
                enabled = enabled,
                presetId = presetId,
                order = order,
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
 * 规则仓储：新增、开关、排序、删除与整表替换。
 *
 * 列表顺序以 [AutomationRule.order] 为准；重排后自动重新编号，避免空洞影响展示。
 */
class AutomationRuleRepository(
    private val store: AutomationRuleStore,
    private val maxRules: Int = DEFAULT_MAX_RULES,
    private val clockMillis: () -> Long,
) {
    init {
        require(maxRules > 0) { "maxRules 必须大于 0" }
    }

    fun list(): List<AutomationRule> =
        load().sortedWith(
            compareBy<AutomationRule> { it.order }.thenBy { it.id },
        )

    /** 新增规则；达到上限时返回 null。 */
    fun add(
        trigger: AutomationTrigger,
        action: AutomationAction,
        presetId: String? = null,
    ): AutomationRule? {
        val existing = load()
        if (existing.size >= maxRules) return null
        val rule =
            AutomationRule(
                id = "rule-${clockMillis()}-${existing.size + 1}",
                trigger = trigger,
                action = action,
                presetId = presetId,
                order = existing.size,
            )
        persist(existing + rule)
        return rule
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean = update(id) { it.copy(enabled = enabled) }

    fun delete(id: String): Boolean {
        val existing = load()
        if (existing.none { it.id == id }) return false
        persist(renumber(existing.filterNot { it.id == id }))
        return true
    }

    /** 上移/下移；越界时不做变化并返回 false。 */
    fun move(
        id: String,
        delta: Int,
    ): Boolean {
        val ordered = list().toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = index + delta
        if (target !in ordered.indices) return false
        val item = ordered.removeAt(index)
        ordered.add(target, item)
        persist(renumber(ordered))
        return true
    }

    fun replaceAll(rules: List<AutomationRule>): Boolean =
        runCatching {
            store.saveRaw(AutomationRuleCodec.encode(renumber(rules.take(maxRules)) { it.copy(enabled = it.enabled) }))
            true
        }.getOrDefault(false)

    fun clear() {
        runCatching { store.saveRaw(AutomationRuleCodec.encode(emptyList())) }
    }

    private fun update(
        id: String,
        transform: (AutomationRule) -> AutomationRule,
    ): Boolean {
        val existing = load()
        if (existing.none { it.id == id }) return false
        persist(existing.map { if (it.id == id) transform(it) else it })
        return true
    }

    private fun load(): List<AutomationRule> =
        runCatching {
            AutomationRuleCodec.decode(store.loadRaw())
        }.getOrDefault(emptyList())

    private fun persist(rules: List<AutomationRule>): Boolean =
        runCatching {
            store.saveRaw(AutomationRuleCodec.encode(rules))
            true
        }.getOrDefault(false)

    private fun renumber(rules: List<AutomationRule>): List<AutomationRule> = renumber(rules) { it }

    private fun renumber(
        rules: List<AutomationRule>,
        transform: (AutomationRule) -> AutomationRule,
    ): List<AutomationRule> =
        rules.mapIndexed { index, rule ->
            transform(rule).copy(order = index)
        }

    companion object {
        const val DEFAULT_MAX_RULES: Int = 20
    }
}
