package com.valhalla.feature.onboarding

import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.feature.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首次使用引导的三步。 */
enum class OnboardingStep {
    PERMISSION,
    SCAN,
    CONNECT,
    ;

    companion object {
        val ORDER: List<OnboardingStep> = listOf(PERMISSION, SCAN, CONNECT)
    }
}

data class OnboardingState(
    val step: OnboardingStep,
    val index: Int,
    val total: Int,
    val reviewMode: Boolean,
    val finished: Boolean,
) {
    val isLastStep: Boolean get() = index == total - 1
}

/**
 * 首次使用引导的状态机。
 *
 * 约束：
 * - 三步都可跳过，跳过等同于完成，避免每次启动重复打扰；
 * - 完成状态写入 [AppSettingsStore]（键 `onboarding_completed`），跨进程可读；
 * - 引导只负责说明，**不直接弹出系统权限框**；权限步骤由界面调用既有
 *   `BlePermissionState` 请求逻辑，请求次数与拒绝后的静默降级仍由它统一管理；
 * - 从设置页进入时使用 `reviewMode = true`，完成后仍回到设置页。
 */
class OnboardingController(
    private val store: AppSettingsStore,
    reviewMode: Boolean = false,
) {
    private val steps = OnboardingStep.ORDER
    private val mutableState =
        MutableStateFlow(
            OnboardingState(
                step = steps.first(),
                index = 0,
                total = steps.size,
                reviewMode = reviewMode,
                finished = false,
            ),
        )

    val state: StateFlow<OnboardingState> = mutableState.asStateFlow()

    /** 下一步；最后一步调用等同于完成。 */
    fun next() {
        val current = mutableState.value
        if (current.finished) return
        if (current.isLastStep) {
            finish()
            return
        }
        val nextIndex = current.index + 1
        mutableState.value = current.copy(step = steps[nextIndex], index = nextIndex)
    }

    /** 跳过剩余步骤并标记完成。 */
    fun skip() = finish()

    /** 标记完成并落库；写入失败只影响下次启动是否再次展示，不阻断当前流程。 */
    fun finish() {
        val current = mutableState.value
        if (current.finished) return
        mutableState.value = current.copy(finished = true)
        runCatching {
            AppSettingsRepository.setOnboardingCompleted(store, true)
        }
    }

    companion object {
        /** 启动时是否应展示引导：从未完成（或存储值损坏）时为 true。 */
        fun shouldShowOnLaunch(store: AppSettingsStore): Boolean = !AppSettingsRepository.isOnboardingCompleted(store)
    }
}
