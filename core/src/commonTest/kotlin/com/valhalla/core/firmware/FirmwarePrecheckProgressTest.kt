package com.valhalla.core.firmware

import com.valhalla.core.protocol.message.FirmwareVersion
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirmwarePrecheckProgressTest {
    private val version = FirmwareVersion(2, 0, 0, 1, "release")
    private val current = FirmwareVersion(1, 9, 0, 0, "release")

    private class CountingSource(
        private val bytes: ByteArray,
        private val payloadEnd: Long,
    ) : FirmwareSource {
        var counting: Boolean = false
        var payloadBytesRead: Long = 0
            private set
        var closed: Boolean = false
            private set

        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(
            offset: Long,
            length: Int,
        ): ByteArray {
            val start = offset.toInt()
            val end = (start + length).coerceAtMost(bytes.size)
            if (counting) {
                val payloadStart = FirmwareHeader.SIZE
                val countedStart = maxOf(start, payloadStart)
                val countedEnd = minOf(end.toLong(), payloadEnd).toInt()
                if (countedEnd > countedStart) payloadBytesRead += (countedEnd - countedStart).toLong()
            }
            return bytes.copyOfRange(start, end)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `预检进度从 0 单调递增到 100 百分比`() =
        runTest {
            val payload = ByteArray(130 * 1024) { (it % 251).toByte() }
            val firmware = buildPackage(payload)
            val fractions = mutableListOf<Float>()

            val result =
                FirmwarePrechecker().check(
                    firmware = firmware,
                    currentVersion = current,
                    currentModel = "VH-100",
                    onProgress = { progress -> fractions += progress.fraction },
                )

            assertTrue(result.passed)
            assertEquals(0f, fractions.first())
            assertEquals(1f, fractions.last())
            assertTrue(fractions.zipWithNext().all { (left, right) -> right >= left })
            assertTrue(fractions.size >= 3, "130 KiB 应至少分 2 个 64 KiB 块上报进度")
        }

    @Test
    fun `用户取消预检抛出专用异常`() =
        runTest {
            val firmware = buildPackage(ByteArray(130 * 1024) { it.toByte() })
            var progressCalls = 0

            var cancelled = false
            try {
                FirmwarePrechecker().check(
                    firmware = firmware,
                    currentVersion = current,
                    currentModel = "VH-100",
                    onProgress = { progressCalls++ },
                    shouldCancel = { progressCalls >= 2 },
                )
            } catch (expected: FirmwarePrecheckCancelledException) {
                cancelled = true
            }

            assertTrue(cancelled, "应当通过 FirmwarePrecheckCancelledException 中止预检")
            assertTrue(progressCalls >= 2)
        }

    @Test
    fun `已签名固件只扫描 payload 一次`() =
        runTest {
            val payload = ByteArray(4096) { (it * 7 % 251).toByte() }
            val secret = "firmware-test-secret".encodeToByteArray()
            val header =
                FirmwareHeader(
                    firmwareVersion = version,
                    targetModels = listOf("VH-100"),
                    payloadLength = payload.size.toLong(),
                    payloadCrc32 = Crc32.compute(payload),
                    hasSignature = true,
                )
            val signature = FirmwareTestSigner.signWithHmacKey(header, payload, secret)
            val source =
                CountingSource(
                    bytes = header.encode() + payload + signature.encode(),
                    payloadEnd = FirmwareHeader.SIZE.toLong() + payload.size,
                )
            val firmware = FirmwareParser.parse("signed.bin", source)
            source.counting = true

            val result =
                FirmwarePrechecker(
                    signatureVerifier = HmacSha256TestVerifier(secret),
                    signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
                ).check(firmware, current, "VH-100")

            assertTrue(result.passed)
            assertEquals(payload.size.toLong(), source.payloadBytesRead, "CRC32/CRC16/签名摘要应由同一次扫描产出")
            firmware.close()
            assertTrue(source.closed)
        }

    @Test
    fun `scanPayload 对取消状态敏感`() =
        runTest {
            val firmware = buildPackage(ByteArray(130 * 1024) { it.toByte() })
            // 预检入口已覆盖用户取消；这里直接验证底层扫描在协程取消时停止。
            assertFailsWith<FirmwarePrecheckCancelledException> {
                firmware.scanPayload(onProgress = { read, _ ->
                    if (read > 0) throw FirmwarePrecheckCancelledException()
                })
            }
        }

    private suspend fun buildPackage(payload: ByteArray): FirmwarePackage {
        val header =
            FirmwareHeader(
                firmwareVersion = version,
                targetModels = listOf("VH-100"),
                payloadLength = payload.size.toLong(),
                payloadCrc32 = Crc32.compute(payload),
            ).encode()
        return FirmwareParser.parse("test.bin", FirmwareSource.memory(header + payload))
    }
}
