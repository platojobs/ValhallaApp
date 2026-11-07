package com.valhalla.data.share

import java.util.TimeZone

/** JVM 测试宿主没有系统分享面板，返回不可用；时间区仍按运行环境返回。 */
actual fun createTextSharer(): TextSharer =
    object : TextSharer {
        override suspend fun share(payload: ShareableText): ShareResult = ShareResult(ShareStatus.UNAVAILABLE, "当前平台不支持系统分享")
    }

actual fun currentTimeZoneOffsetMinutes(epochMillis: Long): Int = TimeZone.getDefault().getOffset(epochMillis) / 60_000
