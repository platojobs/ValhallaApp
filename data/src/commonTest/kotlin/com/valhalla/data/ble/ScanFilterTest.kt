package com.valhalla.data.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanFilterTest {
    @Test
    fun `空过滤条件匹配任意设备`() {
        val filter = ScanFilter()
        assertTrue(filter.matchesName(null))
        assertTrue(filter.matchesName("Valhalla-01"))
        assertTrue(filter.matchesService(emptyList()))
    }

    @Test
    fun `名称前缀忽略大小写`() {
        val filter = ScanFilter(namePrefix = "valhalla")
        assertTrue(filter.matchesName("Valhalla-Sensor"))
        assertFalse(filter.matchesName("Other-Device"))
        assertFalse(filter.matchesName(null))
    }

    @Test
    fun `服务 UUID 忽略大小写匹配`() {
        val filter = ScanFilter(serviceUuid = "0000F1A0-0000-1000-8000-00805F9B34FB")
        assertTrue(filter.matchesService(listOf(BleUuids.OTA_SERVICE)))
        assertFalse(filter.matchesService(emptyList()))
    }
}
