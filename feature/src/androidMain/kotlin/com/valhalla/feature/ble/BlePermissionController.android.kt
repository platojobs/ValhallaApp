package com.valhalla.feature.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.valhalla.data.ble.BleClient
import com.valhalla.data.settings.StorageKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android 12+ 使用 BLUETOOTH_SCAN / BLUETOOTH_CONNECT；11 及以下回落到定位权限。 */
private val requiredBlePermissions: List<String>
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

@Composable
actual fun rememberBlePermissionController(bleClient: BleClient): BlePermissionController {
    val context = LocalContext.current
    val activity = context.findActivity()

    if (activity == null) {
        return remember {
            object : BlePermissionController {
                override val state: StateFlow<BlePermissionState> =
                    MutableStateFlow(BlePermissionState.NOT_REQUESTED).asStateFlow()

                override fun requestPermission() = Unit

                override fun openAppSettings() = Unit

                override fun requestEnableBluetooth() = Unit

                override fun refresh() = Unit
            }
        }
    }

    val machine = remember(activity) { BlePermissionStateMachine() }
    val prefs =
        remember(activity) {
            activity.getSharedPreferences(StorageKeys.ANDROID_BLE_PERMISSION_PREFS, Context.MODE_PRIVATE)
        }
    val mutableState =
        remember(activity) {
            MutableStateFlow(
                activity.readPermissionState(machine, prefs),
            )
        }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            prefs.edit().putBoolean(StorageKeys.ANDROID_BLE_PERMISSION_KEY, true).apply()
            val granted =
                requiredBlePermissions.all {
                    activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
            val canAskAgain = requiredBlePermissions.any { activity.shouldShowRequestPermissionRationale(it) }
            mutableState.value = machine.onRequestResult(granted, canAskAgain)
            bleClient.refreshAdapterState()
        }

    val controller =
        remember(activity, launcher) {
            object : BlePermissionController {
                override val state: StateFlow<BlePermissionState> = mutableState.asStateFlow()

                override fun requestPermission() {
                    if (activity.readPermissionState(machine, prefs) == BlePermissionState.GRANTED) {
                        mutableState.value = BlePermissionState.GRANTED
                        return
                    }
                    // 永久拒绝时 beginRequest 返回 false，绝不再弹系统窗口。
                    if (!machine.beginRequest()) {
                        mutableState.value = machine.state
                        return
                    }
                    val missing =
                        requiredBlePermissions.filter {
                            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                        }
                    if (missing.isEmpty()) {
                        mutableState.value = machine.onRequestResult(granted = true, canAskAgain = false)
                        return
                    }
                    prefs.edit().putBoolean(StorageKeys.ANDROID_BLE_PERMISSION_KEY, true).apply()
                    launcher.launch(missing.toTypedArray())
                }

                override fun openAppSettings() {
                    val intent =
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { activity.startActivity(intent) }
                }

                override fun requestEnableBluetooth() {
                    val intent =
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { activity.startActivity(intent) }
                }

                override fun refresh() {
                    mutableState.value = activity.readPermissionState(machine, prefs)
                }
            }
        }

    DisposableEffect(activity, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    bleClient.refreshAdapterState()
                    controller.refresh()
                }
            }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            machine.cancelRequest()
        }
    }

    return controller
}

private fun ComponentActivity.readPermissionState(
    machine: BlePermissionStateMachine,
    prefs: SharedPreferences,
): BlePermissionState {
    val granted =
        requiredBlePermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    val hasRequestedBefore = prefs.getBoolean(StorageKeys.ANDROID_BLE_PERMISSION_KEY, false)
    val canAskAgain = requiredBlePermissions.any { shouldShowRequestPermissionRationale(it) }
    return machine.syncFromSystem(granted, canAskAgain, hasRequestedBefore)
}

private fun Context.findActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
