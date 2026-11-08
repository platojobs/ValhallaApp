package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.theme.DesignTokens

/**
 * 统一的四态面板（空态 / 加载态 / 错误态 / 不可用态）。
 *
 * 只做渲染，文案与判定全部来自可测逻辑；读屏语义在这里集中处理：
 * 加载态有 [progressBarRangeInfo]，错误与结果有 [liveRegion]，不可用态给出可读原因。
 */
@Composable
internal fun EmptyStatePanel(
    body: String? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val text = body ?: strings.t(StringKey.STATE_EMPTY_BODY)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.Spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        Text(strings.t(StringKey.STATE_EMPTY_TITLE), style = MaterialTheme.typography.titleSmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LoadingStatePanel(
    body: String? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val text = body ?: strings.t(StringKey.STATE_LOADING_BODY)
    val description = strings.t(StringKey.STATE_LOADING_TITLE) + " " + text
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.Spacing.md)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = description
                },
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        Text(strings.t(StringKey.STATE_LOADING_TITLE) + " " + text, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            modifier =
                Modifier.fillMaxWidth().semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    contentDescription = description
                },
        )
    }
}

@Composable
internal fun ErrorStatePanel(
    detail: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val text = strings.t(StringKey.STATE_ERROR_BODY, "detail" to detail)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = DesignTokens.Spacing.md)
                .semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = strings.t(StringKey.STATE_ERROR_TITLE) + " " + text
                },
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        Text(strings.t(StringKey.STATE_ERROR_TITLE), style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(strings.t(StringKey.RETRY)) }
        }
    }
}

@Composable
internal fun DisabledStatePanel(
    reason: StringKey,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val text = strings.t(reason)
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = text },
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
    ) {
        Text(strings.t(StringKey.STATE_DISABLED_TITLE), style = MaterialTheme.typography.titleSmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
