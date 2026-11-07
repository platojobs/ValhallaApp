package com.valhalla.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自由文本字段级脱敏规则测试。 */
class SensitiveTextRedactionTest {
    @Test
    fun `带标签的令牌与凭据只保留标签`() {
        val output =
            SensitiveTextRedaction.redact(
                "token=abc123 credential: \"cred-xyz\" password=p@ss serialNumber=SN-0001",
            )
        assertEquals("token=*** credential: *** password=*** serialNumber=***", output)
    }

    @Test
    fun `MAC 地址与 UUID 被替换且可区分`() {
        val output =
            SensitiveTextRedaction.redact(
                "device AA:BB:CC:DD:EE:FF and 123e4567-e89b-12d3-a456-426614174000",
            )
        assertFalse(output.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(output.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(output.startsWith("device AA:**:**:**:**:**"))
        assertTrue(output.contains("dev#"))
    }

    @Test
    fun `长十六进制与 PEM 块被整体替换`() {
        val keyHex = "0f1e2d3c4b5a69788796a5b4c3d2e1f0" + "a1b2c3d4e5f60718"
        val pem =
            """
            -----BEGIN PUBLIC KEY-----
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE
            -----END PUBLIC KEY-----
            """.trimIndent()
        val output = SensitiveTextRedaction.redact("key=$keyHex\n$pem")

        assertFalse(output.contains(keyHex))
        assertFalse(output.contains("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"))
        assertTrue(output.contains("[REDACTED PEM]"))
    }

    @Test
    fun `重复脱敏保持稳定`() {
        val once = SensitiveTextRedaction.redact("token=abc123 deviceId=AA:BB:CC:DD:EE:FF")
        assertEquals(once, SensitiveTextRedaction.redact(once))
        assertTrue(SensitiveTextRedaction.isRedacted(once))
        assertFalse(SensitiveTextRedaction.isRedacted("token=abc123"))
    }

    @Test
    fun `普通文案不被误伤`() {
        val plain = "固件版本 1.2.0 校验完成，耗时 1234 ms，进度 100%"
        assertEquals(plain, SensitiveTextRedaction.redact(plain))
    }
}
