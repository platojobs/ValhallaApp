package com.valhalla.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM 测试宿主没有系统保存面板，返回不可用。 */
@Composable
actual fun rememberFileSaver(): FileSaver = remember {
    object : FileSaver {
        override suspend fun save(file: SaveableFile): FileSaveResult =
            FileSaveResult(FileSaveStatus.UNAVAILABLE, "当前平台不支持系统文件保存")
    }
}
