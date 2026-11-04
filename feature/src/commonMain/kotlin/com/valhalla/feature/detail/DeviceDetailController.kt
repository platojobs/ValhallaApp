package com.valhalla.feature.detail

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.logging.DeviceLogger
import com.valhalla.core.logging.InMemoryDeviceLogger
import com.valhalla.core.logging.LogLevel
import com.valhalla.core.protocol.message.AuthMode
import com.valhalla.core.protocol.message.AuthRequest
import com.valhalla.core.protocol.message.AuthResponse
import com.valhalla.core.protocol.message.SignedAuthCredential
import com.valhalla.core.protocol.message.BatteryResponse
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceInfoResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.FirmwareVersion
import com.valhalla.core.protocol.message.FirmwareVersionResponse
import com.valhalla.core.protocol.message.GetBatteryRequest
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.GetDeviceInfoRequest
import com.valhalla.core.protocol.message.GetFirmwareVersionRequest
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.core.state.DeviceEvent
import com.valhalla.core.state.DeviceState
import com.valhalla.core.state.DeviceStateMachine
import com.valhalla.core.security.AuthSignatureMessage
import com.valhalla.core.security.AuthSigner
import com.valhalla.core.security.AuthToken
import com.valhalla.core.security.TokenStore
import com.valhalla.feature.toFailureInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 设备详情页状态。 */
data class DeviceDetailState(
    val deviceId: String,
    val deviceState: DeviceState = DeviceState.CONNECTED,
    val firmwareVersion: String? = null,
    val firmware: FirmwareVersion? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val voltageMillivolts: Int? = null,
    val deviceInfo: DeviceInfoResponse? = null,
    val configuration: Map<String, ByteArray> = emptyMap(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val errorCode: DeviceStatus? = null,
)

/**
 * 设备详情控制器：鉴权、设备信息/电量/固件读取、配置读写与断开流程。
 *
 * 所有上下行都经 [ConcurrentCommandDispatcher] 走强类型请求/应答模型，
 * 控制层不再直接拼装裸字节；设备错误码经 [DeviceStatus] 统一翻译为 UI 文案。
 * 状态迁移仍由 [DeviceStateMachine] 集中管理。
 */
@OptIn(ExperimentalTime::class)
class DeviceDetailController(
    private val dispatcher: ConcurrentCommandDispatcher,
    val deviceId: String,
    private val logger: DeviceLogger = InMemoryDeviceLogger(),
    private val disconnectLink: suspend () -> Unit = {},
    /** 会话令牌安全存储；为 null 时仅保留在内存中（测试/旧调用）。 */
    private val tokenStore: TokenStore? = null,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val machine = DeviceStateMachine(initialState = DeviceState.CONNECTED)
    private val mutableState = MutableStateFlow(
        DeviceDetailState(deviceId = deviceId, deviceState = machine.state),
    )
    val state: StateFlow<DeviceDetailState> = mutableState.asStateFlow()

    /** 鉴权成功后的会话令牌，供后续安全操作使用。 */
    var sessionToken: String? = null
        private set

    /** 令牌有效期（秒）。 */
    var tokenExpiresInSeconds: Long = 0
        private set

    /** 挑战模式下设备返回的挑战值。 */
    var serverChallenge: ByteArray? = null
        private set

    init {
        log(LogLevel.INFO, "链路已建立：$deviceId")
    }

    /** 发送 AUTH 鉴权。成功进入 READY，失败进入 FAULT 并把错误码暴露到 UI 状态。 */
    suspend fun authenticate(token: String, mode: AuthMode = AuthMode.TOKEN): Boolean {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)
        transition(DeviceEvent.AuthenticationStarted)
        log(LogLevel.INFO, "开始鉴权（模式 ${mode.name}）")

        return try {
            val response = dispatcher.execute(AuthRequest(mode, token.encodeToByteArray()))
            if (response !is AuthResponse) {
                error("鉴权应答类型异常: ${response::class.simpleName}")
            }
            registerSession(response)
            transition(DeviceEvent.AuthSucceeded)
            log(
                LogLevel.INFO,
                "鉴权成功，令牌有效期 ${response.expiresInSeconds}s" +
                    if (response.hasChallenge) "，已收到服务端挑战" else "",
            )
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            transition(DeviceEvent.AuthFailed(info.message))
            log(LogLevel.ERROR, "鉴权失败：${info.message}")
            mutableState.value = mutableState.value.copy(
                error = "鉴权失败：${info.message}",
                errorCode = info.status,
            )
            false
        }
    }

    /**
     * 发起挑战请求并返回设备下发的挑战。
     *
     * 用于签名鉴权的第一步；[clientNonce] 为客户端随机数，会同时参与后续签名。
     * 失败时写入与 [authenticate] 一致的错误状态并返回 null。
     */
    suspend fun requestChallenge(clientNonce: ByteArray): ByteArray? {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)
        log(LogLevel.INFO, "请求鉴权挑战（nonce ${clientNonce.size} 字节）")
        return try {
            val response = dispatcher.execute(AuthRequest(AuthMode.CHALLENGE, clientNonce))
            if (response !is AuthResponse) {
                error("挑战应答类型异常: ${response::class.simpleName}")
            }
            if (!response.hasChallenge) {
                error("设备未返回挑战")
            }
            serverChallenge = response.challenge
            log(LogLevel.INFO, "已收到服务端挑战（${response.challenge.size} 字节）")
            response.challenge
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            log(LogLevel.ERROR, "请求挑战失败：${info.message}")
            mutableState.value = mutableState.value.copy(
                error = "请求挑战失败：${info.message}",
                errorCode = info.status,
            )
            null
        }
    }

    /**
     * 签名挑战应答鉴权。
     *
     * 流程：无挑战时先执行 [requestChallenge] 获取；随后把
     * `challenge + clientNonce + deviceDigest` 交给 [signer] 签名，以 `AuthMode.SIGNED`
     * 发送凭据。设备验签通过后返回令牌，失败则进入 FAULT 并保留错误码。
     */
    suspend fun authenticateSigned(
        signer: AuthSigner,
        clientNonce: ByteArray,
        deviceDigest: String,
        /** 显式传入 [requestChallenge] 得到的挑战；为 null 时自动获取一次新挑战。 */
        challenge: ByteArray? = null,
    ): Boolean {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)
        transition(DeviceEvent.AuthenticationStarted)
        log(LogLevel.INFO, "开始签名鉴权（${signer.algorithm}）")

        return try {
            val activeChallenge = challenge ?: run {
                val response = dispatcher.execute(AuthRequest(AuthMode.CHALLENGE, clientNonce))
                if (response !is AuthResponse) {
                    error("挑战应答类型异常: ${response::class.simpleName}")
                }
                if (!response.hasChallenge) {
                    error("设备未返回挑战")
                }
                response.challenge
            }
            serverChallenge = activeChallenge

            val credential = SignedAuthCredential(
                challenge = activeChallenge,
                clientNonce = clientNonce,
                deviceDigest = deviceDigest,
                signature = signer.sign(
                    AuthSignatureMessage.canonical(activeChallenge, clientNonce, deviceDigest),
                ),
            )
            val response = dispatcher.execute(AuthRequest(AuthMode.SIGNED, credential.encode()))
            if (response !is AuthResponse) {
                error("鉴权应答类型异常: ${response::class.simpleName}")
            }
            registerSession(response)
            // 保留本次签名使用的挑战，便于上层诊断/二次签名；令牌与过期时间已由 registerSession 处理。
            serverChallenge = activeChallenge
            transition(DeviceEvent.AuthSucceeded)
            log(LogLevel.INFO, "签名鉴权成功，令牌有效期 ${response.expiresInSeconds}s")
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            transition(DeviceEvent.AuthFailed(info.message))
            log(LogLevel.ERROR, "签名鉴权失败：${info.message}")
            mutableState.value = mutableState.value.copy(
                error = "鉴权失败：${info.message}",
                errorCode = info.status,
            )
            false
        }
    }

    /** 读取安全存储中仍未过期的令牌；无存储或已过期时返回 null（过期会被清除）。 */
    fun loadPersistedToken(): AuthToken? = tokenStore?.loadValid(clockMillis())

    /** 清除安全存储中的令牌（例如用户手动退出登录）。 */
    fun clearPersistedToken() {
        tokenStore?.clear()
    }

    /**
     * 记录鉴权结果：内存字段始终更新；配置了安全存储时按设备有效期写入并统一过期判断。
     */
    private fun registerSession(response: AuthResponse) {
        sessionToken = response.token
        tokenExpiresInSeconds = response.expiresInSeconds
        serverChallenge = response.challenge
        val store = tokenStore ?: return
        if (response.token.isEmpty()) return
        val expiresAt = if (response.expiresInSeconds > 0) {
            clockMillis() + response.expiresInSeconds * 1_000
        } else {
            0L
        }
        store.save(AuthToken(value = response.token, expiresAtEpochMillis = expiresAt))
    }

    /**
     * 并行读取设备信息、固件版本与电量。
     *
     * 三个请求经同一个调度器并发发出，应答按 sequence 精确回到各自的 await，
     * 因此设备乱序回复也不会串包；单项失败不影响其它项，全部失败才报整体错误。
     */
    suspend fun refreshInfo() {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)

        val (infoAttempt, firmwareAttempt, batteryAttempt) = supervisorScope {
            val info = async { attempt { dispatcher.execute(GetDeviceInfoRequest()) as DeviceInfoResponse } }
            val firmware = async {
                attempt { dispatcher.execute(GetFirmwareVersionRequest()) as FirmwareVersionResponse }
            }
            val battery = async { attempt { dispatcher.execute(GetBatteryRequest()) as BatteryResponse } }
            Triple(info.await(), firmware.await(), battery.await())
        }

        val info = infoAttempt.valueOrNull()
        val firmware = firmwareAttempt.valueOrNull()
        val battery = batteryAttempt.valueOrNull()

        val failures = listOf(infoAttempt, firmwareAttempt, batteryAttempt)
            .mapNotNull { (it as? Attempt.Failure)?.error }
        failures.forEach { log(LogLevel.WARN, "信息读取失败：${it.toFailureInfo().message}") }

        mutableState.value = mutableState.value.copy(
            deviceInfo = info ?: mutableState.value.deviceInfo,
            firmware = firmware?.version ?: mutableState.value.firmware,
            firmwareVersion = firmware?.version?.toString() ?: mutableState.value.firmwareVersion,
            batteryPercent = battery?.percent ?: mutableState.value.batteryPercent,
            charging = battery?.charging ?: mutableState.value.charging,
            voltageMillivolts = battery?.voltageMillivolts ?: mutableState.value.voltageMillivolts,
            error = if (info == null && firmware == null && battery == null) {
                "设备信息读取失败"
            } else {
                null
            },
            errorCode = failures.firstOrNull()?.toFailureInfo()?.status,
        )

        if (info != null || firmware != null || battery != null) {
            log(
                LogLevel.INFO,
                "信息刷新：型号=${info?.model ?: "未知"}，" +
                    "固件=${firmware?.version ?: "未知"}，电量=${battery?.percent ?: "未知"}",
            )
        }
    }

    /** 读取配置项，失败返回 null 并写入可读错误。 */
    suspend fun readConfig(key: String): ByteArray? {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)
        return try {
            val response = dispatcher.execute(GetConfigRequest(key))
            if (response !is ConfigResponse) {
                error("配置应答类型异常: ${response::class.simpleName}")
            }
            mutableState.value = mutableState.value.copy(
                configuration = mutableState.value.configuration + (response.key to response.value),
            )
            log(LogLevel.INFO, "读取配置 ${response.key} 成功（${response.value.size} 字节）")
            response.value
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            log(LogLevel.WARN, "读取配置 $key 失败：${info.message}")
            mutableState.value = mutableState.value.copy(
                error = "读取配置失败：${info.message}",
                errorCode = info.status,
            )
            null
        }
    }

    /** 写入配置项，成功返回 true。 */
    suspend fun writeConfig(key: String, value: ByteArray): Boolean {
        mutableState.value = mutableState.value.copy(error = null, errorCode = null)
        return try {
            val response = dispatcher.execute(SetConfigRequest(key, value))
            if (response !is SetConfigResponse) {
                error("写配置应答类型异常: ${response::class.simpleName}")
            }
            mutableState.value = mutableState.value.copy(
                configuration = mutableState.value.configuration + (key to value),
            )
            log(LogLevel.INFO, "写入配置 $key 成功（${value.size} 字节）")
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            log(LogLevel.WARN, "写入配置 $key 失败：${info.message}")
            mutableState.value = mutableState.value.copy(
                error = "写入配置失败：${info.message}",
                errorCode = info.status,
            )
            false
        }
    }

    /** 主动断开，状态回落 DISCONNECTED。 */
    suspend fun disconnect() {
        transition(DeviceEvent.DisconnectRequested)
        try {
            disconnectLink()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log(LogLevel.WARN, "断开时出现异常：${error.message}")
        } finally {
            transition(DeviceEvent.Disconnected)
            log(LogLevel.INFO, "链路已断开")
        }
    }

    fun deviceLogger(): DeviceLogger = logger

    private sealed interface Attempt<out T> {
        data class Success<T>(val value: T) : Attempt<T>
        data class Failure(val error: Throwable) : Attempt<Nothing>
    }

    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> =
        try {
            Attempt.Success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Attempt.Failure(error)
        }

    private fun <T> Attempt<T>.valueOrNull(): T? = (this as? Attempt.Success)?.value

    private fun transition(event: DeviceEvent) {
        machine.handle(event)
        mutableState.value = mutableState.value.copy(deviceState = machine.state)
    }

    private fun log(level: LogLevel, message: String) {
        logger.log(level, "DeviceDetail", message)
        mutableState.value = mutableState.value.copy(
            logs = logger.entries().map { it.format() },
        )
    }
}
