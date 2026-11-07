package com.valhalla.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthSignerTest {
    private val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val clientNonce = byteArrayOf(0x10, 0x20, 0x30)
    private val deviceDigest = "d3adb33f"

    @Test
    fun `规范化消息对三个要素都敏感`() {
        val base = AuthSignatureMessage.canonical(challenge, clientNonce, deviceDigest)
        assertNotEquals(base.contentHashCode(), AuthSignatureMessage.canonical(challenge, clientNonce, "other").contentHashCode())
        assertNotEquals(
            base.contentHashCode(),
            AuthSignatureMessage.canonical(challenge, byteArrayOf(0x11), deviceDigest).contentHashCode(),
        )
        assertNotEquals(
            base.contentHashCode(),
            AuthSignatureMessage.canonical(byteArrayOf(0x09), clientNonce, deviceDigest).contentHashCode(),
        )
    }

    @Test
    fun `长度前缀避免拼接歧义`() {
        val left = AuthSignatureMessage.canonical(byteArrayOf(0x01), byteArrayOf(0x02, 0x03), "d")
        val right = AuthSignatureMessage.canonical(byteArrayOf(0x01, 0x02), byteArrayOf(0x03), "d")
        assertFalse(left.contentEquals(right))
    }

    @Test
    fun `相同输入产生稳定签名`() {
        val signer = HmacAuthSigner("shared-secret".encodeToByteArray())
        val message = AuthSignatureMessage.canonical(challenge, clientNonce, deviceDigest)
        assertEquals(
            signer.sign(message).toList(),
            HmacAuthSigner("shared-secret".encodeToByteArray()).sign(message).toList(),
        )
    }

    @Test
    fun `签名可被相同密钥验证且错误密钥失败`() {
        val message = AuthSignatureMessage.canonical(challenge, clientNonce, deviceDigest)
        val signature = HmacAuthSigner("shared-secret".encodeToByteArray()).sign(message)

        assertTrue(HmacSha256.verify("shared-secret".encodeToByteArray(), message, signature))
        assertFalse(HmacSha256.verify("wrong-secret".encodeToByteArray(), message, signature))
    }

    @Test
    fun `签名器拒绝空密钥`() {
        assertFailsWith<IllegalArgumentException> { HmacAuthSigner(ByteArray(0)) }
    }

    @Test
    fun `算法标识为 HMAC-SHA256`() {
        assertEquals("HMAC-SHA256", HmacAuthSigner("k".encodeToByteArray()).algorithm)
    }
}
