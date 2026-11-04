package com.valhalla.feature.ble

import com.valhalla.data.ble.BleAdapterState
import kotlin.test.Test
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisconnectInfoTest {

    @Test
    fun `主动断开优先级最高且不自动重连`() {
        val info = classifyDisconnect(
            DisconnectSignals(
                userRequested = true,
                adapterState = BleAdapterState.POWERED_OFF,
                permissionState = BlePermissionState.PERMANENTLY_DENIED,
            ),
        )

        assertEquals(DisconnectReason.USER_REQUESTED, info.reason)
        assertEquals(DisconnectAction.RESCAN, info.action)
        assertFalse(info.autoReconnectAllowed)
    }

    @Test
    fun `权限丢失映射为去系统设置且不自动重连`() {
        BlePermissionState.entries
            .filter { it == BlePermissionState.DENIED || it == BlePermissionState.PERMANENTLY_DENIED }
            .forEach { permission ->
                val info = classifyDisconnect(DisconnectSignals(permissionState = permission))
                assertEquals(DisconnectReason.PERMISSION_LOST, info.reason)
                assertEquals(DisconnectAction.OPEN_SETTINGS, info.action)
                assertFalse(info.autoReconnectAllowed)
            }

        val unauthorized = classifyDisconnect(
            DisconnectSignals(adapterState = BleAdapterState.UNAUTHORIZED),
        )
        assertEquals(DisconnectReason.PERMISSION_LOST, unauthorized.reason)
    }

    @Test
    fun `蓝牙关闭映射为开启蓝牙并允许自动重连`() {
        val info = classifyDisconnect(
            DisconnectSignals(adapterState = BleAdapterState.POWERED_OFF),
        )

        assertEquals(DisconnectReason.BLUETOOTH_OFF, info.reason)
        assertEquals(DisconnectAction.ENABLE_BLUETOOTH, info.action)
        assertTrue(info.autoReconnectAllowed)
    }

    @Test
    fun `超时映射为重试`() {
        val info = classifyDisconnect(DisconnectSignals(timeout = true))

        assertEquals(DisconnectReason.TIMEOUT, info.reason)
        assertEquals(DisconnectAction.RECONNECT, info.action)
        assertEquals(UiMessage(StringKey.RECONNECT_NOW), info.actionLabel)
        assertTrue(info.autoReconnectAllowed)
    }

    @Test
    fun `设备侧断开映射为设备断开`() {
        val info = classifyDisconnect(DisconnectSignals(peerTerminated = true))

        assertEquals(DisconnectReason.DEVICE_SIDE, info.reason)
        assertEquals(DisconnectAction.RECONNECT, info.action)
        assertEquals(StringKey.DISCONNECT_DEVICE_MESSAGE, info.message.key)
    }

    @Test
    fun `传输错误映射为连接失败`() {
        val info = classifyDisconnect(DisconnectSignals(transportError = true))

        assertEquals(DisconnectReason.CONNECT_FAILED, info.reason)
        assertEquals(DisconnectAction.RECONNECT, info.action)
    }

    @Test
    fun `无信号时归入未知原因`() {
        val info = classifyDisconnect(DisconnectSignals())

        assertEquals(DisconnectReason.UNKNOWN, info.reason)
        assertTrue(info.autoReconnectAllowed)
    }

    @Test
    fun `蓝牙重置时提示重置并允许重连`() {
        val info = classifyDisconnect(
            DisconnectSignals(adapterState = BleAdapterState.RESETTING),
        )

        assertEquals(DisconnectReason.BLUETOOTH_OFF, info.reason)
        assertEquals(StringKey.DISCONNECT_RESETTING_TITLE, info.title.key)
        assertTrue(info.autoReconnectAllowed)
    }
}
