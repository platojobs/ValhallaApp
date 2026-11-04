package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState
import kotlin.test.Test
import com.valhalla.feature.i18n.AppStrings
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlePermissionStateMachineTest {

    @Test
    fun `初始为未请求且允许发起一次请求`() {
        val machine = BlePermissionStateMachine()

        assertEquals(BlePermissionState.NOT_REQUESTED, machine.state)
        assertTrue(machine.canRequest)
        assertTrue(machine.beginRequest())
        // 弹窗在途时重复点击不会再弹第二次。
        assertFalse(machine.beginRequest())
        assertFalse(machine.canRequest)
    }

    @Test
    fun `授权后不再请求`() {
        val machine = BlePermissionStateMachine()
        assertTrue(machine.beginRequest())

        assertEquals(
            BlePermissionState.GRANTED,
            machine.onRequestResult(granted = true, canAskAgain = false),
        )
        assertFalse(machine.canRequest)
        assertFalse(machine.beginRequest())
    }

    @Test
    fun `被拒绝但可再次弹窗时保留重新请求能力`() {
        val machine = BlePermissionStateMachine()
        assertTrue(machine.beginRequest())

        assertEquals(
            BlePermissionState.DENIED,
            machine.onRequestResult(granted = false, canAskAgain = true),
        )
        assertTrue(machine.canRequest)
        assertTrue(machine.beginRequest())
    }

    @Test
    fun `永久拒绝后不再弹窗`() {
        val machine = BlePermissionStateMachine()
        assertTrue(machine.beginRequest())

        assertEquals(
            BlePermissionState.PERMANENTLY_DENIED,
            machine.onRequestResult(granted = false, canAskAgain = false),
        )
        assertFalse(machine.canRequest)
        assertFalse(machine.beginRequest())
        // 再次收到拒绝结果也不会回退成可请求。
        assertEquals(
            BlePermissionState.PERMANENTLY_DENIED,
            machine.onRequestResult(granted = false, canAskAgain = false),
        )
    }

    @Test
    fun `回到前台同步系统状态`() {
        val machine = BlePermissionStateMachine()

        assertEquals(
            BlePermissionState.NOT_REQUESTED,
            machine.syncFromSystem(granted = false, canAskAgain = false, hasRequestedBefore = false),
        )
        assertEquals(
            BlePermissionState.GRANTED,
            machine.syncFromSystem(granted = true, canAskAgain = false, hasRequestedBefore = true),
        )
        assertEquals(
            BlePermissionState.DENIED,
            machine.syncFromSystem(granted = false, canAskAgain = true, hasRequestedBefore = true),
        )
        // 已授权后在设置里被回收：系统不再给弹窗机会 -> 永久拒绝。
        assertEquals(
            BlePermissionState.PERMANENTLY_DENIED,
            machine.syncFromSystem(granted = false, canAskAgain = false, hasRequestedBefore = true),
        )
    }

    @Test
    fun `取消在途请求后可重新发起`() {
        val machine = BlePermissionStateMachine()
        assertTrue(machine.beginRequest())
        machine.cancelRequest()

        assertTrue(machine.canRequest)
        assertTrue(machine.beginRequest())
    }

    @Test
    fun `权限与蓝牙状态合并为可用状态`() {
        assertEquals(
            BleAvailability.PERMISSION_NOT_REQUESTED,
            resolveBleAvailability(BleAdapterState.POWERED_ON, BlePermissionState.NOT_REQUESTED),
        )
        assertEquals(
            BleAvailability.PERMISSION_DENIED,
            resolveBleAvailability(BleAdapterState.POWERED_ON, BlePermissionState.DENIED),
        )
        assertEquals(
            BleAvailability.PERMISSION_PERMANENTLY_DENIED,
            resolveBleAvailability(BleAdapterState.POWERED_ON, BlePermissionState.PERMANENTLY_DENIED),
        )
        assertEquals(
            BleAvailability.PERMISSION_PERMANENTLY_DENIED,
            resolveBleAvailability(BleAdapterState.UNAUTHORIZED, BlePermissionState.GRANTED),
        )
        assertEquals(
            BleAvailability.BLUETOOTH_OFF,
            resolveBleAvailability(BleAdapterState.POWERED_OFF, BlePermissionState.GRANTED),
        )
        assertEquals(
            BleAvailability.BLUETOOTH_RESETTING,
            resolveBleAvailability(BleAdapterState.RESETTING, BlePermissionState.GRANTED),
        )
        assertEquals(
            BleAvailability.UNSUPPORTED,
            resolveBleAvailability(BleAdapterState.UNSUPPORTED, BlePermissionState.GRANTED),
        )
        assertEquals(
            BleAvailability.UNKNOWN,
            resolveBleAvailability(BleAdapterState.UNKNOWN, BlePermissionState.GRANTED),
        )
        assertEquals(
            BleAvailability.READY,
            resolveBleAvailability(BleAdapterState.POWERED_ON, BlePermissionState.GRANTED),
        )
    }

    @Test
    fun `只有 READY 允许扫描连接`() {
        BleAvailability.entries.forEach { availability ->
            assertEquals(availability == BleAvailability.READY, availability.allowsConnection())
        }
    }

    @Test
    fun `每种不可用状态都有可读引导与动作`() {
        BleAvailability.entries.filter { it != BleAvailability.READY }.forEach { availability ->
            val guidance = assertNotNull(availability.guidance(), "缺少引导：$availability")
            assertNotNull(AppStrings.entry(guidance.title.key))
            assertNotNull(AppStrings.entry(guidance.message.key))
        }

        assertNull(BleAvailability.READY.guidance())

        assertEquals(
            BleGuidanceAction.REQUEST_PERMISSION,
            BleAvailability.PERMISSION_NOT_REQUESTED.guidance()?.action,
        )
        assertEquals(
            BleGuidanceAction.REQUEST_PERMISSION,
            BleAvailability.PERMISSION_DENIED.guidance()?.action,
        )
        assertEquals(
            BleGuidanceAction.OPEN_SETTINGS,
            BleAvailability.PERMISSION_PERMANENTLY_DENIED.guidance()?.action,
        )
        assertEquals(
            BleGuidanceAction.ENABLE_BLUETOOTH,
            BleAvailability.BLUETOOTH_OFF.guidance()?.action,
        )
        assertEquals(
            UiMessage(StringKey.ACTION_OPEN_SETTINGS),
            BleAvailability.PERMISSION_PERMANENTLY_DENIED.guidance()?.actionLabel,
        )
    }
}
