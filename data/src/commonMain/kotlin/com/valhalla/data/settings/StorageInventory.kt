package com.valhalla.data.settings

/** 应用会写入本地存储的数据类别。 */
enum class StorageItem {
    /** 设备档案：最近连接时间、型号、固件、电量、OTA 记录。 */
    DEVICE_PROFILES,

    /** OTA 断点：支持续传的偏移、会话与固件标识。 */
    OTA_CHECKPOINT,

    /** 会话令牌：经 Android Keystore / iOS Keychain 保护。 */
    AUTH_TOKEN,

    /** 报文日志：仅内存环形缓冲，不落盘。 */
    PACKET_LOG,

    /** 语言、主题偏好。 */
    APP_SETTINGS,

    /** 是否已请求过蓝牙权限（只存一个布尔标记）。 */
    BLE_PERMISSION_FLAG,
}

enum class StoragePlatform {
    ANDROID,
    IOS,
    JVM,
}

/**
 * 一条真实的本地存储位置描述。
 *
 * [keys] 是代码里实际使用的键名，隐私页直接展示这些字符串，测试会校验它们与
 * 各平台实现中的常量一致，避免「文档写一套、代码存另一套」。
 */
data class StorageLocation(
    val item: StorageItem,
    val platform: StoragePlatform,
    val location: String,
    val keys: List<String>,
    /** 是否跨进程持久化；false 表示仅存在于当前进程内存。 */
    val persistent: Boolean,
)

object StorageKeys {
    // Android 设备档案
    const val ANDROID_PROFILES_PREFS = "valhalla_profiles"
    const val ANDROID_PROFILES_KEY = "profiles"

    // iOS 设备档案（NSUserDefaults）
    const val IOS_PROFILES_KEY = "valhalla.profiles"

    // Android OTA 断点
    const val ANDROID_OTA_PREFS = "valhalla_ota"
    const val ANDROID_OTA_KEY = "resume_point"

    // iOS OTA 断点
    const val IOS_OTA_KEY = "valhalla.ota.resume_point"

    // Android 令牌（密文）与 Keystore 别名
    const val ANDROID_TOKEN_PREFS = "valhalla_secure_auth"
    const val ANDROID_TOKEN_KEY = "auth_token"
    const val ANDROID_TOKEN_KEYSTORE_ALIAS = "valhalla.auth.token"

    // iOS 令牌（Keychain）
    const val IOS_KEYCHAIN_SERVICE = "com.valhalla.auth.token"
    const val IOS_KEYCHAIN_ACCOUNT = "session"

    // 偏好
    const val ANDROID_SETTINGS_PREFS = "valhalla_settings"
    const val IOS_SETTINGS_PREFIX = "valhalla.settings."
    const val SETTING_LANGUAGE = SettingsKeys.LANGUAGE
    const val SETTING_THEME = SettingsKeys.THEME

    // 蓝牙权限标记
    const val ANDROID_BLE_PERMISSION_PREFS = "valhalla_ble_permission"
    const val ANDROID_BLE_PERMISSION_KEY = "requested"
}

/** 各平台真实的存储位置清单。 */
object StorageInventory {

    val all: List<StorageLocation> = listOf(
        StorageLocation(
            item = StorageItem.DEVICE_PROFILES,
            platform = StoragePlatform.ANDROID,
            location = "SharedPreferences(${StorageKeys.ANDROID_PROFILES_PREFS})",
            keys = listOf(StorageKeys.ANDROID_PROFILES_KEY),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.DEVICE_PROFILES,
            platform = StoragePlatform.IOS,
            location = "NSUserDefaults",
            keys = listOf(StorageKeys.IOS_PROFILES_KEY),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.DEVICE_PROFILES,
            platform = StoragePlatform.JVM,
            location = "In-memory (InMemoryDeviceProfileStore)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.OTA_CHECKPOINT,
            platform = StoragePlatform.ANDROID,
            location = "SharedPreferences(${StorageKeys.ANDROID_OTA_PREFS})",
            keys = listOf(StorageKeys.ANDROID_OTA_KEY),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.OTA_CHECKPOINT,
            platform = StoragePlatform.IOS,
            location = "NSUserDefaults",
            keys = listOf(StorageKeys.IOS_OTA_KEY),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.OTA_CHECKPOINT,
            platform = StoragePlatform.JVM,
            location = "In-memory (InMemoryOtaCheckpointStore)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.AUTH_TOKEN,
            platform = StoragePlatform.ANDROID,
            location = "Android Keystore-encrypted SharedPreferences(${StorageKeys.ANDROID_TOKEN_PREFS})",
            keys = listOf(
                StorageKeys.ANDROID_TOKEN_KEY,
                StorageKeys.ANDROID_TOKEN_KEYSTORE_ALIAS,
            ),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.AUTH_TOKEN,
            platform = StoragePlatform.IOS,
            location = "Keychain",
            keys = listOf(
                StorageKeys.IOS_KEYCHAIN_SERVICE,
                StorageKeys.IOS_KEYCHAIN_ACCOUNT,
            ),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.AUTH_TOKEN,
            platform = StoragePlatform.JVM,
            location = "In-memory (InMemoryTokenStore)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.PACKET_LOG,
            platform = StoragePlatform.ANDROID,
            location = "In-memory ring buffer (InMemoryPacketLog)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.PACKET_LOG,
            platform = StoragePlatform.IOS,
            location = "In-memory ring buffer (InMemoryPacketLog)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.PACKET_LOG,
            platform = StoragePlatform.JVM,
            location = "In-memory ring buffer (InMemoryPacketLog)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.APP_SETTINGS,
            platform = StoragePlatform.ANDROID,
            location = "SharedPreferences(${StorageKeys.ANDROID_SETTINGS_PREFS})",
            keys = listOf(StorageKeys.SETTING_LANGUAGE, StorageKeys.SETTING_THEME),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.APP_SETTINGS,
            platform = StoragePlatform.IOS,
            location = "NSUserDefaults",
            keys = listOf(
                StorageKeys.IOS_SETTINGS_PREFIX + StorageKeys.SETTING_LANGUAGE,
                StorageKeys.IOS_SETTINGS_PREFIX + StorageKeys.SETTING_THEME,
            ),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.APP_SETTINGS,
            platform = StoragePlatform.JVM,
            location = "In-memory (InMemoryAppSettingsStore)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.BLE_PERMISSION_FLAG,
            platform = StoragePlatform.ANDROID,
            location = "SharedPreferences(${StorageKeys.ANDROID_BLE_PERMISSION_PREFS})",
            keys = listOf(StorageKeys.ANDROID_BLE_PERMISSION_KEY),
            persistent = true,
        ),
        StorageLocation(
            item = StorageItem.BLE_PERMISSION_FLAG,
            platform = StoragePlatform.IOS,
            location = "Not stored (the system reports authorization state)",
            keys = emptyList(),
            persistent = false,
        ),
        StorageLocation(
            item = StorageItem.BLE_PERMISSION_FLAG,
            platform = StoragePlatform.JVM,
            location = "Not stored",
            keys = emptyList(),
            persistent = false,
        ),
    )

    fun forPlatform(platform: StoragePlatform): List<StorageLocation> =
        all.filter { it.platform == platform }
}

/** 当前运行平台，用于筛选要展示的存储位置。 */
expect fun currentStoragePlatform(): StoragePlatform
