package com.valhalla.feature.onboarding

import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSettingsStore : AppSettingsStore {
    val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class OnboardingControllerTest {
    @Test
    fun `未完成时启动应展示引导`() {
        val store = FakeSettingsStore()
        assertTrue(OnboardingController.shouldShowOnLaunch(store))
        assertFalse(OnboardingController(store).state.value.finished)
        assertEquals(OnboardingStep.PERMISSION, OnboardingController(store).state.value.step)
    }

    @Test
    fun `按顺序推进三步`() {
        val controller = OnboardingController(FakeSettingsStore())
        assertEquals(
            listOf(OnboardingStep.PERMISSION, OnboardingStep.SCAN, OnboardingStep.CONNECT),
            OnboardingStep.ORDER,
        )

        controller.next()
        assertEquals(OnboardingStep.SCAN, controller.state.value.step)
        assertEquals(1, controller.state.value.index)
        controller.next()
        assertEquals(OnboardingStep.CONNECT, controller.state.value.step)
        assertEquals(2, controller.state.value.index)
        assertTrue(controller.state.value.isLastStep)

        controller.next()
        assertTrue(controller.state.value.finished)
    }

    @Test
    fun `最后一步继续等同于完成并落库`() {
        val store = FakeSettingsStore()
        val controller = OnboardingController(store)
        controller.next()
        controller.next()
        controller.next()

        assertTrue(controller.state.value.finished)
        assertTrue(store.read(SettingsKeys.ONBOARDING_COMPLETED)?.toBoolean() == true)
        assertFalse(OnboardingController.shouldShowOnLaunch(store))
    }

    @Test
    fun `任一步跳过都标记完成且不重复写入`() {
        val store = FakeSettingsStore()
        val controller = OnboardingController(store)
        controller.next()
        controller.skip()

        assertTrue(controller.state.value.finished)
        assertTrue(OnboardingController.shouldShowOnLaunch(store).not())

        // 已完成状态下重复 finish/next 不再改变结果。
        controller.finish()
        controller.next()
        assertEquals(OnboardingStep.SCAN, controller.state.value.step)
        assertTrue(controller.state.value.finished)
    }

    @Test
    fun `设置页回顾模式不影响完成状态`() {
        val store = FakeSettingsStore()
        store.write(SettingsKeys.ONBOARDING_COMPLETED, "true")
        val controller = OnboardingController(store, reviewMode = true)

        assertTrue(controller.state.value.reviewMode)
        assertEquals(OnboardingStep.PERMISSION, controller.state.value.step)
        controller.skip()

        assertTrue(controller.state.value.finished)
        assertFalse(OnboardingController.shouldShowOnLaunch(store))
    }

    @Test
    fun `损坏的完成标记按未完成处理`() {
        val store = FakeSettingsStore()
        store.write(SettingsKeys.ONBOARDING_COMPLETED, "maybe")
        assertTrue(OnboardingController.shouldShowOnLaunch(store))
    }
}
