package com.valhalla.core.logging

/**
 * 面向自由文本（设备日志、固件回传、错误消息）的字段级脱敏规则。
 *
 * 与 [PacketLogRedaction]（按协议字段脱敏）互补：本对象只处理「无法按结构定位」
 * 的字符串。规则表与 `docs/privacy.md` 保持同步。
 *
 * 设计原则：
 * - **默认拒绝**：宁可在排障时少看到几位字符，也不让标识/密钥进入日志、导出与上报；
 * - **可验证**：每条规则都有对应的单元测试，测试直接断言敏感子串不出现在输出中；
 * - **幂等**：重复执行不会继续套娃，便于入库前 + 导出前二次兜底。
 */
object SensitiveTextRedaction {
    /** 统一占位符。 */
    const val MASK: String = "***"

    /** PEM 块（公钥、证书、私钥）整体替换，避免 Base64 内容被误认为普通文本。 */
    private val PEM_BLOCK =
        Regex(
            pattern = "-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----",
            option = RegexOption.IGNORE_CASE,
        )

    /**
     * 带标签的敏感值：`token=xxx`、`credential: xxx`、`serialNumber=xxx` 等。
     *
     * 只保留标签本身，值替换为 [MASK]；支持引号包裹的值。
     */
    private val LABELED_VALUE =
        Regex(
            pattern =
                "(?i)(token|credential|secret|password|passwd|signature|nonce|" +
                    "serial(?:number)?|device[_ -]?id|auth[_ -]?key)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
        )

    /** MAC 地址：保留前 2 位，其余掩码。 */
    private val MAC_ADDRESS =
        Regex(
            pattern = "(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b",
        )

    /** UUID：不可逆哈希，保留可区分性。 */
    private val UUID =
        Regex(
            pattern = "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
        )

    /** 32 位以上连续十六进制串（密钥片段、摘要、令牌）。 */
    private val LONG_HEX =
        Regex(
            pattern = "(?i)\\b[0-9a-f]{32,}\\b",
        )

    /** 40 位以上无标签 Base64 串（疑似密钥/令牌）。 */
    private val LONG_BASE64 =
        Regex(
            pattern = "\\b[A-Za-z0-9+/]{40,}={0,2}\\b",
        )

    /** 对任意自由文本执行全部规则；null 安全。 */
    fun redact(text: String): String {
        var value = text
        value = PEM_BLOCK.replace(value, "[REDACTED PEM]")
        value =
            LABELED_VALUE.replace(value) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$MASK"
            }
        value =
            MAC_ADDRESS.replace(value) { match ->
                match.value.take(2) + ":" + List(5) { "**" }.joinToString(":")
            }
        value = UUID.replace(value) { match -> PacketLogRedaction.hashDeviceId(match.value) }
        value = LONG_HEX.replace(value, "[REDACTED KEY]")
        value = LONG_BASE64.replace(value, "[REDACTED B64]")
        return value
    }

    /** 是否已不含已知敏感形态；用于测试与投递前自检。 */
    fun isRedacted(text: String): Boolean = redact(text) == text

    /** 单行摘要，避免多行文本破坏日志格式。 */
    fun redactInline(text: String): String = redact(text).replace('\n', ' ').replace('\r', ' ')
}
