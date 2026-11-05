package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 从 `assets/firmware/signing-key.pem` 读取公钥 PEM；文件缺失返回 null。 */
@Composable
actual fun rememberFirmwareSigningKeyPem(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}

private const val ASSET_PATH = "firmware/signing-key.pem"
