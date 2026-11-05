package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareLibraryManagementTest {

    private fun record(
        version: String = "1.0.0 (build 0, release)",
        model: List<String> = listOf("V-1"),
        crc32: Long = 0x1234,
        addedAt: Long = 1_000,
    ) = FirmwareLibraryRecord(
        fileName = "fw-$crc32.bin",
        version = version,
        channel = "release",
        modelWhitelist = model,
        totalBytes = 1024,
        crc32 = crc32,
        addedAtEpochMillis = addedAt,
        sourcePath = "content://fw/$crc32",
    )

    private fun candidate(
        version: String = "1.0.0 (build 0, release)",
        crc32: Long = 0x1234,
        totalBytes: Long = 1024,
    ) = FirmwareLibraryImportCandidate(
        fileName = "fw.bin",
        version = version,
        channel = "release",
        modelWhitelist = listOf("V-1"),
        totalBytes = totalBytes,
        crc32 = crc32,
        sourcePath = "content://fw/imported",
    )

    @Test
    fun `同一内容重复导入不新增条目`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val first = repo.import(candidate())
        assertTrue(first is FirmwareLibraryImportResult.Imported)

        val second = repo.import(candidate())
        assertTrue(second is FirmwareLibraryImportResult.Duplicate)
        assertEquals(1, repo.count())
        assertEquals(first.recordId(), (second as FirmwareLibraryImportResult.Duplicate).existing.id)
    }

    @Test
    fun `别名重命名可持久化且空别名恢复文件名`() {
        val store = InMemoryFirmwareLibraryStore()
        val repo = FirmwareLibraryRepository(store)
        val imported = repo.import(candidate()) as FirmwareLibraryImportResult.Imported

        assertTrue(repo.rename(imported.record.id, "产线测试包"))
        assertEquals("产线测试包", repo.get(imported.record.id)!!.displayName)
        val reloaded = FirmwareLibraryRepository(store)
        assertEquals("产线测试包", reloaded.get(imported.record.id)!!.displayName)

        assertTrue(reloaded.rename(imported.record.id, "  "))
        assertEquals("fw.bin", reloaded.get(imported.record.id)!!.displayName)
    }

    @Test
    fun `按型号和版本关键字筛选`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        repo.record(record(version = "1.0.0 (build 0, release)", model = listOf("V-1"), crc32 = 1))
        repo.record(record(version = "2.0.0 (build 0, release)", model = listOf("V-2"), crc32 = 2))

        assertEquals(1, repo.list(FirmwareLibraryFilter(model = "V-1")).size)
        assertEquals(1, repo.list(FirmwareLibraryFilter(versionQuery = "2.0")).size)
        assertTrue(repo.list(FirmwareLibraryFilter(model = "V-3")).isEmpty())
    }

    @Test
    fun `旧 v1 格式仍可读取且别名为空`() {
        val legacy = "v1\nF|1234|fw-old.bin|1.0.0|release|V-1|1024|1234|1000|||SUCCEEDED|0|content://old\n"
        val decoded = FirmwareLibraryCodec.decode(legacy)

        assertEquals(1, decoded.size)
        assertNull(decoded.first().alias)
        assertEquals("fw-old.bin", decoded.first().fileName)
        assertEquals("content://old", decoded.first().sourcePath)
    }

    @Test
    fun `适用性复用既有型号与版本规则`() {
        val current = FirmwareVersion(major = 1, minor = 0, patch = 0, build = 0, channel = "release")

        val applicable = FirmwareApplicabilityEvaluator.evaluate(
            record(version = "2.0.0 (build 0, release)"),
            currentModel = "V-1",
            currentVersion = current,
        )
        assertTrue(applicable.applicable)

        val rollback = FirmwareApplicabilityEvaluator.evaluate(
            record(version = "0.9.0 (build 0, release)"),
            currentModel = "V-1",
            currentVersion = current,
        )
        assertEquals(FirmwareApplicabilityReason.VERSION_ROLLBACK, rollback.reason)

        val wrongModel = FirmwareApplicabilityEvaluator.evaluate(
            record(version = "2.0.0 (build 0, release)"),
            currentModel = "X-9",
            currentVersion = current,
        )
        assertEquals(FirmwareApplicabilityReason.MODEL_MISMATCH, wrongModel.reason)

        val unknownVersionFormat = FirmwareApplicabilityEvaluator.evaluate(
            record(version = "nightly"),
            currentModel = "V-1",
            currentVersion = current,
        )
        assertEquals(FirmwareApplicabilityReason.VERSION_UNPARSEABLE, unknownVersionFormat.reason)
    }

    @Test
    fun `一键选中最新的适用包`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        repo.record(record(version = "1.5.0 (build 0, release)", crc32 = 1, addedAt = 100))
        val newest = repo.record(record(version = "2.0.0 (build 0, release)", crc32 = 2, addedAt = 200))
        repo.record(record(version = "3.0.0 (build 0, release)", model = listOf("OTHER"), crc32 = 3, addedAt = 300))

        val current = FirmwareVersion(major = 1, minor = 0, patch = 0, build = 0, channel = "release")
        val selected = repo.latestApplicable("V-1", current)
        assertNotNull(selected)
        assertEquals(newest.id, selected.id)
    }

    @Test
    fun `损坏包元数据被拒绝且不入库`() {
        val repo = FirmwareLibraryRepository(InMemoryFirmwareLibraryStore())
        val rejected = repo.import(candidate(totalBytes = -1))

        assertTrue(rejected is FirmwareLibraryImportResult.Rejected)
        assertFalse((rejected as FirmwareLibraryImportResult.Rejected).code.isBlank())
        assertEquals(0, repo.count())
    }

    private fun FirmwareLibraryImportResult.recordId(): String =
        (this as FirmwareLibraryImportResult.Imported).record.id
}
