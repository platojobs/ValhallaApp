package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM 测试宿主没有系统文件选择器，返回固定 null 的空实现。 */
@Composable
actual fun rememberFirmwarePicker(): FirmwarePicker = remember {
    object : FirmwarePicker {
        override suspend fun pickFirmware(): PickedFirmware? = null
    }
}
