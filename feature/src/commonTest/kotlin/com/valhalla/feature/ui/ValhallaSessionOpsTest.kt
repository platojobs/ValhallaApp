package com.valhalla.feature.ui

import com.valhalla.core.automation.AutomationRuleRepository
import com.valhalla.core.automation.InMemoryAutomationRuleStore
import com.valhalla.core.batch.BatchItemStatus
import com.valhalla.core.batch.BatchOperationKind
import com.valhalla.core.batch.BatchTarget
import com.valhalla.core.config.ConfigPreset
import com.valhalla.core.config.ConfigPresetRepository
import com.valhalla.core.config.InMemoryConfigPresetStore
import com.valhalla.core.event.InMemoryDeviceEventStore
import com.valhalla.core.firmware.InMemoryOtaCheckpointStore
import com.valhalla.core.profile.DeviceProfileRepository
import com.valhalla.core.profile.InMemoryDeviceProfileStore
import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import com.valhalla.data.ble.BleConnection
import com.valhalla.data.ble.BleDevice
import com.valhalla.data.ble.BleException
import com.valhalla.data.ble.ScanFilter
import com.valhalla.feature.batch.BatchDeviceOutcome
import com.valhalla.feature.ble.BleAvailability
import com.valhalla.feature.ble.BleGuidanceAction
import com.valhalla.feature.ble.BlePermissionController
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.device.MultiDeviceManager
import com.valhalla.feature.error.ErrorReporter
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.scan.ScanController
import com.valhalla.feature.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话编排层测试。
 *
 * 这些用例直接驱动 [ValhallaSessionOps]，锁住 UI 回调到会话状态的映射：
 * 连接结果分支、断开后的路由回落、重连守卫与批量操作的早退分支。
 * 控制器自身的协议细节由各控制器测试覆盖，这里只验证编排行为。
 */
class ValhallaSessionOpsTest {
    private class FakeConnection(
        override val deviceId: String,
        connectedInitially: Boolean = true,
    ) : BleConnection {
        val connectedState = MutableStateFlow(connectedInitially)
        override val connected: StateFlow<Boolean> = connectedState
        var disconnectCount: Int = 0

        override suspend fun write(
            serviceUuid: String,
            characteristicUuid: String,
            payload: ByteArray,
            withResponse: Boolean,
        ) = Unit

        override suspend fun read(
            serviceUuid: String,
            characteristicUuid: String,
        ): ByteArray = ByteArray(0)

        override fun observe(
            serviceUuid: String,
            characteristicUuid: String,
        ): Flow<ByteArray> = emptyFlow()

        override suspend fun readRssi(): Int? = null

        override suspend fun disconnect() {
            disconnectCount++
        }
    }

    private class FakeBleClient(
        var failConnect: Boolean = false,
    ) : BleClient {
        override val adapterState: StateFlow<BleAdapterState> = MutableStateFlow(BleAdapterState.POWERED_ON)
        var refreshCount: Int = 0

        override fun refreshAdapterState() {
            refreshCount++
        }

        override fun scan(filter: ScanFilter): Flow<BleDevice> = emptyFlow()

        override suspend fun connect(
            deviceId: String,
            timeoutMillis: Long,
        ): BleConnection {
            if (failConnect) throw BleException("连接失败")
            return FakeConnection(deviceId)
        }

        override fun close() = Unit
    }

    private class FakePermissionController : BlePermissionController {
        override val state: StateFlow<BlePermissionState> = MutableStateFlow(BlePermissionState.GRANTED)
        var permissionRequests: Int = 0
        var settingsOpened: Int = 0
        var bluetoothRequests: Int = 0
        var refreshes: Int = 0

        override fun requestPermission() {
            permissionRequests++
        }

        override fun openAppSettings() {
            settingsOpened++
        }

        override fun requestEnableBluetooth() {
            bluetoothRequests++
        }

        override fun refresh() {
            refreshes++
        }
    }

