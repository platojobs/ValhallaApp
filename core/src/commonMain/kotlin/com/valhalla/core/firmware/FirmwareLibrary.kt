package com.valhalla.core.firmware

import com.valhalla.core.logging.SensitiveTextRedaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 固件库排序方式：最近使用优先，或按版本号优先。 */
enum class FirmwareLibrarySort {
    RECENT,
    VERSION,
}

/** 固件库条目的最近一次使用结果；与 [com.valhalla.core.profile.OtaOutcome] 名称保持一致。 */
enum class FirmwareLibraryOutcome {
    SUCCEEDED,
    FAILED,
    ABORTED,
}

/**
 * 一条固件库记录。
 *
 * 只保存元信息与原始文件引用（[sourcePath]），**不缓存固件二进制本体**：
 * 固件动辄几 MB，复制到应用私有目录既不可控也会放大备份体积；文件本身
 * 仍由系统文件选择器（Android SAF URI / iOS 文件 URL）持有，使用时重新打开并校验。
 *
 * 身份（[id]）由「CRC-32 + payload 总长 + 版本」组成：同一固件无论从哪个路径重新选择，
 * 都归并为同一条记录，避免库中出现重复副本。
 */
data class FirmwareLibraryRecord(
    val fileName: String,
    val version: String,
    val channel: String,
    val modelWhitelist: List<String>,
    val totalBytes: Long,
    val crc32: Long,
    val addedAtEpochMillis: Long,
    val sourcePath: String,
    val lastUsedAtEpochMillis: Long? = null,
    val lastDeviceId: String? = null,
    val lastOutcome: FirmwareLibraryOutcome? = null,
    val favorite: Boolean = false,
) {

    init {
        require(fileName.isNotBlank()) { "固件文件名不能为空" }
        require(version.isNotBlank()) { "固件版本不能为空" }
        require(totalBytes >= 0) { "固件总长不能为负" }
        require(crc32 in 0L..0xFFFFFFFFL) { "CRC-32 超出 u32 范围: $crc32" }
        require(addedAtEpochMillis >= 0) { "引入时间不能为负" }
        require(sourcePath.isNotBlank()) { "固件引用路径不能为空" }
    }

    val id: String get() = "$crc32-$totalBytes-$version"

    /** 是否与一次新的选择相同（同一固件不同路径也视为同一条目）。 */
    fun matches(candidate: FirmwareLibraryRecord): Boolean = id == candidate.id
}

/** 固件库的原始字符串存储；平台侧只负责键值读写。 */
interface FirmwareLibraryStore {

    fun loadRaw(): String?

    fun saveRaw(raw: String)

    fun clear()
}

/** 内存实现：JVM 测试与测试宿主使用。 */
class InMemoryFirmwareLibraryStore(private var raw: String? = null) : FirmwareLibraryStore {

    override fun loadRaw(): String? = raw

    override fun saveRaw(raw: String) {
        this.raw = raw
    }

    override fun clear() {
        raw = null
    }
}

/**
 * 固件库版本化行式编码。
 *
 * 与 `DeviceProfileCodec` 同一思路：小数据量、整表读写、无需序列化库，commonMain 可单测。
 * 格式：
 * ```
 * v1
 * F|id|fileName|version|channel|models|totalBytes|crc32|addedAt|lastUsed|lastDevice|outcome|favorite|path
 * ```
 * 字段百分号转义；空字符串表示 null。结构损坏或版本不匹配时安全回退为空列表。
 */
object FirmwareLibraryCodec {

    private const val VERSION = "v1"
    private const val RECORD = "F"
    private const val SEPARATOR = '|'
    private const val PARTS = 14
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun encode(records: List<FirmwareLibraryRecord>): String = buildString {
        append(VERSION)
        append('\n')
        records.forEach { record ->
            append(RECORD)
            append(SEPARATOR)
            append(escape(record.id))
            append(SEPARATOR)
            append(escape(redactNullable(record.fileName)))
            append(SEPARATOR)
            append(escape(record.version))
            append(SEPARATOR)
            append(escape(record.channel))
            append(SEPARATOR)
            append(escape(record.modelWhitelist.joinToString(",") { redactInline(it) }))
            append(SEPARATOR)
            append(record.totalBytes)
            append(SEPARATOR)
            append(record.crc32)
            append(SEPARATOR)
            append(record.addedAtEpochMillis)
            append(SEPARATOR)
            append(record.lastUsedAtEpochMillis?.toString().orEmpty())
            append(SEPARATOR)
            // 最近使用设备 id 仅作本地提示，属于设备引用而非导出内容；保留原值便于匹配。
            append(escape(record.lastDeviceId))
            append(SEPARATOR)
            append(record.lastOutcome?.name.orEmpty())
            append(SEPARATOR)
            append(if (record.favorite) "1" else "0")
            append(SEPARATOR)
            // 路径/URI 是重新打开固件的唯一引用，必须原样保留；不缓存二进制本体。
            append(escape(record.sourcePath))
            append('\n')
        }
    }

