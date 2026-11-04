package com.valhalla.feature.config

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCapabilityResult
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ConfigKeySpec
import com.valhalla.core.config.ConfigValidation
import com.valhalla.core.config.DeviceConfigTarget
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.toFailureInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 单个配置项的可编辑状态。 */
data class ConfigFieldState(
    val spec: ConfigKeySpec,
    val text: String = "",
    val originalText: String = "",
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loadError: String? = null,
    val validationError: String? = null,
    val lastSavedAtEpochMillis: Long? = null,
    /** 基于当前设备快照的能力判定；不支持时 UI 隐藏该键。 */
    val capability: ConfigCapabilityResult = ConfigCapabilityResult.SUPPORTED,
) {
    val dirty: Boolean get() = text != originalText
    val editable: Boolean get() = spec.editable && capability.supported
    val visible: Boolean get() = capability.visible
}

/** 配置区整体状态。 */
data class ConfigEditorState(
    val fields: List<ConfigFieldState> = emptyList(),
    val loading: Boolean = false,
    val savingKey: String? = null,
    val message: UiMessage? = null,
    val error: UiMessage? = null,
    val errorCode: DeviceStatus? = null,
    /** 当前用于能力判定的设备快照。 */
    val target: DeviceConfigTarget = DeviceConfigTarget(),
) {
    val saving: Boolean get() = savingKey != null
    val visibleFields: List<ConfigFieldState> get() = fields.filter { it.visible }
    val hiddenFields: List<ConfigFieldState> get() = fields.filterNot { it.visible }

    /** 能力提示的文案键：未知快照时优先提示刷新设备信息，否则说明因能力隐藏。 */
    val capabilityNoticeKey: StringKey?
        get() = when {
            hiddenFields.isEmpty() -> null
            hiddenFields.any { it.capability.status == com.valhalla.core.config.ConfigCapabilityStatus.TARGET_UNKNOWN } ->
                StringKey.CONFIG_HIDDEN_UNKNOWN_TARGET

            else -> StringKey.CONFIG_HIDDEN_UNSUPPORTED
        }
}

/**
 * 配置读写控制器：按命令表读取当前配置、校验后下发 `SET_CONFIG`。
 *
 * 约束：
 * - 只有命令表标记为可编辑的键才能下发，未知键/只读键在 UI 上标注原因；
 * - 校验在本地完成（类型、长度、整数区间），校验失败不会发请求；
 * - 同一时刻只允许一个保存请求在途，避免重复提交；
 * - 每个键的读取失败独立展示，不影响其它键。
 */
