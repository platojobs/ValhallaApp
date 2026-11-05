package com.valhalla.feature.error

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 远端上报的脱敏与批量行为测试。 */
class RemoteErrorReporterTest {

    private class RecordingReporter : RemoteErrorReporter {
        val batches = mutableListOf<List<RemoteErrorPayload>>()
        var closed = false
        override suspend fun report(batch: List<RemoteErrorPayload>) {
            batches += batch
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `上报载荷不含设备标识 令牌 credential 与密钥片段`() = runTest {
        val transport = RecordingReporter()
        val dispatcher = RemoteErrorDispatcher(
            reporter = transport,
            scope = backgroundScope,
            batchSize = 4,
            flushIntervalMillis = 0,
        )
        val reporter = ErrorReporter(remoteDispatcher = dispatcher)

        reporter.record(
            origin = "device AA:BB:CC:DD:EE:FF",
            error = IllegalStateException(
                "auth failed deviceId=AA:BB:CC:DD:EE:FF token=supersecret123 " +
                    "credential=\"cred-value-9\" serialNumber=SN-12345678 " +
                    "nonce=0f1e2d3c4b5a69788796a5b4c3d2e1f0 " +
                    "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEH\n-----END PRIVATE KEY-----",
            ),
        )
        reporter.closeRemoteReporting()
        dispatcher.awaitCompletion()

        assertTrue(transport.closed)
        val payload = transport.batches.flatten().single()
        val text = listOf(payload.origin, payload.message, payload.stackTrace).joinToString("\n")

        listOf(
            "supersecret123",
            "cred-value-9",
            "SN-12345678",
            "AA:BB:CC:DD:EE:FF",
            "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEH",
        ).forEach { sensitive ->
            assertFalse(text.contains(sensitive), "上报载荷泄露了敏感串：$sensitive")
        }
        assertTrue(text.contains("token=***"), "令牌字段应保留标签并掩码")
    }

    @Test
    fun `按批次大小切分并在关闭时投递剩余条目`() = runTest {
        val transport = RecordingReporter()
        val dispatcher = RemoteErrorDispatcher(
            reporter = transport,
            scope = backgroundScope,
            batchSize = 3,
            flushIntervalMillis = 0,
        )
        val reporter = ErrorReporter(remoteDispatcher = dispatcher)

        repeat(5) { index ->
            reporter.record("origin-$index", IllegalStateException("failure-$index"))
        }
        reporter.closeRemoteReporting()
        dispatcher.awaitCompletion()

        assertEquals(listOf(3, 2), transport.batches.map { it.size })
        assertEquals(5, transport.batches.flatten().size)
    }

    @Test
    fun `默认空实现不产生任何上报`() = runTest {
        val reporter = ErrorReporter()
        reporter.record("origin", IllegalStateException("plain failure"))
        reporter.closeRemoteReporting()
        assertEquals(1, reporter.items.value.size)
        assertEquals(0, reporter.items.value.first().id)
    }
}
