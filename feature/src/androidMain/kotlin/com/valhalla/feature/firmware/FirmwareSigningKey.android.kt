package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 从 `assets/firmware/signing-key.pem` 读取公钥 PEM；资源缺失时回退到构建期注入常量。
 * 两者都缺失返回 null（预检策略回退 `WARN`）。
 */
@Composable
actual fun rememberFirmwareSigningKeyPem(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: BuildInjectedFirmwareKey.PEM.takeIf { it.isNotBlank() }
    }
}

private const val ASSET_PATH = "firmware/signing-key.pem"
