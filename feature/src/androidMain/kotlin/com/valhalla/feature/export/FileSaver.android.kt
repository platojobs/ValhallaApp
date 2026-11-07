package com.valhalla.feature.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次 SAF 保存的结果回调槽。 */
private class PendingSave {
    var deferred: CompletableDeferred<Uri?>? = null
}

@Composable
actual fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    val pending = remember { PendingSave() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val deferred = pending.deferred
            pending.deferred = null
            val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
            deferred?.complete(uri)
        }

    return remember(context, launcher, pending) {
        object : FileSaver {
            override suspend fun save(file: SaveableFile): FileSaveResult {
                if (pending.deferred != null) {
                    return FileSaveResult(FileSaveStatus.FAILED, "已有保存任务在进行")
                }
                val deferred = CompletableDeferred<Uri?>()
                pending.deferred = deferred
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = file.mimeType
                        putExtra(Intent.EXTRA_TITLE, file.fileName)
                    }
                launcher.launch(intent)
                val uri =
                    deferred.await()
                        ?: return FileSaveResult(FileSaveStatus.CANCELLED)

                return withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            stream.write(file.content.encodeToByteArray())
                        } ?: return@withContext FileSaveResult(FileSaveStatus.FAILED, "无法打开目标文件")
                        FileSaveResult(FileSaveStatus.SAVED)
                    } catch (error: Throwable) {
                        FileSaveResult(FileSaveStatus.FAILED, error.message ?: "写入文件失败")
                    }
                }
            }
        }
    }
}
