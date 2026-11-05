@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * 从 app bundle `firmware/signing-key.pem` 读取；缺失时回退到构建期注入常量
 * （静态 framework 不打包 resources 时仍可使用注入值）。
 */
@Composable
actual fun rememberFirmwareSigningKeyPem(): String? = remember {
    val path = NSBundle.mainBundle.pathForResource("signing-key", "pem", "firmware")
        ?: NSBundle.mainBundle.pathForResource("signing-key", "pem")
    path?.let {
        NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null)
    }?.takeIf { it.isNotBlank() }
        ?: BuildInjectedFirmwareKey.PEM.takeIf { it.isNotBlank() }
}
