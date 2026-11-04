package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import com.valhalla.core.firmware.FirmwareSource

/** 用户选中的固件文件。source 为只读、可分块读取的句柄，由选择控制器负责关闭。 */
data class PickedFirmware(
    val name: String,
    val source: FirmwareSource,
)

/** 统一的固件选择接口：Android 走 SAF，iOS 走 UIDocumentPicker。 */
interface FirmwarePicker {
    /** 打开系统文件选择器；用户取消时返回 null。 */
    suspend fun pickFirmware(): PickedFirmware?
}

/** 平台文件选择器工厂；必须在 Compose 作用域内调用（Android 需要 ActivityResult 注册）。 */
@Composable
expect fun rememberFirmwarePicker(): FirmwarePicker
