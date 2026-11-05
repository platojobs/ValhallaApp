package com.valhalla.feature.e2e

import com.valhalla.core.command.CommandFailure
import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.firmware.Crc32
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwarePrechecker
import com.valhalla.core.firmware.FirmwareSignaturePolicy
import com.valhalla.core.firmware.FirmwareSource
import com.valhalla.core.firmware.HmacSha256TestVerifier
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.firmware.OtaResumePoint
import com.valhalla.core.protocol.CommandType
import com.valhalla.core.protocol.Crc16
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FinishOtaRequest
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.OtaDataRequest
import com.valhalla.core.protocol.message.StartOtaRequest
import com.valhalla.core.security.HmacAuthSigner
import com.valhalla.feature.detail.DeviceDetailController
import com.valhalla.feature.firmware.FirmwarePicker
import com.valhalla.feature.firmware.FirmwareSelectionController
import com.valhalla.feature.firmware.PickedFirmware
import com.valhalla.feature.i18n.asChinese
import com.valhalla.feature.ota.OtaController
import com.valhalla.feature.ota.OtaState
import com.valhalla.testkit.DeviceSimulator
import com.valhalla.testkit.FaultInjection
import com.valhalla.testkit.ResumePolicy
import com.valhalla.testkit.SimulatorFirmware
import com.valhalla.testkit.SimulatorScenario
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备模拟器 + 调度器 + 固件预检 + OTA 控制器的端到端协议一致性测试。
 *
 * 这些用例证明 App 与「按协议字典实现的设备」能够闭环；它们不代表真机 BLE/固件行为。
 */
class DeviceSimulatorE2ETest {

    private val deviceVersion = FirmwareVersion(1, 0, 0, 0, "stable")
    private val targetVersion = FirmwareVersion(1, 1, 0, 0, "stable")
    private val payload = ByteArray(300) { (it * 7 % 251).toByte() }
    private val payloadCrc32 = Crc32.compute(payload)
    private val payloadCrc16 = Crc16.ccittFalse(payload)

    private class SingleFirmwarePicker(
        private val name: String,
        private val bytes: ByteArray,
    ) : FirmwarePicker {
        override suspend fun pickFirmware(): PickedFirmware =
            PickedFirmware(name, FirmwareSource.memory(bytes))
    }

    private fun packageBytes(): ByteArray =
        SimulatorFirmware.packageBytes(payload, targetVersion, listOf("VH-100"))

    private fun resumePoint(sentBytes: Int, version: FirmwareVersion = targetVersion) =
        OtaResumePoint(
            sessionId = 7,
            sentBytes = sentBytes,
            totalBytes = payload.size,
            crc16 = payloadCrc16,
            firmwareVersion = version,
            crc32 = payloadCrc32,
        )

    @Test
    fun `鉴权到升级完成的完整链路`() = runTest {
        val simulator = DeviceSimulator(SimulatorScenario(deviceVersion = deviceVersion), backgroundScope)
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val store = InMemoryOtaCheckpointStore()
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )

        val auth = dispatcher.execute(
            AuthRequest(AuthMode.TOKEN, "token".encodeToByteArray()),
        ) as com.valhalla.core.protocol.message.AuthResponse
        assertEquals("sim-token", auth.token)

        val info = dispatcher.execute(GetDeviceInfoRequest()) as DeviceInfoResponse
        assertEquals("VH-100", info.model)
        val version = dispatcher.execute(GetFirmwareVersionRequest()) as FirmwareVersionResponse
        assertEquals(deviceVersion, version.version)

