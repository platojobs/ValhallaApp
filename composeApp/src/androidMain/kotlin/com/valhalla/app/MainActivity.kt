package com.valhalla.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.feature.notification.EXTRA_NOTIFICATION_ROUTE

class MainActivity : ComponentActivity() {

    /** 点击通知冷启动/热启动时的目标页；Compose 观察该状态后跳转。 */
    private val notificationRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidBleSupport.init(this)
        notificationRoute.value = intent?.getStringExtra(EXTRA_NOTIFICATION_ROUTE)
        // 权限请求统一由 BlePermissionController 在扫描页按状态机发起，
        // 避免启动即弹窗、被永久拒绝后反复打扰。
        setContent { App(startRoute = notificationRoute.value) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute.value = intent.getStringExtra(EXTRA_NOTIFICATION_ROUTE)
    }
}
