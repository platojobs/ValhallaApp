package com.valhalla.core.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareLibraryTest {

    private fun record(
        version: String = "1.0.0",
        crc32: Long = 0x1234,
        totalBytes: Long = 1024,
        addedAt: Long = 1_000,
        path: String = "/tmp/fw.bin",
        favorite: Boolean = false,
        fileName: String = "fw-$version.bin",
    ): FirmwareLibraryRecord = FirmwareLibraryRecord(
        fileName = fileName,
        version = version,
        channel = "release",
        modelWhitelist = listOf("V-1"),
        totalBytes = totalBytes,
        crc32 = crc32,
        addedAtEpochMillis = addedAt,
        sourcePath = path,
        favorite = favorite,
    )

    @Test
    fun `记录按身份归并且保留常用标记`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val first = repo.record(record(path = "content://a"))
        repo.setFavorite(first.id, true)
        val second = repo.record(record(path = "content://b"))

        assertEquals(1, repo.count())
        assertEquals("content://b", repo.get(first.id)!!.sourcePath, "重复选择应刷新路径")
        assertTrue(repo.get(first.id)!!.favorite, "归并不应丢失常用标记")
        assertEquals(1_000, repo.get(first.id)!!.addedAtEpochMillis, "归并保留首次引入时间")
        assertEquals(second.id, first.id)
    }

    @Test
    fun `按最近使用与版本排序`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val older = repo.record(record(version = "1.0.0", crc32 = 1, addedAt = 100))
        val newer = repo.record(record(version = "1.2.0", crc32 = 2, addedAt = 200))
        repo.markUsed(older.id, "dev-1", FirmwareLibraryOutcome.SUCCEEDED)

        assertEquals(listOf(older.id, newer.id), repo.list(FirmwareLibrarySort.RECENT).map { it.id })
        assertEquals(listOf(newer.id, older.id), repo.list(FirmwareLibrarySort.VERSION).map { it.id })
    }

    @Test
    fun `超过上限时淘汰最久未用的非常用记录`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore(), maxEntries = 3)
        val first = repo.record(record(crc32 = 1, addedAt = 100))
        repo.setFavorite(first.id, true)
        repo.record(record(crc32 = 2, addedAt = 200))
        repo.record(record(crc32 = 3, addedAt = 300))
        repo.record(record(crc32 = 4, addedAt = 400))

        assertEquals(3, repo.count())
        assertTrue(repo.get(first.id) != null, "常用记录应优先保留")
        assertNull(repo.get(identityOf(crc32 = 2, addedAt = 200)), "最久未用的非常用记录应被淘汰")
    }

    @Test
    fun `损坏数据回退为空`() {
        val store = InMemoryFirmwareLibraryStore("not-a-valid-format")
        val repo = FirmwareLibraryRepository(store)
        assertEquals(0, repo.count())
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `编码可往返且不包含二进制本体`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val record = repo.record(record(fileName = "包 含 空格.bin", path = "content://x/y"))
        val raw = InMemoryFirmwareLibraryStore().let { store ->
            store.saveRaw(FirmwareLibraryCodec.encode(repo.list()))
            store.loadRaw()!!
        }
        val decoded = FirmwareLibraryCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(record.fileName, decoded.first().fileName)
        assertEquals(record.sourcePath, decoded.first().sourcePath)
        assertFalse(raw.contains("payload"), "不应缓存固件二进制")
    }

    @Test
    fun `删除与常用切换`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rec = repo.record(record())
        assertTrue(repo.setFavorite(rec.id, true))
        assertTrue(repo.get(rec.id)!!.favorite)
        assertTrue(repo.delete(rec.id))
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `可访问且 CRC 一致时允许复用`() = kotlinx.coroutines.test.runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rec = repo.record(record())
        val check = repo.checkReuse(
            id = rec.id,
            isAccessible = { true },
            computeCrc32 = { rec.crc32 },
        )
        assertTrue(check is FirmwareReuseCheck.Available)
        assertEquals(rec.id, (check as FirmwareReuseCheck.Available).record.id)
    }

    @Test
    fun `文件不可访问时提示重新选择`() = kotlinx.coroutines.test.runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rec = repo.record(record())
        val check = repo.checkReuse(
            id = rec.id,
            isAccessible = { false },
            computeCrc32 = { rec.crc32 },
        )
        assertTrue(check is FirmwareReuseCheck.Missing)
        assertEquals(
            FirmwareReuseReason.ACCESS_DENIED,
            (check as FirmwareReuseCheck.Missing).reason,
        )
    }

    @Test
    fun `CRC 不一致时不假装可用`() = kotlinx.coroutines.test.runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rec = repo.record(record())
        val check = repo.checkReuse(
            id = rec.id,
            isAccessible = { true },
            computeCrc32 = { 0x9999 },
        )
        assertTrue(check is FirmwareReuseCheck.CrcMismatch)
        assertEquals(rec.crc32, (check as FirmwareReuseCheck.CrcMismatch).expectedCrc32)
        assertEquals(0x9999, check.actualCrc32)
    }

    @Test
    fun `探测抛出异常按不可用处理`() = kotlinx.coroutines.test.runTest {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rec = repo.record(record())
        val inaccessible = repo.checkReuse(
            id = rec.id,
            isAccessible = { error("boom") },
            computeCrc32 = { rec.crc32 },
        )
        assertTrue(inaccessible is FirmwareReuseCheck.Missing)

        val unreadable = repo.checkReuse(
            id = rec.id,
            isAccessible = { true },
            computeCrc32 = { error("io") },
        )
        assertTrue(unreadable is FirmwareReuseCheck.Missing)
    }

    private fun identityOf(crc32: Long, addedAt: Long): String =
        record(crc32 = crc32, addedAt = addedAt).id
}
