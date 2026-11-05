package com.valhalla.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceMetricSamplesTest {

    private fun repository(maxSamples: Int = 50): DeviceMetricRepository =
        DeviceMetricRepository(InMemoryDeviceMetricStore(), maxSamples = maxSamples) { 1_000L }

    @Test
    fun `记录采样并按时间保留`() {
        var now = 1_000L
        val repository = DeviceMetricRepository(InMemoryDeviceMetricStore(), clockMillis = { now })
        repository.record("dev", batteryPercent = 80, rssi = -50)
        now = 2_000L
        repository.record("dev", batteryPercent = 70, rssi = -60)

        val series = repository.series("dev")
        assertEquals(2, series.samples.size)
        assertEquals(listOf(1_000L to 80, 2_000L to 70), series.batteryPoints)
        assertEquals(listOf(1_000L to -50, 2_000L to -60), series.rssiPoints)
    }

    @Test
    fun `超过上限淘汰最旧采样`() {
        val store = InMemoryDeviceMetricStore()
        var now = 0L
        val repository = DeviceMetricRepository(store, maxSamples = 3, clockMillis = { now })
        repeat(5) { index ->
            now = index.toLong()
            repository.record("dev", batteryPercent = 100 - index, rssi = -40 - index)
        }

        val series = repository.series("dev")
        assertEquals(3, series.samples.size)
        assertEquals(listOf(2L, 3L, 4L), series.samples.map { it.timestampMillis })
        assertEquals(3, DeviceMetricCodec.decode(store.loadRaw("dev")).size)
    }

    @Test
    fun `空值采样不写入`() {
        val repository = repository()
        assertEquals(0, repository.record("dev", null, null).samples.size)
        assertEquals(0, repository.series("dev").samples.size)
    }

    @Test
    fun `不足三个有效点时不画趋势`() {
        val repository = repository()
        repository.record("dev", 90, null)
        repository.record("dev", 85, null)
        assertFalse(repository.series("dev").canDrawBattery)

        repository.record("dev", 80, null)
        assertTrue(repository.series("dev").canDrawBattery)
        assertFalse(repository.series("dev").canDrawRssi)
    }

    @Test
    fun `编码往返保留空值`() {
        val samples = listOf(
            DeviceMetricSample(1, 80, -50),
            DeviceMetricSample(2, null, -70),
        )
        val decoded = DeviceMetricCodec.decode(DeviceMetricCodec.encode(samples))
        assertEquals(samples, decoded)
    }

    @Test
    fun `损坏数据回退为空`() {
        assertEquals(emptyList(), DeviceMetricCodec.decode("v2\nS|1|80|-50"))
        assertEquals(emptyList(), DeviceMetricCodec.decode("v1\nbroken"))
        assertEquals(emptyList(), DeviceMetricCodec.decode(null))

        val mixed = DeviceMetricCodec.decode("v1\nS|1|80|-50\nS|bad|90|-40\nS|3||||")
        assertEquals(1, mixed.size)
        assertEquals(1L, mixed.single().timestampMillis)
    }

    @Test
    fun `设备之间互不影响`() {
        val repository = repository()
        repository.record("a", 80, -50)
        repository.record("b", 60, -80)
        assertEquals(1, repository.series("a").samples.size)
        assertEquals(60, repository.series("b").samples.single().batteryPercent)
    }
}
