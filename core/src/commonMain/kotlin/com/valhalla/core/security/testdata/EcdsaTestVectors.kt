@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.valhalla.core.security.testdata

import com.valhalla.core.protocol.Hex

/**
 * 跨端共享的 ECDSA-P256 测试向量。
 *
 * 数据由 **OpenSSL 3** 独立生成并用 `openssl pkeyutl -verify` 核对；摘要为原始 32 字节
 * SHA-256，签名格式为 DER/X9.62。JVM、Android instrumented 与 iOS 模拟器测试都通过
 * 本文件读取同一批常量，避免各端各写一套导致「看似一致、实际不同源」。
 *
 * 公钥/证书仅用于测试，不得进入生产包。对象位于 commonMain 是为了让 commonTest、
 * Android instrumented test 与 iOS test 三棵树都能引用同一份常量（KMP 的 commonTest
 * 不能同时被 instrumentedTest 树依赖）；内容只有公开测试公钥，不含任何私钥。
 */
object EcdsaTestVectors {
    const val PUBLIC_KEY_PEM: String = """
        -----BEGIN PUBLIC KEY-----
        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWXWes/sli2RbqBvrh8XJjxoqEG9U
        hImkFXqNjdLEQwzeHuLAidPNy1Qtt895nkOTUppNHfii/mVVw4GY/C/CnQ==
        -----END PUBLIC KEY-----
    """

    const val CERTIFICATE_PEM: String = """
        -----BEGIN CERTIFICATE-----
        MIIBhTCCASygAwIBAgIUf18XfofjsddqGFeocSu3BvMsS68wCgYIKoZIzj0EAwIw
        KTEnMCUGA1UEAwweVmFsaGFsbGEgVGVzdCBGaXJtd2FyZSBTaWduaW5nMB4XDTI2
        MTEwNTAwMDgxOVoXDTM2MTEwMjAwMDgxOVowKTEnMCUGA1UEAwweVmFsaGFsbGEg
        VGVzdCBGaXJtd2FyZSBTaWduaW5nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
        WXWes/sli2RbqBvrh8XJjxoqEG9UhImkFXqNjdLEQwzeHuLAidPNy1Qtt895nkOT
        UppNHfii/mVVw4GY/C/CnaMyMDAwHQYDVR0OBBYEFPKs26S8VUoO7VNmI5BWQrin
        cxnFMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgUpjFyE+zTmjT
        wCBoSPDYnb4lODfKsz30eawxuO7jUy0CIBaoeqJf/qngqH+gtV1T8OmDEKBh7Cv5
        v67LEY7NgKAb
        -----END CERTIFICATE-----
    """

    /** P-384 公钥，用于验证曲线白名单。 */
    const val P384_PUBLIC_KEY_PEM: String = """
        -----BEGIN PUBLIC KEY-----
        MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE0TxUcY1BxR/7nzjbv26JVxjb5nPPRs4t
        NXokZKPA/PbXWWcOQbPk+6674QnrOKP4qVYc2e7L+CVGfdJdsshZrbIsOAdZLLrf
        NGSlxxCLSZoNHM+3OzGrnaWfRjulGPqM
        -----END PUBLIC KEY-----
    """

    const val PRIVATE_KEY_PEM: String = """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgBmjv6/k/KKZBIaO2
        RPq2xxLHrtH9I+kvhUN6owM73oahRANCAARZdZ6z+yWLZFuoG+uHxcmPGioQb1SE
        iaQVeo2N0sRDDN4e4sCJ083LVC23z3meQ5NSmk0d+KL+ZVXDgZj8L8Kd
        -----END PRIVATE KEY-----
    """

    data class Vector(
        val name: String,
        val message: String,
        val digestHex: String,
        val signatureHex: String,
    ) {
        fun digest(): ByteArray = Hex.decode(digestHex)

        fun signature(): ByteArray = Hex.decode(signatureHex)
    }

    val ALL: List<Vector> =
        listOf(
            Vector(
                name = "vector-1",
                message = "valhalla-ecdsa-test-vector-1",
                digestHex = "aa0b3dcd03296f9e6b2073455f1721eb39534746a8a627abce2f2a99a5ef2e5d",
                signatureHex =
                    "30450221008a6af213d443e3b16549b1b497eaee97dd7226e1fc52ee65311e392bfb234b87" +
                        "022027178caeef2d510c5e786e6315ce35b5608b28313f9516b70c29ab38e377299e",
            ),
            Vector(
                name = "vector-2",
                message = "valhalla-ecdsa-test-vector-2",
                digestHex = "5338e9a61e427a555b8bb212b4afb49c027918a286282d8787fb590144f223a6",
                signatureHex =
                    "3045022100876d2333e7c3831bec73aa54ce83251bb410632e93573d2950ace168a1707494" +
                        "02203d48b85f4e98b0e49c07f133b8fe924cd6322d3e90b0e23735d2954527d7d3b8",
            ),
        )

    val VECTOR_1: Vector get() = ALL[0]
    val VECTOR_2: Vector get() = ALL[1]

    fun certificateDer(): ByteArray = pemBodyToDer(CERTIFICATE_PEM)

    fun tamperedDigest(vector: Vector): ByteArray = vector.digest().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

    fun tamperedSignature(vector: Vector): ByteArray = vector.signature().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }

    fun pemBodyToDer(pem: String): ByteArray {
        val body =
            pem
                .lines()
                .map { it.trim() }
                .filterNot { it.startsWith("-----") || it.isBlank() }
                .joinToString(separator = "")
        return kotlin.io.encoding.Base64
            .decode(body)
    }
}