    fun decode(raw: String?): List<FirmwareLibraryRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return emptyList()
        val result = mutableListOf<FirmwareLibraryRecord>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split(SEPARATOR)
            if (parts.size != PARTS || parts[0] != RECORD) return@forEach
            parse(parts)?.let(result::add)
        }
        return result
    }

    private fun parse(parts: List<String>): FirmwareLibraryRecord? {
        val fileName = unescape(parts[2]) ?: return null
        val version = unescape(parts[3]) ?: return null
        val totalBytes = parts[6].toLongOrNull() ?: return null
        val crc32 = parts[7].toLongOrNull() ?: return null
        val addedAt = parts[8].toLongOrNull() ?: return null
        val lastUsed = if (parts[9].isEmpty()) null else parts[9].toLongOrNull() ?: return null
        val outcome = if (parts[11].isEmpty()) {
            null
        } else {
            FirmwareLibraryOutcome.entries.firstOrNull { it.name == parts[11] } ?: return null
        }
        val favorite = when (parts[12]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        val sourcePath = unescape(parts[13]) ?: return null
        val models = unescape(parts[5])?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        return runCatching {
            FirmwareLibraryRecord(
                fileName = fileName,
                version = version,
                channel = unescape(parts[4]).orEmpty(),
                modelWhitelist = models,
                totalBytes = totalBytes,
                crc32 = crc32,
                addedAtEpochMillis = addedAt,
                sourcePath = sourcePath,
                lastUsedAtEpochMillis = lastUsed,
                lastDeviceId = unescape(parts[10]),
                lastOutcome = outcome,
                favorite = favorite,
            )
        }.getOrNull()
    }

    private fun redactNullable(value: String?): String? =
        value?.let { SensitiveTextRedaction.redactInline(it) }

    private fun redactInline(value: String): String = SensitiveTextRedaction.redactInline(value)

    private fun escape(value: String?): String {
        if (value == null) return ""
        return buildString {
            value.encodeToByteArray().forEach { byte ->
                val code = byte.toInt() and 0xFF
                if (isAsciiSafe(code)) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[code shr 4])
                    append(HEX_DIGITS[code and 0x0F])
                }
            }
        }
    }

    private fun isAsciiSafe(code: Int): Boolean =
        code in 0x30..0x39 ||
            code in 0x41..0x5A ||
            code in 0x61..0x7A ||
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
                if (char.code > 0x7E) return null
                bytes += char.code.toByte()
                index++
            }
        }
        return runCatching { bytes.toByteArray().decodeToString() }.getOrNull()
    }
}

/** 固件文件不可用的原因；界面据此本地化提示。 */
enum class FirmwareReuseReason {
    ACCESS_DENIED,
    READ_FAILED,
}

/** 固件文件可访问性与内容校验的结果。 */
sealed interface FirmwareReuseCheck {

    /** 文件仍可打开且 CRC-32 与记录一致，可直接用于升级。 */
    data class Available(val record: FirmwareLibraryRecord) : FirmwareReuseCheck

    /** 文件已删除、被移走或系统不再授权，需要重新选择。 */
    data class Missing(
        val record: FirmwareLibraryRecord,
        val reason: FirmwareReuseReason,
    ) : FirmwareReuseCheck

    /** 文件存在但内容与记录不一致（可能被替换），需要重新选择并预检。 */
    data class CrcMismatch(
        val record: FirmwareLibraryRecord,
        val expectedCrc32: Long,
        val actualCrc32: Long?,
    ) : FirmwareReuseCheck

    data object NotFound : FirmwareReuseCheck
}

/**
 * 固件库：本地归档 + 跨设备复用。
 *
 * 保留策略：默认最多 [DEFAULT_MAX_ENTRIES] 条；超出时优先淘汰「最久未使用且未标常用」的记录，
 * 全部为常用时淘汰最早引入的一条，保证常用标记不会导致库无限增长。
 * 存储损坏、版本不匹配时回退为空列表，不影响 OTA 主流程。
 */
