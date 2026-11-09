package com.valhalla.feature.uilogic

import com.valhalla.core.config.ParamSpec
import com.valhalla.core.config.ParamValueType
import com.valhalla.core.logging.PacketLogExportEstimate
import com.valhalla.feature.ble.ReconnectPhase
import com.valhalla.feature.ble.ReconnectState
import com.valhalla.feature.i18n.LocaleFormat
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.param.ParamFieldState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiLogicExportAndParamTest {
    private val en = StringResolver(ResolvedLanguage.ENGLISH)

    @Test
    fun packetLogExportAvailabilityReflectsBlockers() {
        assertTrue(
            packetLogCanExport(
                exporting = false,
                entriesEmpty = false,
                hasOptions = true,
                rangeError = null,
                fromError = null,
                toError = null,
                matchedCount = 3,
            ),
        )
        assertFalse(packetLogCanExport(true, false, true, null, null, null, 3))
        assertFalse(packetLogCanExport(false, true, true, null, null, null, 3))
        assertFalse(packetLogCanExport(false, false, false, null, null, null, 3))
        assertFalse(packetLogCanExport(false, false, true, "range", null, null, 3))
        assertFalse(packetLogCanExport(false, false, true, null, "from", null, 3))
        assertFalse(packetLogCanExport(false, false, true, null, null, "to", 3))
        assertFalse(packetLogCanExport(false, false, true, null, null, null, 0))
    }

    @Test
    fun packetLogEstimateLinePrefersErrorsThenEstimate() {
        val format = LocaleFormat(ResolvedLanguage.ENGLISH)
        val ready =
            PacketLogExportEstimate(
                sourceCount = 10,
                matchedCount = 10,
                estimatedEntries = 10,
                estimatedBytes = 100,
                truncated = false,
            )
        assertEquals(
            en.t(StringKey.LOGS_ESTIMATE_INVALID_RANGE, "detail" to "range"),
            packetLogEstimateLine("range", null, null, true, ready, en, format),
        )
        assertEquals(
            en.t(StringKey.LOGS_TIME_FORMAT_ERROR),
            packetLogEstimateLine(null, "from", null, true, ready, en, format),
        )
        assertEquals(
            en.t(StringKey.LOGS_ESTIMATE_INVALID_LIMIT, "detail" to ""),
            packetLogEstimateLine(null, null, null, false, ready, en, format),
        )
        assertEquals(
            en.t(StringKey.LOGS_ESTIMATE_LOADING),
            packetLogEstimateLine(null, null, null, true, null, en, format),
        )
        assertEquals(
            en.t(StringKey.LOGS_ESTIMATE_NO_MATCH),
            packetLogEstimateLine(null, null, null, true, ready.copy(matchedCount = 0), en, format),
        )
        assertEquals(
            en.t(
                StringKey.LOGS_ESTIMATE_TRUNCATED,
                "entries" to format.number(10),
                "bytes" to format.bytes(100L),
                "matched" to format.number(10),
            ),
            packetLogEstimateLine(null, null, null, true, ready.copy(truncated = true), en, format),
        )
        assertEquals(
            en.t(
                StringKey.LOGS_ESTIMATE,
                "entries" to format.number(10),
                "bytes" to format.bytes(100L),
            ),
            packetLogEstimateLine(null, null, null, true, ready, en, format),
        )
    }

    @Test
    fun paramRangeLabelCoversAllValueTypes() {
        assertEquals(
            en.t(StringKey.PARAM_RANGE, "range" to "100..60000"),
            paramRangeLabel(ParamSpec(1, "timeout", ParamValueType.INTEGER, writable = true, intRange = 100..60000), en),
        )
        assertEquals(
            en.t(StringKey.PARAM_RANGE, "range" to "0/1/2"),
            paramRangeLabel(
                ParamSpec(2, "mode", ParamValueType.ENUM, writable = true, enumValues = mapOf(2 to "c", 0 to "a", 1 to "b")),
                en,
            ),
        )
        assertEquals(
            en.t(StringKey.PARAM_RANGE, "range" to "≤ 16"),
            paramRangeLabel(ParamSpec(3, "name", ParamValueType.STRING, writable = true, maxLength = 16), en),
        )
        assertNull(paramRangeLabel(ParamSpec(4, "flag", ParamValueType.BOOLEAN, writable = true), en))
    }

    @Test
    fun paramDetailLineJoinsTypeRangeUnitAndId() {
        val spec = ParamSpec(0x1F, "timeout", ParamValueType.INTEGER, writable = true, intRange = 100..60000, unit = "ms")
        assertEquals(
            "INTEGER · 100..60000 · ms · Parameter ID: 0x001F",
            paramDetailLine(spec, "INTEGER", "100..60000", en),
        )
    }

    @Test
    fun paramSupportingTextFollowsFieldStatePriority() {
        val spec = ParamSpec(1, "timeout", ParamValueType.INTEGER, writable = true, intRange = 0..10)
        assertEquals(
            en.t(StringKey.PARAM_VALUE_LABEL),
            paramSupportingText(ParamFieldState(spec = spec), spec, en),
        )
        assertEquals(
            en.t(StringKey.PARAM_LOADING),
            paramSupportingText(ParamFieldState(spec = spec, loading = true), spec, en),
        )
        assertEquals(
            en.t(StringKey.PARAM_LOAD_ERROR) + " · boom",
            paramSupportingText(ParamFieldState(spec = spec, loadError = "boom"), spec, en),
        )
        assertEquals(
            "invalid",
            paramSupportingText(ParamFieldState(spec = spec, validationError = "invalid"), spec, en),
        )
        val readOnlySpec = ParamSpec(2, "fixed", ParamValueType.INTEGER, writable = false)
        assertEquals(
            en.t(StringKey.PARAM_READ_ONLY_REASON),
            paramSupportingText(ParamFieldState(spec = readOnlySpec), readOnlySpec, en),
        )
        assertEquals(
            en.t(StringKey.PARAM_SAVING),
            paramSupportingText(ParamFieldState(spec = spec, saving = true), spec, en),
        )
        assertEquals(
            en.t(StringKey.PARAM_SAVED_FIELD, "name" to spec.displayName),
            paramSupportingText(ParamFieldState(spec = spec, lastSavedAtEpochMillis = 1L), spec, en),
        )
        assertEquals(
            en.t(StringKey.PARAM_UNSAVED),
            paramSupportingText(ParamFieldState(spec = spec, text = "2", originalText = "1"), spec, en),
        )
    }

    @Test
    fun disconnectReconnectHintMapsAllPhases() {
        assertEquals(
            en.t(StringKey.RECONNECT_WAITING, "seconds" to "5", "attempt" to "2"),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.WAITING, attempt = 1, nextDelayMillis = 5_000), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_CONNECTING, "attempt" to "2"),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.CONNECTING, attempt = 1), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_PAUSED_BLUETOOTH_OFF),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.PAUSED, pausedByBluetoothOff = true), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_PAUSED),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.PAUSED), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_EXHAUSTED, "attempt" to "3"),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.EXHAUSTED, attempt = 3), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_CANCELLED),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.CANCELLED), en),
        )
        assertEquals(
            en.t(StringKey.RECONNECT_SUCCEEDED),
            disconnectReconnectHint(ReconnectState(ReconnectPhase.SUCCEEDED), en),
        )
        assertNull(disconnectReconnectHint(ReconnectState(ReconnectPhase.IDLE), en))
    }
}
