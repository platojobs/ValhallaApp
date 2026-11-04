package com.valhalla.data.security

import com.valhalla.core.security.InMemoryTokenStore
import com.valhalla.core.security.TokenStore

/** JVM 无平台安全存储，使用内存实现；仅用于测试与桌面调试。 */
actual fun createTokenStore(): TokenStore = InMemoryTokenStore()
