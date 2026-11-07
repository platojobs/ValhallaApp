@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package com.valhalla.data.security

import com.valhalla.core.security.AuthToken
import com.valhalla.core.security.AuthTokenCodec
import com.valhalla.core.security.TokenStore
import com.valhalla.data.settings.StorageKeys
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS 安全存储：Keychain 通用密码（一个 service/account 组合只存一条会话令牌）。
 *
 * 读写失败按「无令牌」处理；读取到损坏数据时顺手删除，避免反复失败。
 * 注意：此处只验证了编译与模拟器构建，真机 Keychain 行为需在设备上复核。
 */
actual fun createTokenStore(): TokenStore = KeychainTokenStore()

private class KeychainTokenStore : TokenStore {
    override fun save(token: AuthToken) {
        val payload = AuthTokenCodec.encode(token).encodeToByteArray()
        val query = baseQuery()
        try {
            // 先删旧值，再插入新值，保持「一条会话令牌」的语义。
            SecItemDelete(query.dict)
            query.addGlobal(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            memScoped {
                val data =
                    NSData.create(
                        bytes = allocArrayOf(payload),
                        length = payload.size.toULong(),
                    )
                val retainedData = CFBridgingRetain(data)
                query.addRetained(kSecValueData, retainedData)
                SecItemAdd(query.dict, null)
            }
        } finally {
            query.close()
        }
    }

    override fun load(): AuthToken? {
        val query = baseQuery()
        try {
            query.addGlobal(kSecReturnData, kCFBooleanTrue)
            query.addGlobal(kSecMatchLimit, kSecMatchLimitOne)
            return memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query.dict, result.ptr)
                if (status != errSecSuccess) {
                    if (status != errSecItemNotFound) clear()
                    return@memScoped null
                }
                val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
                val length = data.length.toInt()
                if (length <= 0) return@memScoped null
                val bytes = data.bytes ?: return@memScoped null
                AuthTokenCodec.decode(bytes.reinterpret<ByteVar>().readBytes(length).decodeToString())
            }
        } finally {
            query.close()
        }
    }

    override fun clear() {
        val query = baseQuery()
        try {
            SecItemDelete(query.dict)
        } finally {
            query.close()
        }
    }

    private fun baseQuery(): KeychainQuery {
        val dictionary =
            CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
                ?: error("无法创建 Keychain 查询字典")
        val retained = mutableListOf<CFTypeRef?>()
        val service = CFBridgingRetain(StorageKeys.IOS_KEYCHAIN_SERVICE)
        val account = CFBridgingRetain(StorageKeys.IOS_KEYCHAIN_ACCOUNT)
        retained += service
        retained += account
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, service)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, account)
        return KeychainQuery(dictionary, retained)
    }
}

/**
 * Keychain 查询字典及其持有者。
 *
 * 字典用 null callbacks 创建，不负责 retain；所有由 [CFBridgingRetain] 产生的对象
 * 都记录在 [retained] 中，在 [close] 时统一释放，避免泄漏。
 */
private class KeychainQuery(
    val dict: CFMutableDictionaryRef,
    val retained: MutableList<CFTypeRef?>,
) {
    fun addGlobal(
        key: CFStringRef?,
        value: CFTypeRef?,
    ) {
        CFDictionaryAddValue(dict, key, value)
    }

    fun addRetained(
        key: CFStringRef?,
        value: CFTypeRef?,
    ) {
        retained += value
        CFDictionaryAddValue(dict, key, value)
    }

    fun close() {
        retained.forEach { if (it != null) CFRelease(it) }
        retained.clear()
        CFRelease(dict)
    }
}
