package com.valhalla.core.security

import com.valhalla.core.protocol.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Sha256Test {
    @Test
    fun `空输入符合 FIPS 180-4 标准向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hex.encode(Sha256.compute(ByteArray(0)), upperCase = false),
        )
    }

    @Test
    fun `abc 向量`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hex.encode(Sha256.compute("abc".encodeToByteArray()), upperCase = false),
        )
    }

    @Test
    fun `接近块边界的标准向量`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Hex.encode(
                Sha256.compute("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
                upperCase = false,
            ),
        )
    }

    @Test
    fun `增量输入与一次性计算一致`() {
        val data = ByteArray(1000) { (it * 31 % 251).toByte() }
        val oneShot = Sha256.compute(data)
        val incremental = Sha256Digest()
        incremental.update(data, 0, 7)
        incremental.update(data, 7, 64)
        incremental.update(data, 71, data.size - 71)
        assertEquals(Hex.encode(oneShot), Hex.encode(incremental.digest()))
    }

    @Test
    fun `一百万个 a 的标准向量`() {
        val data = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Hex.encode(Sha256.compute(data), upperCase = false),
        )
    }

    @Test
    fun `不同输入产生不同摘要`() {
        assertNotEquals(
            Hex.encode(Sha256.compute("abc".encodeToByteArray())),
            Hex.encode(Sha256.compute("abd".encodeToByteArray())),
        )
    }
}