@OptIn(ExperimentalTime::class)
class ConfigEditorController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val catalog: ConfigCatalog = ConfigCatalog,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    initialTarget: DeviceConfigTarget = DeviceConfigTarget(),
) {

    private val mutableState = MutableStateFlow(
        ConfigEditorState(
            fields = catalog.keys.map { spec ->
                ConfigFieldState(spec = spec, capability = catalog.capability(spec, initialTarget))
            },
            target = initialTarget,
        ),
    )
    val state: StateFlow<ConfigEditorState> = mutableState.asStateFlow()

    /**
     * 更新用于能力判定的设备快照（固件版本、型号）。
     *
     * 仅在能力发生变化时重算；已输入文本、校验错误与最近保存时间都保留，
     * 因此刷新设备信息不会清空用户正在编辑的内容。
     */
    fun updateTarget(target: DeviceConfigTarget) {
        mutableState.update { state ->
            if (state.target == target) {
                state
            } else {
                state.copy(
                    target = target,
                    fields = state.fields.map { field ->
                        field.copy(capability = catalog.capability(field.spec, target))
                    },
                )
            }
        }
    }

    /** 读取当前能力下可见的配置键；已在读取中时直接返回 false，避免重复请求。 */
    suspend fun load(): Boolean {
        if (mutableState.value.loading) return false
        val visible = mutableState.value.visibleFields
        if (visible.isEmpty()) {
            mutableState.update {
                it.copy(
                    loading = false,
                    message = null,
                    error = UiMessage(StringKey.CONFIG_NO_KEYS),
                    errorCode = null,
                )
            }
            return false
        }
        mutableState.update { it.copy(loading = true, message = null, error = null, errorCode = null) }
        try {
            supervisorScope {
                val jobs = visible.map { field ->
                    async { loadOne(field.spec) }
                }
                jobs.forEach { it.await() }
            }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
        return true
    }

    /** 更新编辑框文本；不清除保存成功提示，避免用户输入时提示闪烁。 */
    fun updateText(key: String, text: String) {
        updateField(key) { field ->
            field.copy(text = text, validationError = null)
        }
    }

    /**
     * 校验并保存单个配置项。
     *
     * @return true 表示保存成功；校验失败、只读、已有保存在途或设备拒绝时返回 false。
     */
    suspend fun save(key: String): Boolean {
        val snapshot = mutableState.value
        val field = snapshot.fields.firstOrNull { it.spec.key == key }
            ?: return fail(UiMessage(StringKey.CONFIG_UNKNOWN_KEY, mapOf("key" to key)))
        // 下发前用最新设备快照二次校验能力，避免读取版本后设备状态变化导致设备回 INVALID_PARAM。
        val capability = catalog.capability(field.spec, snapshot.target)
        if (capability != field.capability) {
            updateField(key) { it.copy(capability = capability) }
        }
        if (!capability.supported || !capability.visible) {
            return fail(UiMessage(StringKey.CONFIG_UNSUPPORTED_FIELD, mapOf("field" to key)))
        }
        if (!field.spec.editable) {
            return fail(UiMessage(StringKey.CONFIG_READ_ONLY_FIELD, mapOf("field" to key)))
        }
        if (snapshot.savingKey != null) {
            return fail(
                UiMessage(StringKey.CONFIG_SAVING_BUSY, mapOf("field" to snapshot.savingKey)),
            )
        }
        val validation = catalog.validate(field.spec, field.text)
        if (validation is ConfigValidation.Invalid) {
            updateField(key) { it.copy(validationError = validation.message) }
            return false
        }
        val valid = validation as ConfigValidation.Valid

        mutableState.update {
            it.copy(
                savingKey = key,
                message = null,
                error = null,
                errorCode = null,
                fields = it.fields.map { item ->
                    if (item.spec.key == key) item.copy(saving = true, validationError = null) else item
                },
            )
        }

        return try {
            val response = dispatcher.execute(SetConfigRequest(key, valid.bytes))
            if (response !is SetConfigResponse) {
                throw IllegalStateException("写配置应答类型异常: ${response::class.simpleName}")
            }
            val now = clockMillis()
            mutableState.update {
                it.copy(
                    savingKey = null,
                    message = UiMessage(StringKey.CONFIG_SAVED_FIELD, mapOf("field" to key)),
                    error = null,
                    errorCode = null,
                    fields = it.fields.map { item ->
                        if (item.spec.key == key) {
                            item.copy(
                                text = valid.canonicalText,
                                originalText = valid.canonicalText,
                                loaded = true,
                                saving = false,
                                validationError = null,
                                loadError = null,
                                lastSavedAtEpochMillis = now,
                            )
                        } else {
                            item
                        }
                    },
                )
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            mutableState.update {
                it.copy(
                    savingKey = null,
                    error = UiMessage(
                        StringKey.CONFIG_LOAD_FAILED,
                        args = mapOf("field" to key),
                        nested = mapOf("detail" to info.ui),
                    ),
                    errorCode = info.status,
                    fields = it.fields.map { item ->
                        if (item.spec.key == key) item.copy(saving = false) else item
                    },
                )
            }
            false
        }
    }

    fun clearFeedback() {
        mutableState.update { it.copy(message = null, error = null, errorCode = null) }
    }

    private suspend fun loadOne(spec: ConfigKeySpec) {
        updateField(spec.key) { it.copy(loading = true, loadError = null) }
        try {
            val response = dispatcher.execute(GetConfigRequest(spec.key))
            if (response !is ConfigResponse) {
                throw IllegalStateException("配置应答类型异常: ${response::class.simpleName}")
            }
            val text = catalog.format(spec, response.value)
            updateField(spec.key) {
                it.copy(
                    text = text,
                    originalText = text,
                    loaded = true,
                    loading = false,
                    loadError = null,
                    validationError = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val info = error.toFailureInfo()
            updateField(spec.key) {
                it.copy(
                    loading = false,
                    loadError = info.message,
                )
            }
        }
    }

    private fun fail(message: UiMessage): Boolean {
        mutableState.update { it.copy(error = message) }
        return false
    }

    private fun updateField(key: String, transform: (ConfigFieldState) -> ConfigFieldState) {
        mutableState.update { state ->
            state.copy(
                fields = state.fields.map { field ->
                    if (field.spec.key == key) transform(field) else field
                },
            )
        }
    }
}
