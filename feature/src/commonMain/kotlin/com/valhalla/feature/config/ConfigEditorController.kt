package com.valhalla.feature.config

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ConfigCatalog
import com.valhalla.core.config.ConfigKeySpec
import com.valhalla.core.config.ConfigValidation
import com.valhalla.core.protocol.message.ConfigResponse
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.GetConfigRequest
import com.valhalla.core.protocol.message.SetConfigRequest
import com.valhalla.core.protocol.message.SetConfigResponse
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
) {
    val dirty: Boolean get() = text != originalText
    val editable: Boolean get() = spec.editable
}

/** 配置区整体状态。 */
data class ConfigEditorState(
    val fields: List<ConfigFieldState> = emptyList(),
    val loading: Boolean = false,
    val savingKey: String? = null,
    val message: String? = null,
    val error: String? = null,
    val errorCode: DeviceStatus? = null,
) {
    val saving: Boolean get() = savingKey != null
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
) {

    private val mutableState = MutableStateFlow(
        ConfigEditorState(fields = catalog.keys.map { ConfigFieldState(spec = it) }),
    )
    val state: StateFlow<ConfigEditorState> = mutableState.asStateFlow()

    /** 读取命令表中的全部配置键；已在读取中时直接返回 false，避免重复请求。 */
    suspend fun load(): Boolean {
        if (mutableState.value.loading) return false
        mutableState.update { it.copy(loading = true, message = null, error = null, errorCode = null) }
        try {
            supervisorScope {
                val jobs = mutableState.value.fields.map { field ->
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
            ?: return fail("未知配置项：$key")
        if (!field.editable) {
            return fail(field.spec.readOnlyReason ?: "该配置项只读")
        }
        if (snapshot.savingKey != null) {
            return fail("正在保存 ${snapshot.savingKey}，请稍后再试")
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
                    message = "已保存 ${field.spec.displayName}",
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
                    error = "保存 ${field.spec.displayName} 失败：${info.message}",
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
                    loadError = "读取失败：${info.message}",
                )
            }
        }
    }

    private fun fail(message: String): Boolean {
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
