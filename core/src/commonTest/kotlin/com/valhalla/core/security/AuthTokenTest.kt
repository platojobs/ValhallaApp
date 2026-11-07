package com.valhalla.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTokenTest {
    @Test
    fun `带过期时间的令牌按时间判断`() {
        val token = AuthToken("abc", expiresAtEpochMillis = 1_000)
        assertFalse(token.isExpired(999))
        assertTrue(token.isExpired(1_000))
        assertTrue(token.isExpired(1_001))
        assertTrue(token.hasExpiry)
    }

    @Test
    fun `有效期为 0 表示未声明且不因时间过期`() {
        val token = AuthToken("abc", expiresAtEpochMillis = 0)
        assertFalse(token.hasExpiry)
        assertFalse(token.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `编解码往返保持值与过期时间`() {
        val token = AuthToken("token-with-special-chars", 123_456L)
        assertEquals(token, AuthTokenCodec.decode(AuthTokenCodec.encode(token)))
    }

    @Test
    fun `损坏编码按无令牌处理`() {
        assertNull(AuthTokenCodec.decode(null))
        assertNull(AuthTokenCodec.decode(""))
        assertNull(AuthTokenCodec.decode("no-separator"))
        assertNull(AuthTokenCodec.decode("\u0001not-a-number"))
        assertNull(AuthTokenCodec.decode("value\u0001-1"))
    }

    @Test
    fun `内存存储支持写入读取与清除`() {
        val store = InMemoryTokenStore()
        assertNull(store.load())

        val token = AuthToken("token", 10_000)
        store.save(token)
        assertEquals(token, store.load())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `读取有效令牌时过期数据被清除`() {
        val store = InMemoryTokenStore()
        store.save(AuthToken("expired", 1_000))

        assertNull(store.loadValid(2_000))
        assertNull(store.load())
    }

    @Test
    fun `过期前仍可读取有效令牌`() {
        val store = InMemoryTokenStore()
        store.save(AuthToken("valid", 5_000))
        assertEquals("valid", store.loadValid(4_999)?.value)
    }
}
