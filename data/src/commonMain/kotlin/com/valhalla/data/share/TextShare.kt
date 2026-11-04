package com.valhalla.data.share

/** 待分享的纯文本文件；[mimeType] 交给平台分享面板识别。 */
data class ShareableText(
    val fileName: String,
    val content: String,
    val mimeType: String = "text/plain",
)

enum class ShareStatus {
    SHARED,
    UNAVAILABLE,
    FAILED,
}

data class ShareResult(
    val status: ShareStatus,
    val message: String? = null,
)

/** 统一的文本分享接口：Android 走 ACTION_SEND + FileProvider，iOS 走 UIActivityViewController。 */
interface TextSharer {
    suspend fun share(payload: ShareableText): ShareResult
}

/** 平台分享器工厂；Android 依赖 `AndroidBleSupport.init` 提供的 Application Context。 */
expect fun createTextSharer(): TextSharer

/** 当前时区相对 UTC 的偏移（分钟），用于日志时间戳本地化。 */
expect fun currentTimeZoneOffsetMinutes(epochMillis: Long): Int
