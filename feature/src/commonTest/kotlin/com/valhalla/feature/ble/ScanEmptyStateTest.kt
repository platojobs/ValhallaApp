package com.valhalla.feature.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanEmptyStateTest {
    @Test
    fun `已有设备时无空态`() {
        assertEquals(
            ScanEmptyReason.NONE,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 2,
                availability = BleAvailability.PERMISSION_PERMANENTLY_DENIED,
                timedOut = true,
            ),
        )
    }

    @Test
    fun `扫描错误优先于业务空态`() {
        assertEquals(
            ScanEmptyReason.ERROR,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.BLUETOOTH_OFF,
                error = "扫描失败",
            ),
        )
    }

    @Test
    fun `缺少权限不被误报为无设备`() {
        assertEquals(
            ScanEmptyReason.NO_PERMISSION,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.PERMISSION_NOT_REQUESTED,
            ),
        )
        assertEquals(
            ScanEmptyReason.NO_PERMISSION,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.PERMISSION_DENIED,
            ),
        )
        assertEquals(
            ScanEmptyReason.PERMISSION_PERMANENTLY_DENIED,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.PERMISSION_PERMANENTLY_DENIED,
            ),
        )
    }

    @Test
    fun `蓝牙关闭与蓝牙重置分别展示`() {
        assertEquals(
            ScanEmptyReason.BLUETOOTH_OFF,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.BLUETOOTH_OFF,
            ),
        )
        assertEquals(
            ScanEmptyReason.BLUETOOTH_RESETTING,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.BLUETOOTH_RESETTING,
            ),
        )
    }

    @Test
    fun `不支持与未知状态分别展示`() {
        assertEquals(
            ScanEmptyReason.UNSUPPORTED,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.UNSUPPORTED,
            ),
        )
        assertEquals(
            ScanEmptyReason.UNKNOWN_AVAILABILITY,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.UNKNOWN,
            ),
        )
    }

    @Test
    fun `扫描中与扫描超时与无设备三者区分`() {
        assertEquals(
            ScanEmptyReason.SCANNING,
            classifyScanEmpty(
                scanning = true,
                deviceCount = 0,
                availability = BleAvailability.READY,
            ),
        )
        assertEquals(
            ScanEmptyReason.SCAN_TIMEOUT,
            classifyScanEmpty(
                scanning = true,
                deviceCount = 0,
                availability = BleAvailability.READY,
                timedOut = true,
            ),
        )
        assertEquals(
            ScanEmptyReason.SCAN_TIMEOUT,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.READY,
                timedOut = true,
            ),
        )
        assertEquals(
            ScanEmptyReason.NO_DEVICES,
            classifyScanEmpty(
                scanning = false,
                deviceCount = 0,
                availability = BleAvailability.READY,
            ),
        )
    }
}
