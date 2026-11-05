@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/** 从 app bundle `firmware/signing-key.pem` 读取；缺失返回 null。 */
@Composable
actual fun rememberFirmwareSigningKeyPem(): String? = remember {
    val path = NSBundle.mainBundle.pathForResource("signing-key", "pem", "firmware")
        ?: NSBundle.mainBundle.pathForResource("signing-key", "pem")
    path?.let {
        NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null)
    }
}
