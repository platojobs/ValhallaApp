package com.valhalla.feature.param

import com.valhalla.core.command.ConcurrentCommandDispatcher
import com.valhalla.core.config.ParamCatalog
import com.valhalla.core.config.ParamSpec
import com.valhalla.core.config.ParamValidation
import com.valhalla.core.protocol.message.DeviceStatus
import com.valhalla.core.protocol.message.GetParamRequest
import com.valhalla.core.protocol.message.ParamResponse
import com.valhalla.core.protocol.message.SetParamRequest
import com.valhalla.core.protocol.message.SetParamResponse
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.UiMessage
import com.valhalla.feature.toFailureInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 单个参数项的可编辑状态。 */
data class ParamFieldState(
    val spec: ParamSpec,
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
    val editable: Boolean get() = spec.writable
}

/** 参数区整体状态。 */
data class ParamEditorState(
    val fields: List<ParamFieldState> = emptyList(),
    val loading: Boolean = false,
    val savingId: Int? = null,
    val message: UiMessage? = null,
    val error: UiMessage? = null,
    val errorCode: DeviceStatus? = null,
) {
    val saving: Boolean get() = savingId != null

    /** 读取或写入任一在途时都为 true，界面据此禁用所有操作。 */
    val busy: Boolean get() = loading || saving
}

/**
 * 参数读写控制器：`GET_PARAM(0x0A)` 读取、`SET_PARAM(0x09)` 写入。
 *
 * 约束：
 * - 参数表来自 [ParamCatalog] 的约定值，固件未确认，界面必须提示「需固件确认」；
 * - 本地校验（类型、范围、UTF-8 字节长度）失败不下发；
 * - 同一时刻只允许一个在途请求：读取按顺序串行执行，写入期间拒绝其它写入，
 *   读取期间拒绝写入，避免设备端参数互相覆盖；
 * - 设备拒绝时展示错误码与错误文案，写入状态回落为可重试。
 */
@OptIn(ExperimentalTime::class)
class ParamEditorController(
    private val dispatcher: ConcurrentCommandDispatcher,
    private val catalog: ParamCatalog = ParamCatalog,
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onError: (Throwable) -> Unit = {},
) {

    private val mutableState = MutableStateFlow(
        ParamEditorState(fields = catalog.params.map { ParamFieldState(spec = it) }),
    )
    val state: StateFlow<ParamEditorState> = mutableState.asStateFlow()

    /** 串行读取全部参数；已有读取或写入在途时返回 false。 */
    suspend fun load(): Boolean {
        if (mutableState.value.busy) return false
        if (mutableState.value.fields.isEmpty()) {
            mutableState.update {
                it.copy(error = UiMessage(StringKey.PARAM_NO_ITEMS), errorCode = null)
            }
            return false
        }
        mutableState.update { it.copy(loading = true, message = null, error = null, errorCode = null) }
        try {
            mutableState.value.fields.forEach { field ->
                loadOne(field.spec)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            onError(error)
            mutableState.update {
                it.copy(
                    error = UiMessage(
                        StringKey.PARAM_LOAD_FAILED,
                        mapOf("detail" to (error.message ?: StringKey.UNKNOWN.name)),
                    ),
                )
            }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
        return true
    }

    /** 更新编辑框文本；输入时清除上一次的校验/保存提示。 */
    fun updateText(paramId: Int, text: String) {
        updateField(paramId) { it.copy(text = text, validationError = null) }
    }

    /**
     * 校验并写入单个参数。
     *
     * @return true 表示设备接受写入；校验失败、只读、有请求在途或设备拒绝时返回 false。
     */
    suspend fun save(paramId: Int): Boolean {
        val snapshot = mutableState.value
        val field = snapshot.fields.firstOrNull { it.spec.id == paramId }
            ?: return fail(UiMessage(StringKey.PARAM_UNKNOWN_ITEM, mapOf("id" to paramId.toString())))
        if (!field.spec.writable) {
            return fail(UiMessage(StringKey.PARAM_READ_ONLY_ITEM, mapOf("name" to field.spec.displayName)))
        }
        if (snapshot.loading) {
            return fail(UiMessage(StringKey.PARAM_BUSY))
        }
        if (snapshot.savingId != null) {
            val busyName = snapshot.fields.firstOrNull { it.spec.id == snapshot.savingId }
                ?.spec?.displayName ?: snapshot.savingId.toString()
            return fail(UiMessage(StringKey.PARAM_SAVING_BUSY, mapOf("name" to busyName)))
        }
        val validation = catalog.validate(field.spec, field.text)
        if (validation is ParamValidation.Invalid) {
            updateField(paramId) { it.copy(validationError = validation.message) }
            return false
        }
        val valid = validation as ParamValidation.Valid

        mutableState.update {
            it.copy(
                savingId = paramId,
                message = null,
                error = null,
                errorCode = null,
                fields = it.fields.map { item ->
                    if (item.spec.id == paramId) {
                        item.copy(saving = true, validationError = null)
                    } else {
                        item
                    }
                },
            )
        }

        return try {
            val response = dispatcher.execute(SetParamRequest(paramId, valid.bytes))
            if (response !is SetParamResponse) {
                throw IllegalStateException("写参数应答类型异常: ${response::class.simpleName}")
            }
            val now = clockMillis()
            mutableState.update {
                it.copy(
                    savingId = null,
                    message = UiMessage(
                        StringKey.PARAM_SAVED_FIELD,
                        mapOf("name" to field.spec.displayName),
                    ),
                    error = null,
                    errorCode = null,
                    fields = it.fields.map { item ->
                        if (item.spec.id == paramId) {
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
                    savingId = null,
                    error = UiMessage(
                        StringKey.PARAM_SAVE_FAILED,
                        args = mapOf("name" to field.spec.displayName),
                        nested = mapOf("detail" to info.ui),
                    ),
                    errorCode = info.status,
                    fields = it.fields.map { item ->
                        if (item.spec.id == paramId) item.copy(saving = false) else item
                    },
                )
            }
            false
        }
    }

    fun clearFeedback() {
        mutableState.update { it.copy(message = null, error = null, errorCode = null) }
    }

    private suspend fun loadOne(spec: ParamSpec) {
        updateField(spec.id) { it.copy(loading = true, loadError = null) }
        try {
            val response = dispatcher.execute(GetParamRequest(spec.id))
            if (response !is ParamResponse) {
                throw IllegalStateException("参数应答类型异常: ${response::class.simpleName}")
            }
            val text = catalog.format(spec, response.value)
            updateField(spec.id) {
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
            updateField(spec.id) {
                it.copy(loading = false, loadError = info.message)
            }
        }
    }

    private fun fail(message: UiMessage): Boolean {
        mutableState.update { it.copy(error = message) }
        return false
    }

    private fun updateField(paramId: Int, transform: (ParamFieldState) -> ParamFieldState) {
        mutableState.update { state ->
            state.copy(
                fields = state.fields.map { field ->
                    if (field.spec.id == paramId) transform(field) else field
                },
            )
        }
    }
}
