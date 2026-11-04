package com.valhalla.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.valhalla.data.ble.AndroidBleSupport

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidBleSupport.init(this)
        // 权限请求统一由 BlePermissionController 在扫描页按状态机发起，
        // 避免启动即弹窗、被永久拒绝后反复打扰。
        setContent { App() }
    }
}
