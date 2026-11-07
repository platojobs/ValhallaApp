package com.valhalla.core.security

/**
 * 设备会话令牌及其过期时间。
 *
 * [expiresAtEpochMillis] 为 0 表示「设备未声明有效期」，按调用方策略处理；
 * 判断过期统一使用 [isExpired]，避免各平台自行比较时钟。
 */
data class AuthToken(
    val value: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(value.isNotEmpty()) { "令牌不能为空" }
        require(expiresAtEpochMillis >= 0) { "过期时间不能为负: $expiresAtEpochMillis" }
    }

    /** 是否声明了有效期。 */
    val hasExpiry: Boolean get() = expiresAtEpochMillis > 0

    fun isExpired(nowMillis: Long): Boolean = hasExpiry && expiresAtEpochMillis <= nowMillis
}

/** 令牌在存储层的字符串编码，用于平台 Keychain/SharedPreferences 持久化。 */
object AuthTokenCodec {
    private const val SEPARATOR = '\u0001'

    fun encode(token: AuthToken): String = "${token.value}$SEPARATOR${token.expiresAtEpochMillis}"

    fun decode(raw: String?): AuthToken? {
        if (raw.isNullOrEmpty()) return null
        val index = raw.indexOf(SEPARATOR)
        if (index <= 0) return null
        val value = raw.substring(0, index)
        val expiresAt = raw.substring(index + 1).toLongOrNull() ?: return null
        return runCatching { AuthToken(value, expiresAt) }.getOrNull()
    }
}

/**
 * 会话令牌安全存储。
 *
 * 平台实现位于 `data` 模块（Android Keystore 加密 SharedPreferences / iOS Keychain /
 * JVM 内存）。存储层不得抛出平台异常，损坏数据一律按「无令牌」处理。
 */
interface TokenStore {
    fun save(token: AuthToken)

    fun load(): AuthToken?

    fun clear()

    /**
     * 读取仍未过期的令牌；已过期时顺手清除，避免下次仍读到失效数据。
     */
    fun loadValid(nowMillis: Long): AuthToken? {
        val token = load() ?: return null
        if (token.isExpired(nowMillis)) {
            clear()
            return null
        }
        return token
    }
}

/** 内存实现：JVM 测试宿主使用，进程退出即丢失。 */
class InMemoryTokenStore : TokenStore {
    private var raw: String? = null

    override fun save(token: AuthToken) {
        raw = AuthTokenCodec.encode(token)
    }

    override fun load(): AuthToken? = AuthTokenCodec.decode(raw)

    override fun clear() {
        raw = null
    }
}
