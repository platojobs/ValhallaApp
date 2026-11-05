package com.valhalla.feature.firmware

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * JVM 宿主从 classpath `firmware/signing-key.pem` 读取；缺失时回退到构建期注入常量。
 * 两者都缺失返回 null（预检策略回退 `WARN`）。
 */
@Composable
actual fun rememberFirmwareSigningKeyPem(): String? = remember {
    runCatching {
        val loader = Thread.currentThread().contextClassLoader
        val stream = loader?.getResourceAsStream(RESOURCE_PATH)
            ?: ClassLoader.getSystemResourceAsStream(RESOURCE_PATH)
        stream?.use { it.bufferedReader().readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: BuildInjectedFirmwareKey.PEM.takeIf { it.isNotBlank() }
}

private const val RESOURCE_PATH = "firmware/signing-key.pem"
