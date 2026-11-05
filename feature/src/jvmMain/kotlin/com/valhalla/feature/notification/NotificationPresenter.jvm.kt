package com.valhalla.feature.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.valhalla.feature.ble.BlePermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** JVM 宿主（测试/预览）没有本地通知能力：开关可保存，但不会产生通知。 */
@Composable
actual fun rememberNotificationPresenter(): NotificationPresenter =
    remember {
        object : NotificationPresenter {
            override val supported: Boolean = false
            override val state: StateFlow<BlePermissionState> =
                MutableStateFlow(BlePermissionState.NOT_REQUESTED).asStateFlow()
            override val canNotify: Boolean = false
            override fun requestPermission() = Unit
            override fun openAppSettings() = Unit
            override fun refresh() = Unit
            override fun show(title: String, body: String) = Unit
        }
    }
