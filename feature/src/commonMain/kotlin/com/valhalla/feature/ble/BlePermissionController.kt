package com.valhalla.feature.ble

import androidx.compose.runtime.Composable
import com.valhalla.data.ble.BleClient
import kotlinx.coroutines.flow.StateFlow

/**
 * 平台权限入口：把 Android 运行时权限 / iOS CoreBluetooth 授权收敛成
 * [BlePermissionState]，并暴露「请求权限 / 去系统设置 / 开启蓝牙」三类动作。
 */
interface BlePermissionController {
    val state: StateFlow<BlePermissionState>

    /** 请求权限；永久拒绝时不会再次弹窗（交由 [openAppSettings]）。 */
    fun requestPermission()

    /** 打开本应用的系统设置页。 */
    fun openAppSettings()

    /** 请求开启蓝牙（Android 弹系统确认框；iOS 打开设置页）。 */
    fun requestEnableBluetooth()

    /** 重新读取系统状态（回到前台或从设置返回时调用）。 */
    fun refresh()
}

/** 平台实现需在回到前台时同时刷新 [BleClient] 与权限状态。 */
@Composable
expect fun rememberBlePermissionController(bleClient: BleClient): BlePermissionController
