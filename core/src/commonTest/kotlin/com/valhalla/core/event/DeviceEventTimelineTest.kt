package com.valhalla.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceEventTimelineTest {

    @Test
    fun `事件按时间倒序返回并可按类型筛选`() {
        val timeline = DeviceEventTimeline(InMemoryDeviceEventStore(), clockMillis = { 1 })
        timeline.record("dev", DeviceEventType.CONNECTION, "已连接", timestampMillis = 100)
        timeline.record("dev", DeviceEventType.OTA, "OTA 开始", timestampMillis = 200)
        timeline.record("dev", DeviceEventType.ERROR, "设备错误 0x05", timestampMillis = 300)

        val all = timeline.list("dev")
        assertEquals(listOf(300L, 200L, 100L), all.map { it.timestampMillis })
        assertEquals(listOf(300L), timeline.list("dev", DeviceEventType.ERROR).map { it.timestampMillis })
        assertTrue(timeline.list("other").isEmpty(), "事件按设备隔离")
    }

    @Test
    fun `超过 100 条后淘汰最旧事件`() {
        val store = InMemoryDeviceEventStore()
        val timeline = DeviceEventTimeline(store, maxPerDevice = 100, clockMillis = { 0 })
        repeat(105) { index ->
            timeline.record("dev", DeviceEventType.CONNECTION, "事件 $index", timestampMillis = index.toLong())
        }
        val events = timeline.list("dev")
        assertEquals(100, events.size)
        assertEquals(104L, events.first().timestampMillis)
        assertEquals(5L, events.last().timestampMillis)
    }

    @Test
    fun `损坏存储回退为空且不抛出`() {
        val store = InMemoryDeviceEventStore()
        store.saveRaw("dev", "garbage")
        val timeline = DeviceEventTimeline(store)
        assertTrue(timeline.list("dev").isEmpty())
        assertEquals(0, timeline.count("dev"))
    }

    @Test
    fun `写入失败只返回 false 不影响调用方`() {
        val failing = object : DeviceEventStore {
            override fun loadRaw(deviceId: String): String? = null
            override fun saveRaw(deviceId: String, raw: String) = error("disk full")
            override fun clear(deviceId: String) = Unit
        }
        val timeline = DeviceEventTimeline(failing)
        assertFalse(timeline.record("dev", DeviceEventType.OTA, "OTA 失败"))
        assertTrue(timeline.list("dev").isEmpty())
    }

    @Test
    fun `事件文本落盘前脱敏`() {
        val store = InMemoryDeviceEventStore()
        val timeline = DeviceEventTimeline(store)
        timeline.record(
            deviceId = "dev",
            type = DeviceEventType.ERROR,
            summary = "token=secret-value 连接失败",
            detail = "serialNumber=SN12345",
        )
        val raw = store.loadRaw("dev")!!
        assertFalse(raw.contains("secret-value"), "事件摘要不应包含令牌原文")
        assertFalse(raw.contains("SN12345"), "事件详情不应包含序列号原文")
        assertFalse(timeline.list("dev").first().summary.contains("secret-value"))
    }

    @Test
    fun `清除只影响指定设备`() {
        val store = InMemoryDeviceEventStore()
        val timeline = DeviceEventTimeline(store)
        timeline.record("a", DeviceEventType.CONNECTION, "a")
        timeline.record("b", DeviceEventType.CONNECTION, "b")
        timeline.clear("a")
        assertTrue(timeline.list("a").isEmpty())
        assertEquals(1, timeline.count("b"))
    }
}
