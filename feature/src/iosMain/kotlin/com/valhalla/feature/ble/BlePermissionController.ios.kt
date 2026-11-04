package com.valhalla.feature.ble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.valhalla.data.ble.BleAdapterState
import com.valhalla.data.ble.BleClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

/**
 * iOS CoreBluetooth 没有单独的「请求权限」API：首次创建 CBCentralManager 时系统自动弹窗，
 * 之后只能通过系统设置修改。因此这里把 `CBManagerState` 映射为权限状态，
 * 并在回到前台时重读，避免 UI 停留在旧状态。
 */
@Composable
actual fun rememberBlePermissionController(bleClient: BleClient): BlePermissionController {
    val controller = remember(bleClient) { IosBlePermissionController(bleClient) }

    LaunchedEffect(bleClient) {
        bleClient.adapterState.collect { controller.onAdapterStateChanged(it) }
    }

    DisposableEffect(controller) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            controller.refresh()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
        }
    }

    return controller
}

private class IosBlePermissionController(
    private val bleClient: BleClient,
) : BlePermissionController {

    private val mutableState = MutableStateFlow(toPermissionState(bleClient.adapterState.value))
    override val state: StateFlow<BlePermissionState> = mutableState.asStateFlow()

    override fun requestPermission() {
        // 触发一次状态刷新即可；系统授权弹窗由 CBCentralManager 首次使用时负责。
        refresh()
    }

    override fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url)
    }

    override fun requestEnableBluetooth() {
        // iOS 不允许应用直接开启蓝牙，只能引导用户到设置。
        openAppSettings()
    }

    override fun refresh() {
        bleClient.refreshAdapterState()
        onAdapterStateChanged(bleClient.adapterState.value)
    }

    fun onAdapterStateChanged(adapterState: BleAdapterState) {
        mutableState.value = toPermissionState(adapterState)
    }
}

private fun toPermissionState(adapterState: BleAdapterState): BlePermissionState = when (adapterState) {
    BleAdapterState.POWERED_ON,
    BleAdapterState.POWERED_OFF,
    BleAdapterState.RESETTING,
    BleAdapterState.UNSUPPORTED,
    -> BlePermissionState.GRANTED

    BleAdapterState.UNAUTHORIZED -> BlePermissionState.PERMANENTLY_DENIED
    BleAdapterState.UNKNOWN -> BlePermissionState.NOT_REQUESTED
}