    private class Harness(
        scope: CoroutineScope,
        capacity: Int = 4,
        val client: FakeBleClient = FakeBleClient(),
    ) {
        val ui = ValhallaUiState(Route.Scan, null)
        val eventStore = InMemoryDeviceEventStore()
        val errorReporter = ErrorReporter()
        val scanController = ScanController(client, scope)
        val deviceManager = MultiDeviceManager(client, capacity = capacity)
        val ops =
            ValhallaSessionOps(
                ui = ui,
                services =
                    ValhallaSessionServices(
                        scope = scope,
                        errorReporter = errorReporter,
                        bleClient = client,
                        deviceManager = deviceManager,
                        profileRepository = DeviceProfileRepository(InMemoryDeviceProfileStore()),
                        checkpointStore = InMemoryOtaCheckpointStore(),
                        eventStore = eventStore,
                        automationRepository =
                            AutomationRuleRepository(
                                store = InMemoryAutomationRuleStore(),
                                clockMillis = { 1L },
                            ),
                        presetRepository =
                            ConfigPresetRepository(
                                store = InMemoryConfigPresetStore(),
                                clockMillis = { 1L },
                            ),
                        settings = AppSettings(),
                        strings = StringResolver(ResolvedLanguage.CHINESE),
                    ),
            )
    }

    private val device = BleDevice(deviceId = "dev-1", name = "设备一", rssi = -50)

    @Test
    fun `扫描请求在蓝牙不可用时给出引导且不启动扫描`() =
        runTest {
            val harness = Harness(this)

            harness.ops.requestStartScan(BleAvailability.PERMISSION_DENIED, harness.scanController)

            assertNotNull(harness.ui.errorMessage)
            assertFalse(harness.scanController.state.value.scanning)
        }

    @Test
    fun `扫描请求在就绪时清空错误并启动扫描`() =
        runTest {
            val harness = Harness(this)
            harness.ui.errorMessage = null

            harness.ops.requestStartScan(BleAvailability.READY, harness.scanController)

            assertNull(harness.ui.errorMessage)
            assertTrue(harness.scanController.state.value.scanning)
            harness.scanController.stopScan()
        }

    @Test
    fun `蓝牙引导动作分派到权限控制器与客户端刷新`() =
        runTest {
            val harness = Harness(this)
            val permission = FakePermissionController()

            harness.ops.handleBleAction(BleGuidanceAction.REQUEST_PERMISSION, permission)
            harness.ops.handleBleAction(BleGuidanceAction.OPEN_SETTINGS, permission)
            harness.ops.handleBleAction(BleGuidanceAction.ENABLE_BLUETOOTH, permission)
            harness.ops.handleBleAction(BleGuidanceAction.RETRY, permission)
            harness.ops.handleBleAction(BleGuidanceAction.NONE, permission)

            assertEquals(1, permission.permissionRequests)
            assertEquals(1, permission.settingsOpened)
            assertEquals(1, permission.bluetoothRequests)
            assertEquals(1, permission.refreshes)
            assertEquals(1, harness.client.refreshCount)
        }

    @Test
    fun `连接成功时建立会话记录事件并跳转详情`() =
        runTest {
            val harness = Harness(this)

            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()

            assertTrue(harness.ui.sessions.containsKey(device.deviceId))
            assertEquals(Route.Detail(device.deviceId), harness.ui.route)
            assertNull(harness.ui.connectingDeviceId)
            assertNotNull(harness.eventStore.loadRaw(device.deviceId))
        }

    @Test
    fun `已连接设备再次连接只切换当前设备不新建会话`() =
        runTest {
            val harness = Harness(this)
            harness.deviceManager.connect(device)

            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()

            assertEquals(Route.Detail(device.deviceId), harness.ui.route)
            assertTrue(harness.ui.sessions.isEmpty())
        }

    @Test
    fun `容量不足时提示上限而不是静默失败`() =
        runTest {
            val harness = Harness(this, capacity = 1)
            harness.deviceManager.connect(BleDevice("other", "其它", -60))

            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()

            assertNotNull(harness.ui.errorMessage)
            assertTrue(harness.ui.sessions.isEmpty())
            assertNull(harness.ui.connectingDeviceId)
        }

    @Test
    fun `连接失败时展示错误并复位连接中标记`() =
        runTest {
            val client = FakeBleClient(failConnect = true)
            val harness = Harness(this, client = client)

            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()

            assertNotNull(harness.ui.errorMessage)
            assertTrue(harness.ui.sessions.isEmpty())
            assertNull(harness.ui.connectingDeviceId)
        }

    @Test
    fun `蓝牙不可用时连接直接给出引导`() =
        runTest {
            val harness = Harness(this)

            harness.ops.connectFromScan(device, BleAvailability.BLUETOOTH_OFF, harness.scanController)
            advanceUntilIdle()

            assertNotNull(harness.ui.errorMessage)
            assertTrue(harness.ui.sessions.isEmpty())
        }