        assertTrue(selection.pickAndPrecheck(info.firmwareVersion, info.model))
        val state = selection.state.value
        assertTrue(state.readyForOta)
        assertNull(state.resumePoint)
        assertEquals(payloadCrc16, state.computedCrc16)

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )
        val result = controller.startPackage(assertNotNull(selection.firmware))

        val succeeded = result as OtaState.Succeeded
        assertEquals(payload.size, succeeded.totalBytes)
        assertEquals(5, succeeded.chunks)
        assertTrue(succeeded.rebootRequired)
        val start = simulator.requests.filterIsInstance<StartOtaRequest>().single()
        assertFalse(start.hasResumeExtension)
        assertEquals(
            listOf(0L, 64L, 128L, 192L, 256L),
            simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset },
        )
        assertEquals(1, simulator.requests.filterIsInstance<FinishOtaRequest>().size)
        assertNull(store.load(), "成功后必须清除断点")
    }

    @Test
    fun `设备接受续传偏移时从断点继续`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128))
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.ACCEPT,
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val state = selection.state.value
        assertTrue(state.canResume)
        assertEquals(128, state.resumePoint?.sentBytes)
        assertNull(state.resumeBlockedReason)

        lateinit var controller: OtaController
        var observed: OtaState? = null
        simulator.onRequest = { request ->
            if (request is OtaDataRequest && request.offset == 128L) {
                observed = controller.state.value
            }
        }
        controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )

        val result = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = state.resumePoint,
        )

        assertTrue(result is OtaState.Succeeded)
        assertEquals(
            listOf(128L, 192L, 256L),
            simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset },
        )
        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertEquals(128, transferring.initialOffset)
        assertEquals(128f / 300f, transferring.progress, 1e-6f)
        assertNull(transferring.resumeFallbackReason)
        assertNull(store.load())
    }

    @Test
    fun `设备下调续传偏移时按确认值继续`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128))
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.ADJUST,
                resumeDownBytes = 32,
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        lateinit var controller: OtaController
        var observed: OtaState? = null
        simulator.onRequest = { request ->
            if (request is OtaDataRequest && request.offset == 96L) {
                observed = controller.state.value
            }
        }
        controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )

        val result = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = selection.state.value.resumePoint,
        )

        assertTrue(result is OtaState.Succeeded)
        assertEquals(
            listOf(96L, 160L, 224L, 288L),
            simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset },
        )
        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertEquals(96, transferring.initialOffset)
        assertTrue(transferring.resumeFallbackReason!!.asChinese().contains("下调"))
        assertNull(store.load())
    }

    @Test
    fun `设备拒绝续传时回退旧格式从头开始`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128))
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.REJECT,
                resumeRejectStatus = DeviceStatus.OTA_RESUME_CONTEXT_INVALID,
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        lateinit var controller: OtaController
        var observed: OtaState? = null
        simulator.onRequest = { request ->
            if (request is OtaDataRequest && request.offset == 0L) {
                observed = controller.state.value
            }
        }
        controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )

        val result = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = selection.state.value.resumePoint,
        )

        assertTrue(result is OtaState.Succeeded)
        val starts = simulator.requests.filterIsInstance<StartOtaRequest>()
        assertEquals(2, starts.size)
        assertTrue(starts[0].hasResumeExtension)
        assertFalse(starts[1].hasResumeExtension)
        assertEquals(
            listOf(0L, 64L, 128L, 192L, 256L),
            simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset },
        )
        val transferring = observed as? OtaState.Transferring
        assertNotNull(transferring)
        assertNotNull(transferring.resumeFallbackReason)
        assertTrue(transferring.resumeFallbackReason!!.asChinese().contains("回退"))
    }

    @Test
    fun `旧设备不识别扩展时回退并完成升级`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128))
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.LEGACY_DEVICE,
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )
        val result = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = selection.state.value.resumePoint,
        )

        assertTrue(result is OtaState.Succeeded)
        val starts = simulator.requests.filterIsInstance<StartOtaRequest>()
        assertEquals(2, starts.size)
        assertFalse(starts[1].hasResumeExtension)
        assertEquals(0L, simulator.requests.filterIsInstance<OtaDataRequest>().first().offset)
    }

    @Test
    fun `固件标识不一致时禁止续传并提示原因`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128, version = deviceVersion))
        val simulator = DeviceSimulator(SimulatorScenario(deviceVersion = deviceVersion), backgroundScope)
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val state = selection.state.value
        assertFalse(state.canResume)
        assertNull(state.resumePoint)
        assertNotNull(state.resumeBlockedReason)
        assertTrue(state.resumeBlockedReason!!.asChinese().contains("不匹配"))

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )
        controller.startPackage(assertNotNull(selection.firmware), resumeFrom = store.load())

        assertFalse(simulator.requests.filterIsInstance<StartOtaRequest>().single().hasResumeExtension)
    }

    @Test
    fun `中途断连后落盘断点并可再次续传`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.ACCEPT,
                faults = FaultInjection(disconnectAfterDataChunks = 2),
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            connectionState = simulator.connectionState,
            checkpointStore = store,
        )
        val first = controller.startPackage(assertNotNull(selection.firmware))

        assertTrue(first is OtaState.Failed)
        val point = assertNotNull(controller.lastResumePoint)
        assertEquals(128, point.sentBytes)
        assertEquals(point, store.load())
        assertEquals(2, simulator.requests.filterIsInstance<OtaDataRequest>().size)

        simulator.reconnect()
        simulator.scenario = simulator.scenario.copy(faults = FaultInjection())
        simulator.resetFaults()
        val resumed = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = controller.lastResumePoint,
        )
        assertTrue(resumed is OtaState.Succeeded)
        assertEquals(
            listOf(128L, 192L, 256L),
            simulator.requests.filterIsInstance<OtaDataRequest>().drop(2).map { it.offset },
        )
        assertNull(store.load())
    }

    @Test
    fun `设备返回 acceptedOffset 等于 totalBytes 时保守失败且不发送 FINISH`() = runTest {
        val store = InMemoryOtaCheckpointStore()
        store.save(resumePoint(128))
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                existingPayload = payload,
                resumePolicy = ResumePolicy.ACCEPT,
                forcedAcceptedOffset = payload.size.toLong(),
                allowCompleteResume = true,
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = store,
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
            checkpointStore = store,
        )
        val result = controller.startPackage(
            assertNotNull(selection.firmware),
            resumeFrom = selection.state.value.resumePoint,
        )

        // 期望行为（v1.2.0 起的保守策略）：设备声称「已接收完」属于非法应答，
        // 直接失败，不进入 TRANSFERRING/FINISH。
        val failed = result as OtaState.Failed
        assertTrue(failed.reason.asChinese().contains("偏移"))
        assertEquals(0, failed.sentBytes)
        assertTrue(simulator.requests.filterIsInstance<FinishOtaRequest>().isEmpty())
        assertTrue(simulator.requests.filterIsInstance<OtaDataRequest>().isEmpty())
        // 未发送任何字节，设备声称「已接收完」不可信；保守策略丢弃断点，
        // 避免把设备侧状态写回本地导致下次续传从错误位置开始。
        assertNull(store.load(), "校验失败时不应保留由设备声称的已完成偏移")
    }

    @Test
    fun `超时与忙时同偏移重传且最终成功`() = runTest {
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                faults = FaultInjection(
                    timeoutCommands = mapOf(CommandType.START_OTA to 1),
                    busyCommands = mapOf(CommandType.OTA_DATA to 1),
                ),
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
        )
        val result = controller.startPackage(assertNotNull(selection.firmware))

        val succeeded = result as OtaState.Succeeded
        assertTrue(succeeded.retransmissions >= 1)
        val dataOffsets = simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset }
        assertEquals(0L, dataOffsets[0])
        assertEquals(0L, dataOffsets[1], "BUSY 后必须以同一偏移重传")
    }

    @Test
    fun `确认长度不足时重传同一分片`() = runTest {
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                faults = FaultInjection(shortConfirmCommands = mapOf(CommandType.OTA_DATA to 1)),
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
        )
        val result = controller.startPackage(assertNotNull(selection.firmware))

        assertTrue(result is OtaState.Succeeded)
        val dataOffsets = simulator.requests.filterIsInstance<OtaDataRequest>().map { it.offset }
        assertEquals(0L, dataOffsets[0])
        assertEquals(0L, dataOffsets[1])
        assertTrue((result as OtaState.Succeeded).retransmissions >= 1)
    }

    @Test
    fun `重复应答 粘包 拆包与通知丢失不影响升级`() = runTest {
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                faults = FaultInjection(
                    duplicateCommands = mapOf(CommandType.START_OTA to 1),
                    stickyCommands = mapOf(CommandType.OTA_DATA to 1),
                    fragmentCommands = mapOf(CommandType.GET_DEVICE_INFO to 1),
                    dropNotifications = 2,
                ),
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val info = dispatcher.execute(GetDeviceInfoRequest()) as DeviceInfoResponse
        assertEquals("VH-100", info.model)

        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
        )
        selection.pickAndPrecheck(info.firmwareVersion, info.model)

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
        )
        assertTrue(controller.startPackage(assertNotNull(selection.firmware)) is OtaState.Succeeded)
    }

    @Test
    fun `设备整包校验失败时升级失败`() = runTest {
        val simulator = DeviceSimulator(
            scenario = SimulatorScenario(
                deviceVersion = deviceVersion,
                faults = FaultInjection(crcMismatchCommands = mapOf(CommandType.FINISH_OTA to 1)),
            ),
            scope = backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("firmware.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
        )
        selection.pickAndPrecheck(deviceVersion, "VH-100")

        val controller = OtaController(
            dispatcher = dispatcher,
            chunkSize = 64,
            scope = backgroundScope,
        )
        val result = controller.startPackage(assertNotNull(selection.firmware))

        val failed = result as OtaState.Failed
        assertEquals(DeviceStatus.CRC_MISMATCH, failed.status)
    }

    @Test
    fun `鉴权失败映射为结构化错误`() = runTest {
        val simulator = DeviceSimulator(
            SimulatorScenario(deviceVersion = deviceVersion, authStatus = DeviceStatus.AUTH_FAILED),
            backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)

        val error = kotlin.runCatching {
            dispatcher.execute(AuthRequest(AuthMode.TOKEN, "bad".encodeToByteArray()))
        }.exceptionOrNull()

        assertTrue(error is CommandFailure.DeviceRejected)
        assertEquals(DeviceStatus.AUTH_FAILED, error.status)
    }

    @Test
    fun `延迟应答时并发请求仍按序号配对`() = runTest {
        val simulator = DeviceSimulator(
            SimulatorScenario(
                deviceVersion = deviceVersion,
                faults = FaultInjection(delayedCommands = mapOf(CommandType.GET_BATTERY to 100L)),
            ),
            backgroundScope,
        )
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)

        coroutineScope {
            listOf(
                async { dispatcher.execute(GetBatteryRequest()) },
                async { dispatcher.execute(GetDeviceInfoRequest()) },
            ).awaitAll()
        }

        val battery = simulator.requests.filterIsInstance<GetBatteryRequest>()
        assertTrue(battery.isNotEmpty())
        val delayedBattery = dispatcher.execute(GetBatteryRequest()) as BatteryResponse
        assertEquals(87, delayedBattery.percent)
        assertTrue(delayedBattery.charging)
    }

    @Test
    fun `设备模拟器接受签名挑战应答并下发令牌`() = runTest {
        val scenario = SimulatorScenario(deviceVersion = deviceVersion)
        val simulator = DeviceSimulator(scenario, backgroundScope)
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val controller = DeviceDetailController(dispatcher, "sim-device")

        val success = controller.authenticateSigned(
            signer = HmacAuthSigner(SimulatorScenario.DEFAULT_AUTH_SECRET),
            clientNonce = ByteArray(16) { 0x51 },
            deviceDigest = scenario.deviceDigest,
        )

        assertTrue(success)
        assertEquals("sim-token", controller.sessionToken)
        assertEquals(com.valhalla.core.state.DeviceState.READY, controller.state.value.deviceState)
        assertEquals(2, simulator.requests.filterIsInstance<AuthRequest>().size)
    }

    @Test
    fun `设备模拟器拒绝错误密钥的签名挑战应答`() = runTest {
        val scenario = SimulatorScenario(deviceVersion = deviceVersion)
        val simulator = DeviceSimulator(scenario, backgroundScope)
        val dispatcher = ConcurrentCommandDispatcher(simulator, scope = backgroundScope)
        val controller = DeviceDetailController(dispatcher, "sim-device")

        val success = controller.authenticateSigned(
            signer = HmacAuthSigner("wrong-secret".encodeToByteArray()),
            clientNonce = ByteArray(16),
            deviceDigest = scenario.deviceDigest,
        )

        assertFalse(success)
        assertEquals(DeviceStatus.AUTH_FAILED, controller.state.value.errorCode)
        assertNull(controller.sessionToken)
    }

    @Test
    fun `已签名固件在 REQUIRE 策略下通过预检`() = runTest {
        val secret = "firmware-test-secret".encodeToByteArray()
        val signedBytes = SimulatorFirmware.packageBytes(
            payload = payload,
            version = targetVersion,
            models = listOf("VH-100"),
            signingSecret = secret,
        )
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("signed.bin", signedBytes),
            checkpointStore = InMemoryOtaCheckpointStore(),
            prechecker = FirmwarePrechecker(
                signatureVerifier = HmacSha256TestVerifier(secret),
                signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
            ),
        )

        assertTrue(selection.pickAndPrecheck(deviceVersion, "VH-100"))
        val state = selection.state.value
        assertTrue(state.readyForOta)
        assertNull(state.precheck?.warningSummary())
    }

    @Test
    fun `旧格式未签名固件在 REQUIRE 策略下被预检拒绝`() = runTest {
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("legacy.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
            prechecker = FirmwarePrechecker(
                signatureVerifier = HmacSha256TestVerifier("firmware-test-secret".encodeToByteArray()),
                signaturePolicy = FirmwareSignaturePolicy.REQUIRE,
            ),
        )

        assertFalse(selection.pickAndPrecheck(deviceVersion, "VH-100"))
        assertFalse(selection.state.value.readyForOta)
        assertTrue(selection.state.value.precheck!!.errors.any { it is FirmwareException.SignatureMissing })
    }

    @Test
    fun `旧格式未签名固件在 WARN 策略下仅提示`() = runTest {
        val selection = FirmwareSelectionController(
            picker = SingleFirmwarePicker("legacy.bin", packageBytes()),
            checkpointStore = InMemoryOtaCheckpointStore(),
            prechecker = FirmwarePrechecker(signaturePolicy = FirmwareSignaturePolicy.WARN),
        )

        assertTrue(selection.pickAndPrecheck(deviceVersion, "VH-100"))
        assertTrue(selection.state.value.readyForOta)
        assertNotNull(selection.state.value.precheck?.warningSummary())
    }
}
