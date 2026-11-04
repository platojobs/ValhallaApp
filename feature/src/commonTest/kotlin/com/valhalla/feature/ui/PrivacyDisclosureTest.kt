package com.valhalla.feature.ui

import com.valhalla.data.settings.StorageItem
import com.valhalla.data.settings.StoragePlatform
import com.valhalla.feature.i18n.AppStrings
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivacyDisclosureTest {

    @Test
    fun `隐私页为每种存储类别提供说明`() {
        val keys = privacyKeysForAllItems()

        assertEquals(StorageItem.entries.toSet(), keys.keys)
        keys.values.forEach { key ->
            assertNotNull(AppStrings.entry(key), "缺少文案：$key")
        }
    }

    @Test
    fun `隐私页展示的存储项与清单一致`() {
        StoragePlatform.entries.forEach { platform ->
            val items = storageLocationItems(platform).map { it.item }.toSet()
            assertEquals(StorageItem.entries.toSet(), items, "缺少存储项：$platform")
        }
    }

    @Test
    fun `声明本版本不会上传数据`() {
        val zh = privacyNetworkDisclosure(ResolvedLanguage.CHINESE)
        val en = privacyNetworkDisclosure(ResolvedLanguage.ENGLISH)

        assertTrue(zh.contains("没有网络上传"), zh)
        assertFalse(zh.contains("上传到服务器"), "不得声称已实现上传：$zh")
        assertTrue(en.contains("no network upload"), en)
    }

    @Test
    fun `令牌与日志的说明与实际行为一致`() {
        val token = AppStrings.get(ResolvedLanguage.CHINESE, StringKey.PRIVACY_ITEM_TOKEN)
        assertTrue(token.contains("Keystore"))
        assertTrue(token.contains("Keychain"))

        val logs = AppStrings.get(ResolvedLanguage.CHINESE, StringKey.PRIVACY_ITEM_LOGS)
        assertTrue(logs.contains("内存"))
        assertTrue(logs.contains("不落盘"))
    }

    @Test
    fun `关于页版本来源与统一版本一致`() {
        // 关于页直接读取生成的 AppBuildInfo，此处断言生成值与版本常量非空且为语义化版本。
        val version = com.valhalla.core.AppBuildInfo.VERSION_NAME
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(version), "版本号格式异常：$version")
        assertTrue(com.valhalla.core.AppBuildInfo.VERSION_CODE > 0)
        assertTrue(com.valhalla.core.AppBuildInfo.APP_NAME.isNotBlank())
    }

    @Test
    fun `帮助页覆盖四类常见问题`() {
        listOf(
            StringKey.HELP_Q_PERMISSION,
            StringKey.HELP_A_PERMISSION,
            StringKey.HELP_Q_CONNECT,
            StringKey.HELP_A_CONNECT,
            StringKey.HELP_Q_OTA,
            StringKey.HELP_A_OTA,
            StringKey.HELP_Q_LOGS,
            StringKey.HELP_A_LOGS,
        ).forEach { key ->
            assertNotNull(AppStrings.entry(key), "缺少帮助条目：$key")
        }
    }
}
