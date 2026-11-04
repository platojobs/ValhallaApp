package com.valhalla.feature.firmware

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 一次 SAF 选择的结果回调槽。 */
private class PendingPick {
    var deferred: CompletableDeferred<Uri?>? = null
}

@Composable
actual fun rememberFirmwarePicker(): FirmwarePicker {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val pending = remember { PendingPick() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val deferred = pending.deferred
        pending.deferred = null
        deferred?.complete(uri)
    }

    return remember(resolver, launcher, pending) {
        object : FirmwarePicker {
            override suspend fun pickFirmware(): PickedFirmware? {
                if (pending.deferred != null) return null
                val deferred = CompletableDeferred<Uri?>()
                pending.deferred = deferred
                launcher.launch(arrayOf("application/octet-stream", "*/*"))
                val uri = deferred.await() ?: return null
                val name = queryDisplayName(resolver, uri) ?: "firmware.bin"
                return PickedFirmware(name = name, source = ContentUriFirmwareSource(resolver, uri))
            }
        }
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

/**
 * 通过 ContentResolver 打开的只读固件源。
 *
 * 使用 `FileChannel.position(offset)` 做随机读取，保证 OTA 分片与预检可以复用同一句柄，
 * 且不会把整包一次性读入内存。读取在 [Dispatchers.IO] 上执行。
 */
private class ContentUriFirmwareSource(
    resolver: ContentResolver,
    private val uri: Uri,
) : FirmwareSource {

    private val mutex = Mutex()
    private val descriptor: ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开固件文件: $uri")
    private val stream: FileInputStream = FileInputStream(descriptor.fileDescriptor)
    private val channel: FileChannel = stream.channel

    override val size: Long =
        descriptor.statSize.takeIf { it > 0 } ?: runCatching { channel.size() }.getOrDefault(0L)

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw FirmwareException.ReadFailure(offset, length)
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    channel.position(offset)
                    val buffer = ByteBuffer.allocate(length)
                    while (buffer.hasRemaining()) {
                        val read = channel.read(buffer)
                        if (read <= 0) break
                    }
                    if (buffer.position() < length) {
                        throw FirmwareException.ReadFailure(offset, length)
                    }
                    buffer.array()
                } catch (error: IOException) {
                    throw FirmwareException.ReadFailure(offset, length, error)
                }
            }
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}
