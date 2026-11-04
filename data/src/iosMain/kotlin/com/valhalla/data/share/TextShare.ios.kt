package com.valhalla.data.share

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.localTimeZone
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPopoverPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

actual fun createTextSharer(): TextSharer = IosTextSharer()

@Suppress("UNUSED_PARAMETER")
actual fun currentTimeZoneOffsetMinutes(epochMillis: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate()).toInt() / 60

/**
 * 先写入临时目录，再把文件 URL 交给 `UIActivityViewController`。
 * iPad 必须提供 popover 锚点，否则会抛异常。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosTextSharer : TextSharer {

    override suspend fun share(payload: ShareableText): ShareResult {
        val presenter = topViewController()
            ?: return ShareResult(ShareStatus.UNAVAILABLE, "未找到可展示分享面板的页面")
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + payload.fileName
        val written = writeTextFile(payload.content, path)
        if (!written) {
            return ShareResult(ShareStatus.FAILED, "写入临时文件失败")
        }
        val url = NSURL.fileURLWithPath(path)
        val controller = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        configurePopover(controller.popoverPresentationController, presenter)
        presenter.presentViewController(controller, animated = true, completion = null)
        return ShareResult(ShareStatus.SHARED)
    }

    /** iPad 上分享面板以 popover 呈现，缺少锚点会崩溃；这里以页面中心作为锚点。 */
    private fun configurePopover(popover: UIPopoverPresentationController?, presenter: UIViewController) {
        if (popover == null) return
        popover.sourceView = presenter.view
        popover.sourceRect = presenter.view.bounds
        popover.permittedArrowDirections = 0u
    }
}

/**
 * 把 UTF-8 文本写成 NSData 再落盘。
 * Kotlin `String` 不能直接转成 `NSString` 调用 `writeToFile`（编译器会提示 cast 永不成立），
 * 因此走字节数组 pin 到 NSData。
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(content: String, path: String): Boolean {
    val bytes = content.encodeToByteArray()
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
    }
    return data.writeToFile(path, atomically = true)
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
