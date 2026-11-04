package com.valhalla.data.security

import com.valhalla.core.security.TokenStore

/**
 * 创建当前平台的会话令牌安全存储。
 *
 * - Android：Android Keystore（AES/GCM）加密后写入 SharedPreferences；
 * - iOS：Keychain（`kSecClassGenericPassword`）；
 * - JVM：内存实现（仅用于测试与桌面调试，进程退出即丢失）。
 */
expect fun createTokenStore(): TokenStore
