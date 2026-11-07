package com.valhalla.feature.ble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.valhalla.data.ble.BleClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVM/桌面端没有运行时蓝牙权限概念，直接视为已授权；
 * 桌面端仅用于本地调试与 JVM 测试。
 */
@Composable
actual fun rememberBlePermissionController(bleClient: BleClient): BlePermissionController =
    remember(bleClient) {
        object : BlePermissionController {
            override val state: StateFlow<BlePermissionState> =
                MutableStateFlow(BlePermissionState.GRANTED).asStateFlow()

            override fun requestPermission() = Unit

            override fun openAppSettings() = Unit

            override fun requestEnableBluetooth() = Unit

            override fun refresh() = Unit
        }
    }