    @Test
    fun `断开单台设备后从详情页回到扫描页`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)

            harness.ops.disconnectDevice(session)
            advanceUntilIdle()

            assertTrue(harness.ui.sessions.isEmpty())
            assertEquals(Route.Scan, harness.ui.route)
            assertTrue(session.connection is FakeConnection)
            // 详情层断链一次，管理器回收槽位时再断一次，两处都必须幂等调用。
            assertEquals(2, (session.connection as FakeConnection).disconnectCount)
        }

    @Test
    fun `断开全部设备清空会话并回到扫描页`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            harness.ui.route = Route.Devices

            harness.ops.disconnectAll()
            advanceUntilIdle()

            assertTrue(harness.ui.sessions.isEmpty())
            assertEquals(Route.Scan, harness.ui.route)
        }

    @Test
    fun `断开并返回在多设备时回到设备页`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val second = BleDevice("dev-2", "设备二", -60)
            harness.ops.connectFromScan(second, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val third = BleDevice("dev-3", "设备三", -70)
            harness.ops.connectFromScan(third, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)

            harness.ops.disconnectAndLeave(session, device.deviceId)
            advanceUntilIdle()

            assertEquals(setOf("dev-2", "dev-3"), harness.ui.sessions.keys)
            assertTrue(session.userDisconnected)
            assertEquals(Route.Devices, harness.ui.route)
        }

    @Test
    fun `断开并返回在最后一台设备时回到扫描页`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)

            harness.ops.disconnectAndLeave(session, device.deviceId)
            advanceUntilIdle()

            assertEquals(Route.Scan, harness.ui.route)
        }

    @Test
    fun `进行中的重连不允许重复发起`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)

            harness.ops.startReconnect(session)
            val first = harness.ui.reconnect
            assertNotNull(first)
            harness.ops.startReconnect(session)

            assertEquals(first, harness.ui.reconnect)
            harness.ops.cancelReconnect()
            assertNull(harness.ui.reconnect)
        }

    @Test
    fun `重连成功后用新链路替换会话并跳转详情`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)

            harness.ops.startReconnect(session)
            advanceUntilIdle()

            assertEquals(Route.Detail(device.deviceId), harness.ui.route)
            assertNull(harness.ui.reconnect)
            assertTrue(harness.ui.sessions.containsKey(device.deviceId))
        }

    @Test
    fun `批量操作缺少会话时标记移除`() =
        runTest {
            val outcome =
                executeBatchOperation(
                    kind = BatchOperationKind.HEALTH_CHECK,
                    preset = null,
                    target = BatchTarget("missing", "缺失"),
                    step = {},
                    sessions = emptyMap(),
                )

            assertEquals(BatchItemStatus.REMOVED, outcome.status)
            assertEquals("batch.disconnected", outcome.reason)
        }

    @Test
    fun `批量操作在链路断开时标记移除`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val session = harness.ui.sessions.getValue(device.deviceId)
            (session.connection as FakeConnection).connectedState.value = false

            val outcome =
                executeBatchOperation(
                    kind = BatchOperationKind.HEALTH_CHECK,
                    preset = null,
                    target = BatchTarget(device.deviceId, device.name.orEmpty()),
                    step = {},
                    sessions = harness.ui.sessions,
                )

            assertEquals(BatchItemStatus.REMOVED, outcome.status)
        }

    @Test
    fun `批量应用预设但未选择预设时失败`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()

            val outcome =
                executeBatchOperation(
                    kind = BatchOperationKind.APPLY_PRESET,
                    preset = null,
                    target = BatchTarget(device.deviceId, device.name.orEmpty()),
                    step = {},
                    sessions = harness.ui.sessions,
                )

            assertEquals(BatchItemStatus.FAILED, outcome.status)
            assertEquals("batch.no_preset", outcome.reason)
        }

    @Test
    fun `批量应用预设时把预设传给后续步骤`() =
        runTest {
            val harness = Harness(this)
            harness.ops.connectFromScan(device, BleAvailability.READY, harness.scanController)
            advanceUntilIdle()
            val preset = ConfigPreset("p1", "常用", emptyMap(), 1L, 1L)
            val steps = mutableListOf<String>()

            val outcome: BatchDeviceOutcome =
                executeBatchOperation(
                    kind = BatchOperationKind.APPLY_PRESET,
                    preset = preset,
                    target = BatchTarget(device.deviceId, device.name.orEmpty()),
                    step = steps::add,
                    sessions = harness.ui.sessions,
                )

            // 配置读取在无应答链路上会超时，最终按失败收敛，但步骤必须已上报。
            assertTrue(steps.contains("batch.preset"))
            assertTrue(outcome.status.terminal)
        }
}