@OptIn(ExperimentalTime::class)
class FirmwareLibraryRepository(
    private val store: FirmwareLibraryStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    init {
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
    }

    /** 按指定排序返回全部记录；版本排序使用「引入时间」作为同版本时的稳定次序。 */
    fun list(sort: FirmwareLibrarySort = FirmwareLibrarySort.RECENT): List<FirmwareLibraryRecord> {
        val records = all()
        return when (sort) {
            FirmwareLibrarySort.RECENT ->
                records.sortedWith(
                    compareByDescending<FirmwareLibraryRecord> { it.lastUsedAtEpochMillis ?: it.addedAtEpochMillis }
                        .thenByDescending { it.addedAtEpochMillis }
                        .thenBy { it.id },
                )

            FirmwareLibrarySort.VERSION ->
                records.sortedWith(
                    compareByDescending<FirmwareLibraryRecord> { versionKey(it.version) }
                        .thenByDescending { it.addedAtEpochMillis }
                        .thenBy { it.id },
                )
        }
    }

    fun get(id: String): FirmwareLibraryRecord? = all().firstOrNull { it.id == id }

    /** 新增或按身份归并一条记录；重复条目会刷新路径与元信息，但保留常用标记。 */
    fun record(record: FirmwareLibraryRecord): FirmwareLibraryRecord {
        val existing = all().firstOrNull { it.id == record.id }
        val merged = if (existing == null) {
            record
        } else {
            record.copy(
                addedAtEpochMillis = existing.addedAtEpochMillis,
                favorite = existing.favorite || record.favorite,
                lastUsedAtEpochMillis = record.lastUsedAtEpochMillis ?: existing.lastUsedAtEpochMillis,
                lastDeviceId = record.lastDeviceId ?: existing.lastDeviceId,
                lastOutcome = record.lastOutcome ?: existing.lastOutcome,
            )
        }
        replace(merged)
        persist()
        return merged
    }

    fun delete(id: String): Boolean {
        val removed = all().removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun setFavorite(id: String, favorite: Boolean): Boolean {
        val records = all()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return false
        records[index] = records[index].copy(favorite = favorite)
        persist()
        return true
    }

    /** 记录一次实际使用结果（成功/失败/中止），并刷新最近使用时间。 */
    fun markUsed(id: String, deviceId: String, outcome: FirmwareLibraryOutcome): Boolean {
        val records = all()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return false
        records[index] = records[index].copy(
            lastUsedAtEpochMillis = clockMillis(),
            lastDeviceId = deviceId,
            lastOutcome = outcome,
        )
        persist()
        return true
    }

    fun count(): Int = all().size

    fun clear() {
        cache = mutableListOf()
        runCatching { store.clear() }
    }

    /**
     * 校验一条历史记录是否仍可直接使用。
     *
     * [isAccessible] 由平台层提供（Android SAF / iOS 文件 URL）；[computeCrc32] 在可访问时
     * 重新流式计算 payload CRC-32。只有两者都通过才返回 [FirmwareReuseCheck.Available]，
     * 任何异常都按「不可用」处理，绝不在校验失败时假装文件可用。
     */
    suspend fun checkReuse(
        id: String,
        isAccessible: suspend (String) -> Boolean,
        computeCrc32: suspend (String) -> Long?,
    ): FirmwareReuseCheck {
        val record = get(id) ?: return FirmwareReuseCheck.NotFound
        val accessible = try {
            isAccessible(record.sourcePath)
        } catch (_: Throwable) {
            false
        }
        if (!accessible) {
            return FirmwareReuseCheck.Missing(record, FirmwareReuseReason.ACCESS_DENIED)
        }
        val actual = try {
            computeCrc32(record.sourcePath)
        } catch (_: Throwable) {
            null
        }
        if (actual == null) {
            return FirmwareReuseCheck.Missing(record, FirmwareReuseReason.READ_FAILED)
        }
        // 重新计算的 CRC 与文件头声明比对：文件头 CRC 是固件自身的完整性标识，
        // 若记录里的 CRC 与实际不符，说明文件已被替换或记录损坏。
        if (actual != record.crc32) {
            return FirmwareReuseCheck.CrcMismatch(record, record.crc32, actual)
        }
        return FirmwareReuseCheck.Available(record)
    }

    private var cache: MutableList<FirmwareLibraryRecord>? = null

    private fun all(): MutableList<FirmwareLibraryRecord> =
        cache ?: FirmwareLibraryCodec.decode(store.loadRaw()).toMutableList().also { cache = it }

    private fun replace(record: FirmwareLibraryRecord) {
        val records = all()
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records += record
    }

    private fun persist() {
        val trimmed = trim(all())
        cache = trimmed.toMutableList()
        runCatching { store.saveRaw(FirmwareLibraryCodec.encode(trimmed)) }
    }

    /** 淘汰顺序：最久未用的非常用 → 最久未用的常用 → 最早引入。 */
    private fun trim(records: List<FirmwareLibraryRecord>): List<FirmwareLibraryRecord> {
        if (records.size <= maxEntries) return records
        val ordered = records.sortedWith(
            compareByDescending<FirmwareLibraryRecord> { it.favorite }
                .thenByDescending { it.lastUsedAtEpochMillis ?: it.addedAtEpochMillis }
                .thenByDescending { it.addedAtEpochMillis }
                .thenBy { it.id },
        )
        return ordered.take(maxEntries)
    }

    /** 版本字符串排序键：尽量解析出数字段，解析失败时退回字符串比较。 */
    private fun versionKey(version: String): String = version

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20
    }
}
