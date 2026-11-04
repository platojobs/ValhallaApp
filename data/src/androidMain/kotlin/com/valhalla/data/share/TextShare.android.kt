package com.valhalla.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.valhalla.data.ble.AndroidBleSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

private const val EXPORT_DIR = "valhalla-exports"

actual fun createTextSharer(): TextSharer = AndroidTextSharer(AndroidBleSupport.requireContext())

actual fun currentTimeZoneOffsetMinutes(epochMillis: Long): Int =
    TimeZone.getDefault().getOffset(epochMillis) / 60_000

/**
 * 写入 App cache 下的导出目录，再通过 [FileProvider] 生成可授予读权限的 content:// URI。
 * 分享面板用 `ACTION_CHOOSER` 包裹，避免暴露未处理的 `ACTION_SEND`。
 */
private class AndroidTextSharer(private val context: Context) : TextSharer {

    override suspend fun share(payload: ShareableText): ShareResult = withContext(Dispatchers.IO) {
        try {
            val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val file = File(directory, payload.fileName)
            file.writeText(payload.content)
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = payload.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "导出报文日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            ShareResult(ShareStatus.SHARED)
        } catch (error: Throwable) {
            ShareResult(ShareStatus.FAILED, error.message ?: "分享失败")
        }
    }
}
