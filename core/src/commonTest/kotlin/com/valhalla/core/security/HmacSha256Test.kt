package com.valhalla.core.security

import com.valhalla.core.protocol.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC 4231 标准测试向量。 */
class HmacSha256Test {
    @Test
    fun `RFC4231 测试用例 1`() {
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hex.encode(HmacSha256.compute(key, "Hi There".encodeToByteArray()), upperCase = false),
        )
    }

    @Test
    fun `RFC4231 测试用例 2`() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hex.encode(
                HmacSha256.compute(
                    "Jefe".encodeToByteArray(),
                    "what do ya want for nothing?".encodeToByteArray(),
                ),
                upperCase = false,
            ),
        )
    }

    @Test
    fun `RFC4231 测试用例 3`() {
        val key = ByteArray(20) { 0xaa.toByte() }
        val data = ByteArray(50) { 0xdd.toByte() }
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            Hex.encode(HmacSha256.compute(key, data), upperCase = false),
        )
    }

    @Test
    fun `RFC4231 测试用例 4`() {
        val key = Hex.decode("0102030405060708090a0b0c0d0e0f10111213141516171819")
        val data = ByteArray(50) { 0xcd.toByte() }
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            Hex.encode(HmacSha256.compute(key, data), upperCase = false),
        )
    }

    @Test
    fun `RFC4231 测试用例 6 超长密钥先做哈希`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            Hex.encode(
                HmacSha256.compute(key, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()),
                upperCase = false,
            ),
        )
    }

    @Test
    fun `RFC4231 测试用例 7 超长密钥与超长消息`() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val message =
            "This is a test using a larger than block-size key and a larger than " +
                "block-size data. The key needs to be hashed before being used by the HMAC algorithm."
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            Hex.encode(HmacSha256.compute(key, message.encodeToByteArray()), upperCase = false),
        )
    }

    @Test
    fun `错误密钥验签失败`() {
        val message = "signed payload".encodeToByteArray()
        val expected = HmacSha256.compute("key-1".encodeToByteArray(), message)
        assertTrue(HmacSha256.verify("key-1".encodeToByteArray(), message, expected))
        assertFalse(HmacSha256.verify("key-2".encodeToByteArray(), message, expected))
        assertFalse(HmacSha256.verify("key-1".encodeToByteArray(), "other".encodeToByteArray(), expected))
    }

    @Test
    fun `常量时间比较对长度与内容均敏感`() {
        val left = ByteArray(32) { it.toByte() }
        assertTrue(HmacSha256.constantTimeEquals(left, left.copyOf()))
        assertFalse(HmacSha256.constantTimeEquals(left, ByteArray(31) { it.toByte() }))
        val changed = left.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() }
        assertFalse(HmacSha256.constantTimeEquals(left, changed))
    }
}
