package com.valhalla.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutomationRuleTest {

    @Test
    fun `编码解码与损坏回退`() {
        val rule = AutomationRule(
            id = "rule-1",
            trigger = AutomationTrigger.OTA_SUCCESS,
            action = AutomationAction.APPLY_PRESET,
            enabled = false,
            presetId = "preset-1",
            order = 2,
        )
        val decoded = AutomationRuleCodec.decode(AutomationRuleCodec.encode(listOf(rule)))
        assertEquals(listOf(rule), decoded)
        assertTrue(AutomationRuleCodec.decode("broken").isEmpty())
        assertTrue(AutomationRuleCodec.decode("v1\nR|bad").isEmpty())
    }

    @Test
    fun `新增开关排序与删除`() {
        var now = 1L
        val repository = AutomationRuleRepository(
            InMemoryAutomationRuleStore(),
            clockMillis = { now++ },
        )
        val first = repository.add(AutomationTrigger.CONNECTED, AutomationAction.READ_DEVICE_INFO)!!
        val second = repository.add(
            AutomationTrigger.CONNECTED,
            AutomationAction.APPLY_PRESET,
            presetId = "preset-1",
        )!!
        val third = repository.add(AutomationTrigger.OTA_SUCCESS, AutomationAction.GENERATE_DIAGNOSTIC)!!

        assertEquals(listOf(first.id, second.id, third.id), repository.list().map { it.id })
        assertTrue(repository.setEnabled(second.id, false))
        assertFalse(repository.list()[1].enabled)
        assertTrue(repository.move(third.id, -1))
        assertEquals(listOf(first.id, third.id, second.id), repository.list().map { it.id })
        assertFalse(repository.move(first.id, -1))
        assertTrue(repository.delete(second.id))
        assertEquals(listOf(0, 1), repository.list().map { it.order })
        assertEquals(2, repository.list().size)
    }

    @Test
    fun `应用预设动作必须绑定预设`() {
        val store = InMemoryAutomationRuleStore()
        val repository = AutomationRuleRepository(store, clockMillis = { 1 })
        val rule = repository.add(
            AutomationTrigger.CONNECTED,
            AutomationAction.APPLY_PRESET,
            presetId = "p1",
        )
        assertNotNull(rule)
        assertEquals("p1", rule.presetId)
    }

    @Test
    fun `整表替换会重新编号`() {
        val repository = AutomationRuleRepository(InMemoryAutomationRuleStore(), clockMillis = { 1 })
        val rules = listOf(
            AutomationRule("a", AutomationTrigger.CONNECTED, AutomationAction.READ_BATTERY, order = 9),
            AutomationRule("b", AutomationTrigger.CONNECTED, AutomationAction.RUN_HEALTH_CHECK, order = 4),
        )
        assertTrue(repository.replaceAll(rules))
        assertEquals(listOf("a", "b"), repository.list().map { it.id })
        assertEquals(listOf(0, 1), repository.list().map { it.order })
    }
}
