package com.valhalla.feature.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.ble.BlePermissionStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

private const val CHANNEL_ID = "valhalla_local_alerts"
private const val NOTIFICATION_PREFS = "valhalla_notification_permission"
private const val REQUESTED_KEY = "requested"

/** 与 MainActivity 约定的点击深链 extra。 */
const val EXTRA_NOTIFICATION_ROUTE = "valhalla.notification.route"

@Composable
actual fun rememberNotificationPresenter(): NotificationPresenter {
    val context = LocalContext.current
    val activity = context.findActivity()

    if (activity == null) {
        return remember {
            object : NotificationPresenter {
                override val supported: Boolean = true
                override val state: StateFlow<BlePermissionState> =
                    MutableStateFlow(BlePermissionState.NOT_REQUESTED).asStateFlow()
                override val canNotify: Boolean = false

                override fun requestPermission() = Unit

                override fun openAppSettings() = Unit

                override fun refresh() = Unit

                override fun show(
                    title: String,
                    body: String,
                    target: NotificationTarget,
                ) = Unit
            }
        }
    }

    val machine = remember(activity) { BlePermissionStateMachine() }
    val prefs: SharedPreferences =
        remember(activity) {
            activity.getSharedPreferences(NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        }
    val mutableState =
        remember(activity) {
            MutableStateFlow(activity.readNotificationState(machine, prefs))
        }
    val canNotifyState =
        remember(activity) {
            MutableStateFlow(activity.notificationsAllowed())
        }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            prefs.edit().putBoolean(REQUESTED_KEY, true).apply()
            val canAskAgain =
                activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            mutableState.value = machine.onRequestResult(granted, canAskAgain)
            canNotifyState.value = granted
        }

    val presenter =
        remember(activity, launcher) {
            val nextId = AtomicInteger(1)
            object : NotificationPresenter {
                override val supported: Boolean = true

                override val state: StateFlow<BlePermissionState> = mutableState.asStateFlow()

                override val canNotify: Boolean get() = canNotifyState.value

                override fun requestPermission() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        // Android 12 及以下没有运行时通知权限，跟随系统通知开关。
                        val allowed = activity.notificationsAllowed()
                        canNotifyState.value = allowed
                        mutableState.value =
                            if (allowed) {
                                BlePermissionState.GRANTED
                            } else {
                                BlePermissionState.PERMANENTLY_DENIED
                            }
                        return
                    }
                    if (activity.notificationsAllowed()) {
                        mutableState.value = BlePermissionState.GRANTED
                        canNotifyState.value = true
                        return
                    }
                    // 永久拒绝后 beginRequest 返回 false，绝不再弹系统窗口。
                    if (!machine.beginRequest()) {
                        mutableState.value = machine.state
                        return
                    }
                    prefs.edit().putBoolean(REQUESTED_KEY, true).apply()
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                override fun openAppSettings() {
                    val intent =
                        Intent(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { activity.startActivity(intent) }
                }

                override fun refresh() {
                    mutableState.value = activity.readNotificationState(machine, prefs)
                    canNotifyState.value = activity.notificationsAllowed()
                }

                override fun show(
                    title: String,
                    body: String,
                    target: NotificationTarget,
                ) {
                    if (!activity.notificationsAllowed()) return
                    val manager =
                        activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        manager.createNotificationChannel(
                            NotificationChannel(
                                CHANNEL_ID,
                                "Valhalla 本地提醒",
                                NotificationManager.IMPORTANCE_DEFAULT,
                            ),
                        )
                    }
                    val intent =
                        activity.packageManager
                            .getLaunchIntentForPackage(activity.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            ?.putExtra(EXTRA_NOTIFICATION_ROUTE, target.wireValue())
                    val pendingIntent =
                        intent?.let {
                            PendingIntent.getActivity(
                                activity,
                                nextId.get(),
                                it,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        }
                    val notification =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Notification.Builder(activity, CHANNEL_ID)
                        } else {
                            @Suppress("DEPRECATION")
                            Notification.Builder(activity)
                        }.setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .build()
                    runCatching { manager.notify(nextId.getAndIncrement(), notification) }
                }
            }
        }

    DisposableEffect(activity, presenter) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) presenter.refresh()
            }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            machine.cancelRequest()
        }
    }

    return presenter
}

private fun ComponentActivity.notificationsAllowed(): Boolean {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
    val runtimeAllowed =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    return runtimeAllowed && manager.areNotificationsEnabled()
}

private fun ComponentActivity.readNotificationState(
    machine: BlePermissionStateMachine,
    prefs: SharedPreferences,
): BlePermissionState {
    val allowed = notificationsAllowed()
    val hasRequestedBefore = prefs.getBoolean(REQUESTED_KEY, false)
    val canAskAgain =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            false
        }
    return machine.syncFromSystem(allowed, canAskAgain, hasRequestedBefore)
}

private fun Context.findActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
