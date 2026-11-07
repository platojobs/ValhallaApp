package com.valhalla.core.config

import com.valhalla.core.protocol.message.FirmwareVersion

/**
 * 判定配置能力时使用的设备快照。
 *
 * [firmwareVersion] 为 null 表示尚未读取到固件版本；[model] 为 null 表示型号未知。
 * 二者都参与能力判定，调用方应在每次刷新设备信息后更新。
 */
data class DeviceConfigTarget(
    val firmwareVersion: FirmwareVersion? = null,
    val model: String? = null,
    val vendor: String? = null,
)

/**
 * 配置键的能力范围。
 *
 * 约定：
 * - 版本比较沿用 `major.minor.patch.build` 口径，忽略渠道（与预检/断点一致）；
 * - [models] 为空表示不限制型号，比较时忽略大小写与首尾空白；
 * - 未设置任何约束即「通用键」，在固件版本/型号未知时仍可正常使用。
 */
data class ConfigScope(
    val minVersion: FirmwareVersion? = null,
    val maxVersion: FirmwareVersion? = null,
    val models: Set<String> = emptySet(),
) {
    /** 是否声明了版本或型号约束。 */
    val restricted: Boolean
        get() = minVersion != null || maxVersion != null || models.isNotEmpty()

    companion object {
        val UNRESTRICTED: ConfigScope = ConfigScope()
    }
}

enum class ConfigCapabilityStatus {
    SUPPORTED,
    VERSION_TOO_OLD,
    VERSION_TOO_NEW,
    MODEL_UNSUPPORTED,

    /** 设备快照不足以判定（固件版本或型号缺失）。 */
    TARGET_UNKNOWN,
}

/**
 * 单个配置键在给定设备快照下的能力判定结果。
 *
 * [visible] 为 false 时 UI 隐藏该键；[reason] 为可展示的中文原因，
 * 便于在「已按能力隐藏」的汇总行里解释。
 */
data class ConfigCapabilityResult(
    val status: ConfigCapabilityStatus,
    val supported: Boolean,
    val visible: Boolean,
    val reason: String? = null,
) {
    companion object {
        val SUPPORTED: ConfigCapabilityResult =
            ConfigCapabilityResult(ConfigCapabilityStatus.SUPPORTED, supported = true, visible = true)
    }
}

/** 版本比较：仅 major.minor.patch.build，忽略 channel。 */
internal fun compareFirmwareVersions(
    a: FirmwareVersion,
    b: FirmwareVersion,
): Int {
    val major = a.major.compareTo(b.major)
    if (major != 0) return major
    val minor = a.minor.compareTo(b.minor)
    if (minor != 0) return minor
    val patch = a.patch.compareTo(b.patch)
    if (patch != 0) return patch
    return a.build.compareTo(b.build)
}

internal fun FirmwareVersion.shortText(): String = "$major.$minor.$patch (build $build)"
