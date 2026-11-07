package com.valhalla.feature.firmware

/**
 * 构建期公钥来源选择（与 `feature/build.gradle.kts` 中 `generateFirmwareKey` 的优先级一致）。
 *
 * 空字符串按「未配置」处理，因此 CI 中未设置的 secret 不会覆盖 Gradle property。
 */
object FirmwareKeyInjection {
    fun select(
        environmentValue: String?,
        gradlePropertyValue: String?,
    ): String? =
        environmentValue?.takeIf { it.isNotBlank() }
            ?: gradlePropertyValue?.takeIf { it.isNotBlank() }
}
