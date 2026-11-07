package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.valhalla.core.firmware.FirmwareException
import com.valhalla.core.firmware.FirmwareSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat

@Composable
actual fun rememberFirmwarePicker(): FirmwarePicker = remember { IosFirmwarePicker() }

/** iOS 文件选择器：导入模式会先把文件复制到 App 沙盒，可直接按路径随机读取。 */
private class IosFirmwarePicker : FirmwarePicker {
    private var delegate: PickerDelegate? = null

    override suspend fun pickFirmware(): PickedFirmware? {
        if (delegate != null) return null
        val deferred = CompletableDeferred<NSURL?>()
        val pickerDelegate = PickerDelegate { url -> deferred.complete(url) }
        delegate = pickerDelegate

        val controller =
            UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
                asCopy = true,
            )
        controller.delegate = pickerDelegate

        val presenter = topViewController()
        if (presenter == null) {
            delegate = null
            return null
        }
        presenter.presentViewController(controller, animated = true, completion = null)

        val url = deferred.await()
        delegate = null
        if (url == null) return null

        val path = url.path ?: return null
        val source = runCatching { PosixFirmwareSource(path) }.getOrNull() ?: return null
        return PickedFirmware(
            name = url.lastPathComponent ?: "firmware.bin",
            source = source,
            sourcePath = path,
        )
    }

    override suspend fun openFirmware(sourcePath: String): PickedFirmware? {
        if (sourcePath.isBlank()) return null
        return runCatching {
            PickedFirmware(
                name = sourcePath.substringAfterLast('/').ifBlank { "firmware.bin" },
                source = PosixFirmwareSource(sourcePath),
                sourcePath = sourcePath,
            )
        }.getOrNull()
    }
}

/** UIDocumentPicker 回调包装；回调在主队列执行。 */
private class PickerDelegate(
    private val onResult: (NSURL?) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onResult(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(null)
    }
}

/** 逐级找到当前顶层控制器，避免从被 dismiss 的页面上弹出选择器。 */
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

/**
 * 基于 POSIX `pread` 的只读固件源：按偏移分段读取，线程安全，不把整包读入内存。
 * 文件在导入模式下已位于 App 沙盒，无需 security-scoped resource 访问。
 */
@OptIn(ExperimentalForeignApi::class)
private class PosixFirmwareSource(
    path: String,
) : FirmwareSource {
    private val mutex = Mutex()
    private val descriptor: Int = open(path, O_RDONLY)

    init {
        if (descriptor < 0) throw FirmwareException.ReadFailure(0, 0)
    }

    override val size: Long =
        memScoped {
            val info = alloc<stat>()
            if (fstat(descriptor, info.ptr) != 0) 0L else info.st_size.toLong()
        }

    override suspend fun read(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw FirmwareException.ReadFailure(offset, length)
        }
        return mutex.withLock {
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                var done = 0
                while (done < length) {
                    val read =
                        pread(
                            descriptor,
                            pinned.addressOf(done),
                            (length - done).toULong(),
                            offset + done,
                        )
                    if (read <= 0) throw FirmwareException.ReadFailure(offset + done, length - done)
                    done += read.toInt()
                }
            }
            bytes
        }
    }

    override fun close() {
        close(descriptor)
    }
}
