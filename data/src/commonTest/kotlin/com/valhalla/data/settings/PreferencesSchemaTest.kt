package com.valhalla.data.settings

import com.valhalla.core.storage.StorageMigrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** 偏好存储 schema 迁移：用旧版本 key-value 字符串作为输入。 */
class PreferencesSchemaTest {
    private class FakeStore(
        initial: Map<String, String> = emptyMap(),
    ) : AppSettingsStore {
        private val values = initial.toMutableMap()

        override fun read(key: String): String? = values[key]

        override fun write(
            key: String,
            value: String?,
        ) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun `v1 偏好真实迁移到 v2`() {
        val store =
            FakeStore(
                mapOf(
                    SettingsKeys.LANGUAGE to "zh-Hans",
                    SettingsKeys.THEME to "dark",
                ),
            )

        val status = PreferencesSchema.migrate(store)

        assertEquals(StorageMigrationStatus.Migrated(1, 2), status)
        assertEquals("CHINESE", store.read(SettingsKeys.LANGUAGE))
        assertEquals("DARK", store.read(SettingsKeys.THEME))
        assertEquals("2", store.read(SettingsKeys.SCHEMA_VERSION))
        // 再次迁移不会重复改写。
        assertEquals(StorageMigrationStatus.Current, PreferencesSchema.migrate(store))
    }

    @Test
    fun `未来版本不改写数据并返回 FutureVersion`() {
        val store =
            FakeStore(
                mapOf(
                    SettingsKeys.SCHEMA_VERSION to "3",
                    SettingsKeys.LANGUAGE to "ENGLISH",
                ),
            )

        val status = PreferencesSchema.migrate(store)

        assertEquals(StorageMigrationStatus.FutureVersion(3, 2), status)
        assertEquals("3", store.read(SettingsKeys.SCHEMA_VERSION))
        assertEquals("ENGLISH", store.read(SettingsKeys.LANGUAGE))
    }

    @Test
    fun `版本字段损坏返回 Corrupted 且不清空数据`() {
        val store =
            FakeStore(
                mapOf(
                    SettingsKeys.SCHEMA_VERSION to "v2-beta",
                    SettingsKeys.LANGUAGE to "chinese",
                ),
            )

        val status = PreferencesSchema.migrate(store)

        assertEquals(StorageMigrationStatus.Corrupted("storage_schema_version 不是整数: v2-beta"), status)
        assertEquals("v2-beta", store.read(SettingsKeys.SCHEMA_VERSION))
        assertEquals("chinese", store.read(SettingsKeys.LANGUAGE))
    }
}
