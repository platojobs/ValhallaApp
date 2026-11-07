package com.valhalla.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

@Composable
actual fun rememberFileSaver(): FileSaver = remember { IosFileSaver() }

/**
 * 先写入 App 临时目录，再用 `UIDocumentPickerViewController(forExportingURLs:asCopy:)`
 * 让用户选择保存位置；用户取消或未选路径时返回 [FileSaveStatus.CANCELLED]。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosFileSaver : FileSaver {
    private var delegate: ExportDelegate? = null

    override suspend fun save(file: SaveableFile): FileSaveResult {
        if (delegate != null) {
            return FileSaveResult(FileSaveStatus.FAILED, "已有保存任务在进行")
        }
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + file.fileName
        if (!writeTextFile(file.content, path)) {
            return FileSaveResult(FileSaveStatus.FAILED, "写入临时文件失败")
        }
        val url = NSURL.fileURLWithPath(path)
        val deferred = CompletableDeferred<Boolean>()
        val pickerDelegate = ExportDelegate { saved -> deferred.complete(saved) }
        delegate = pickerDelegate

        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        picker.delegate = pickerDelegate

        val presenter = topViewController()
        if (presenter == null) {
            delegate = null
            return FileSaveResult(FileSaveStatus.UNAVAILABLE, "未找到可展示文件导出面板的页面")
        }
        presenter.presentViewController(picker, animated = true, completion = null)

        val saved = deferred.await()
        delegate = null
        return if (saved) FileSaveResult(FileSaveStatus.SAVED) else FileSaveResult(FileSaveStatus.CANCELLED)
    }
}

/** `UIDocumentPickerViewController` 回调包装；选中文档位置即视为保存成功。 */
private class ExportDelegate(
    private val onResult: (Boolean) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onResult(true)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onResult(false)
    }
}

/** 逐级找到当前顶层控制器，避免从已被 dismiss 的页面弹出保存面板。 */
private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

/** 把 UTF-8 文本写成 NSData 再落盘（Kotlin String 不能直接调用 NSString 的 writeToFile）。 */
@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(
    content: String,
    path: String,
): Boolean {
    val bytes = content.encodeToByteArray()
    val data =
        if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
        }
    return data.writeToFile(path, atomically = true)
}
