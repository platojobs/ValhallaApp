package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import com.valhalla.core.firmware.FirmwareSource

/** 用户选中的固件文件。source 为只读、可分块读取的句柄，由选择控制器负责关闭。 */
data class PickedFirmware(
    val name: String,
    val source: FirmwareSource,
    /** 平台持久引用（Android content URI / iOS 文件路径）；仅用于固件库复用，不缓存二进制。 */
    val sourcePath: String? = null,
)

/** 统一的固件选择接口：Android 走 SAF，iOS 走 UIDocumentPicker。 */
interface FirmwarePicker {
    /** 打开系统文件选择器；用户取消时返回 null。 */
    suspend fun pickFirmware(): PickedFirmware?

    /**
     * 按固件库记录中的路径重新打开文件；文件被删除、移动或权限失效时返回 null。
     * 默认实现不支持按路径重开（JVM 预览/测试宿主）。
     */
    suspend fun openFirmware(sourcePath: String): PickedFirmware? = null
}

/** 平台文件选择器工厂；必须在 Compose 作用域内调用（Android 需要 ActivityResult 注册）。 */
@Composable
expect fun rememberFirmwarePicker(): FirmwarePicker
