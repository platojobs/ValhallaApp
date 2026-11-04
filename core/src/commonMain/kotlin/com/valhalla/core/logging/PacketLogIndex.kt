package com.valhalla.core.logging

/**
 * 报文日志的只读索引，供大数据量下的搜索/过滤复用。
 *
 * 每次日志快照变化时构建一次（UI 用 `remember(entries)`），把每条记录的可搜索字段
 * 预先拼成小写字符串；输入关键字时只做一次 `contains`，不再逐条拼接列表。
 * 排序与 [filterPacketEntries] 完全一致，保证切换实现后结果不变。
 */
class PacketLogIndex(entries: List<PacketLogEntry>) {

    private data class Indexed(val entry: PacketLogEntry, val haystack: String)

    private val indexed: List<Indexed> = entries
        .sortedWith(compareByDescending<PacketLogEntry> { it.timestampMillis }.thenByDescending { it.id })
        .map { entry ->
            Indexed(
                entry = entry,
                haystack = buildString {
                    append(entry.command.name)
                    append('\n')
                    append(entry.summary)
                    append('\n')
                    append(entry.hex)
                    append('\n')
                    append(entry.sequence)
                    append('\n')
                    append(entry.error.orEmpty())
                }.lowercase(),
            )
        }

    val size: Int get() = indexed.size

    fun query(filter: PacketLogFilter, limit: Int = Int.MAX_VALUE): List<PacketLogEntry> {
        if (limit <= 0) return emptyList()
        val keyword = filter.keyword.trim().lowercase()
        val result = ArrayList<PacketLogEntry>(minOf(limit, indexed.size))
        for (item in indexed) {
            if (result.size >= limit) break
            if (filter.command != null && item.entry.command != filter.command) continue
            if (filter.errorsOnly && item.entry.error == null) continue
            if (keyword.isNotEmpty() && !item.haystack.contains(keyword)) continue
            result += item.entry
        }
        return result
    }
}
