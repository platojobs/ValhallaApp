package com.valhalla.feature.ui

import androidx.compose.ui.graphics.Color
import com.valhalla.core.metrics.DeviceMetricSeries
import com.valhalla.feature.detail.DeviceDetailState
import com.valhalla.feature.i18n.StringResolver
import com.valhalla.feature.metrics.RssiDegradedReason
import com.valhalla.feature.metrics.RssiSource

/** 设备仪表卡的全部输入，避免长参数表，也便于把渲染拆成小块。 */
internal data class DeviceDashboardCardUi(
    val detail: DeviceDetailState,
    val series: DeviceMetricSeries,
    val rssi: Int?,
    val rssiSource: RssiSource,
    val degradedReason: RssiDegradedReason?,
    val legacyMetricsIgnored: Boolean,
    val observedAtMillis: Long,
    val strings: StringResolver,
    val zoneOffsetMinutes: Int,
    val accent: Color,
    val muted: Color,
)
