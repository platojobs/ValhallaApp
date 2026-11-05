package com.valhalla.feature.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirmwareKeyInjectionTest {

    @Test
    fun environmentWinsOverGradleProperty() {
        assertEquals(
            "env-key",
            FirmwareKeyInjection.select("env-key", "gradle-key"),
        )
    }

    @Test
    fun gradlePropertyUsedWhenEnvironmentMissingOrBlank() {
        assertEquals(
            "gradle-key",
            FirmwareKeyInjection.select(null, "gradle-key"),
        )
        assertEquals(
            "gradle-key",
            FirmwareKeyInjection.select("   ", "gradle-key"),
        )
    }

    @Test
    fun nothingConfiguredReturnsNull() {
        assertNull(FirmwareKeyInjection.select(null, null))
        assertNull(FirmwareKeyInjection.select("", "  "))
    }
}
