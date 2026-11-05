package com.valhalla.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageInventoryTest {

    @Test
    fun `每个平台都覆盖全部存储类别`() {
        StoragePlatform.entries.forEach { platform ->
            val covered = StorageInventory.forPlatform(platform).map { it.item }.toSet()
            assertEquals(StorageItem.entries.toSet(), covered, "缺少类别：$platform")
        }
    }

    @Test
    fun `同一平台内键名非空且不重复`() {
        StoragePlatform.entries.forEach { platform ->
            val keys = StorageInventory.forPlatform(platform).flatMap { it.keys }
            assertTrue(keys.all { it.isNotBlank() }, "存在空键：$platform")
            assertEquals(keys.size, keys.toSet().size, "存在重复键：$platform")
        }
    }

    @Test
    fun `偏好键与偏好存储常量一致`() {
        val android = StorageInventory.forPlatform(StoragePlatform.ANDROID)
            .first { it.item == StorageItem.APP_SETTINGS }
        assertEquals(SettingsKeys.ALL, android.keys)

        val ios = StorageInventory.forPlatform(StoragePlatform.IOS)
            .first { it.item == StorageItem.APP_SETTINGS }
        assertEquals(
            SettingsKeys.ALL.map { StorageKeys.IOS_SETTINGS_PREFIX + it },
            ios.keys,
        )
        assertEquals(20, SettingsKeys.ALL.size)
    }

    @Test
    fun `令牌条目声明了真实的加密存储键`() {
        val androidToken = StorageInventory.forPlatform(StoragePlatform.ANDROID)
            .first { it.item == StorageItem.AUTH_TOKEN }
        assertTrue(androidToken.keys.contains(StorageKeys.ANDROID_TOKEN_KEY))
        assertTrue(androidToken.keys.contains(StorageKeys.ANDROID_TOKEN_KEYSTORE_ALIAS))
        assertTrue(androidToken.persistent)

        val iosToken = StorageInventory.forPlatform(StoragePlatform.IOS)
            .first { it.item == StorageItem.AUTH_TOKEN }
        assertTrue(iosToken.keys.contains(StorageKeys.IOS_KEYCHAIN_SERVICE))
        assertTrue(iosToken.keys.contains(StorageKeys.IOS_KEYCHAIN_ACCOUNT))
        assertTrue(iosToken.persistent)
    }

    @Test
    fun `日志在所有平台都只存在于内存`() {
        StoragePlatform.entries.forEach { platform ->
            val log = StorageInventory.forPlatform(platform)
                .first { it.item == StorageItem.PACKET_LOG }
            assertFalse(log.persistent, "日志不应持久化：$platform")
            assertTrue(log.keys.isEmpty(), "日志不应有存储键：$platform")
        }
    }

    @Test
    fun `JVM 宿主全部为内存实现`() {
        StorageInventory.forPlatform(StoragePlatform.JVM).forEach { location ->
            assertFalse(location.persistent)
            assertTrue(location.keys.isEmpty())
        }
    }
}
