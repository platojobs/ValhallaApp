package com.valhalla.feature.export

import androidx.compose.runtime.Composable

/** 待写入系统文件选择器的内容；[mimeType] 决定 Android 的文档类型过滤。 */
data class SaveableFile(
    val fileName: String,
    val content: String,
    val mimeType: String,
)

enum class FileSaveStatus {
    SAVED,
    CANCELLED,
    UNAVAILABLE,
    FAILED,
}

data class FileSaveResult(
    val status: FileSaveStatus,
    val message: String? = null,
)

/**
 * 统一的「导出为文件」能力：Android 走 SAF `ACTION_CREATE_DOCUMENT`，
 * iOS 走 `UIDocumentPickerViewController(forExporting:)`；与「分享」是两个独立入口。
 */
interface FileSaver {
    /** 打开系统保存面板；用户取消时返回 [FileSaveStatus.CANCELLED]。 */
    suspend fun save(file: SaveableFile): FileSaveResult
}

/** 平台文件保存器工厂；必须在 Compose 作用域内调用（Android 需要 ActivityResult 注册）。 */
@Composable
expect fun rememberFileSaver(): FileSaver
