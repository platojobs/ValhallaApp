package com.valhalla.core.logging

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 脱敏正则在长文本上的性能上限。
 *
 * 输入约 1 MB，混合 PEM 头、带标签凭据、MAC、UUID、长十六进制与 Base64。
 * 上限取 5 秒（CI 低配 runner 的宽松阈值），用于捕获回溯爆炸等数量级退化，
 * 不用于精确基准。
 */
class SensitiveTextRedactionPerformanceTest {
    @Test
    fun oneMegabyteTextRedactsWithinUpperBound() {
        val text =
            buildString(1_100_000) {
                repeat(4_000) {
                    append("deviceId=SN-12345678 token=supersecret123 ")
                    append("credential=cred-value-9 mac=AA:BB:CC:DD:EE:FF ")
                    append("uuid=550e8400-e29b-41d4-a716-446655440000 ")
                    append("key=0123456789abcdef0123456789abcdef0123456789abcdef ")
                    append("blob=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg==\n")
                }
            }
        assertTrue(text.length >= 1_000_000, "样本应至少 1 MB，实际 ${text.length}")

        val elapsed =
            measureTimeMillis {
                val redacted = SensitiveTextRedaction.redact(text)
                assertFalse(redacted.contains("supersecret123"))
                assertFalse(redacted.contains("SN-12345678"))
                assertTrue(redacted.contains("token=***"))
            }
        assertTrue(elapsed < 5_000, "1 MB 脱敏耗时 ${elapsed}ms，超过 5000ms 上限")
        println("SensitiveTextRedaction 1MB elapsed=${elapsed}ms")
    }
}
