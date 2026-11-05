package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.core.AppBuildInfo
import com.valhalla.core.AppDependencyInfo
import com.valhalla.core.logging.PacketLogExportOptions
import com.valhalla.data.settings.StorageItem
import com.valhalla.data.settings.StorageLocation
import com.valhalla.data.settings.StoragePlatform
import com.valhalla.data.settings.currentStoragePlatform
import com.valhalla.data.settings.StorageInventory
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.firmware.rememberFirmwareSigningKeyPem
import com.valhalla.feature.i18n.StringKey
import com.valhalla.feature.ble.BlePermissionState
import com.valhalla.feature.notification.NotificationPresenter
import com.valhalla.feature.settings.AppSettings
import com.valhalla.feature.theme.DesignTokens
import com.valhalla.feature.theme.ThemeMode

/** 统一的信息页头部：返回 + 标题，与主界面层级保持一致。 */
@Composable
internal fun InfoScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(DesignTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(LocalStrings.current.t(StringKey.BACK))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
        ) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        options.forEachIndexed { index, (label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
fun SettingsScreen(
    language: AppLanguageMode,
    theme: ThemeMode,
    settings: AppSettings,
    notificationPresenter: NotificationPresenter,
    onLanguageChange: (AppLanguageMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    onPrivacy: () -> Unit,
    onOnboardingReview: () -> Unit,
) {
    val strings = LocalStrings.current
    InfoScreen(title = strings.t(StringKey.SETTINGS_TITLE), onBack = onBack) {
        SectionTitle(strings.t(StringKey.LANGUAGE_SECTION))
        ChoiceRow(
            options = listOf(
                strings.t(StringKey.LANGUAGE_SYSTEM) to (language == AppLanguageMode.SYSTEM),
                strings.t(StringKey.LANGUAGE_CHINESE) to (language == AppLanguageMode.CHINESE),
                strings.t(StringKey.LANGUAGE_ENGLISH) to (language == AppLanguageMode.ENGLISH),
            ),
            onSelect = { index ->
                onLanguageChange(
                    when (index) {
                        1 -> AppLanguageMode.CHINESE
                        2 -> AppLanguageMode.ENGLISH
                        else -> AppLanguageMode.SYSTEM
                    },
                )
            },
        )
        SectionTitle(strings.t(StringKey.THEME_SECTION))
        ChoiceRow(
            options = listOf(
                strings.t(StringKey.THEME_SYSTEM) to (theme == ThemeMode.SYSTEM),
                strings.t(StringKey.THEME_LIGHT) to (theme == ThemeMode.LIGHT),
                strings.t(StringKey.THEME_DARK) to (theme == ThemeMode.DARK),
            ),
            onSelect = { index ->
                onThemeChange(
                    when (index) {
                        1 -> ThemeMode.LIGHT
                        2 -> ThemeMode.DARK
                        else -> ThemeMode.SYSTEM
                    },
                )
            },
        )
        SettingsValuesSection(settings = settings, onSettingsChange = onSettingsChange)
        NotificationSettingsSection(
            settings = settings,
            presenter = notificationPresenter,
            onSettingsChange = onSettingsChange,
        )
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
            OutlinedButton(onClick = onOnboardingReview, modifier = Modifier.fillMaxWidth()) {
                Text(strings.t(StringKey.ONBOARDING_REVIEW_ENTRY))
            }
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                Text(strings.t(StringKey.ABOUT_ENTRY))
            }
            OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) {
                Text(strings.t(StringKey.HELP_ENTRY))
            }
            OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text(strings.t(StringKey.PRIVACY_ENTRY))
            }
        }
    }
}

/**
 * 运行参数设置区：六个数字字段共用一次「应用」。
 *
 * 非法输入就地提示且不写入；应用成功后由调用方持久化并刷新 [settings]。
 */
@Composable
private fun SettingsValuesSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val strings = LocalStrings.current
    var attempts by remember(settings) { mutableStateOf(settings.reconnectMaxAttempts.toString()) }
    var delay by remember(settings) { mutableStateOf(settings.reconnectMaxDelaySeconds.toString()) }
    var capacity by remember(settings) { mutableStateOf(settings.logCapacity.toString()) }
    var entries by remember(settings) { mutableStateOf(settings.exportEntries.toString()) }
    var bytes by remember(settings) { mutableStateOf(settings.exportBytesKb.toString()) }
    var hex by remember(settings) { mutableStateOf(settings.exportHexChars.toString()) }
    var status by remember(settings) { mutableStateOf<StringKey?>(null) }

    val attemptsValue = parseSettingInt(
        attempts,
        AppSettings.MIN_RECONNECT_ATTEMPTS,
        AppSettings.MAX_RECONNECT_ATTEMPTS,
    )
    val delayValue = parseSettingInt(
        delay,
        AppSettings.MIN_RECONNECT_MAX_DELAY_SECONDS,
        AppSettings.MAX_RECONNECT_MAX_DELAY_SECONDS,
    )
    val capacityValue = parseSettingInt(
        capacity,
        AppSettings.MIN_LOG_CAPACITY,
        AppSettings.MAX_LOG_CAPACITY,
    )
    val entriesValue = parseSettingInt(
        entries,
        PacketLogExportOptions.MIN_ENTRIES,
        PacketLogExportOptions.MAX_ENTRIES,
    )
    val bytesValue = parseSettingInt(
        bytes,
        AppSettings.MIN_EXPORT_BYTES_KB,
        AppSettings.MAX_EXPORT_BYTES_KB,
    )
    val hexValue = parseSettingInt(
        hex,
        1,
        PacketLogExportOptions.MAX_HEX_CHARS,
    )
    val allValid = listOf(attemptsValue, delayValue, capacityValue, entriesValue, bytesValue, hexValue)
        .all { it != null }

    SectionTitle(strings.t(StringKey.SETTINGS_RECONNECT_SECTION))
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_RECONNECT_ATTEMPTS),
        value = attempts,
        min = AppSettings.MIN_RECONNECT_ATTEMPTS,
        max = AppSettings.MAX_RECONNECT_ATTEMPTS,
        onValueChange = { attempts = it; status = null },
    )
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_RECONNECT_DELAY),
        value = delay,
        min = AppSettings.MIN_RECONNECT_MAX_DELAY_SECONDS,
        max = AppSettings.MAX_RECONNECT_MAX_DELAY_SECONDS,
        onValueChange = { delay = it; status = null },
    )
    SectionTitle(strings.t(StringKey.SETTINGS_LOG_CAPACITY))
    Text(
        strings.t(StringKey.SETTINGS_LOG_CAPACITY_NOTE),
        style = MaterialTheme.typography.bodySmall,
    )
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_LOG_CAPACITY),
        value = capacity,
        min = AppSettings.MIN_LOG_CAPACITY,
        max = AppSettings.MAX_LOG_CAPACITY,
        onValueChange = { capacity = it; status = null },
    )

    SectionTitle(strings.t(StringKey.SETTINGS_EXPORT_SECTION))
    Text(strings.t(StringKey.SETTINGS_EXPORT_NOTE), style = MaterialTheme.typography.bodySmall)
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_EXPORT_ENTRIES),
        value = entries,
        min = PacketLogExportOptions.MIN_ENTRIES,
        max = PacketLogExportOptions.MAX_ENTRIES,
        onValueChange = { entries = it; status = null },
    )
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_EXPORT_BYTES),
        value = bytes,
        min = AppSettings.MIN_EXPORT_BYTES_KB,
        max = AppSettings.MAX_EXPORT_BYTES_KB,
        onValueChange = { bytes = it; status = null },
    )
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_EXPORT_HEX),
        value = hex,
        min = 1,
        max = PacketLogExportOptions.MAX_HEX_CHARS,
        onValueChange = { hex = it; status = null },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
        Button(
            enabled = allValid,
            onClick = {
                if (allValid) {
                    onSettingsChange(
                        AppSettings(
                            reconnectMaxAttempts = attemptsValue!!,
                            reconnectMaxDelaySeconds = delayValue!!,
                            logCapacity = capacityValue!!,
                            exportEntries = entriesValue!!,
                            exportBytesKb = bytesValue!!,
                            exportHexChars = hexValue!!,
                            notifyOtaEnabled = settings.notifyOtaEnabled,
                            notifyDisconnectEnabled = settings.notifyDisconnectEnabled,
                            notifyLowBatteryEnabled = settings.notifyLowBatteryEnabled,
                            lowBatteryThreshold = settings.lowBatteryThreshold,
                        ),
                    )
                    status = StringKey.SETTINGS_APPLIED
                }
            },
        ) { Text(strings.t(StringKey.SETTINGS_APPLY)) }
        OutlinedButton(
            onClick = {
                attempts = AppSettings.DEFAULTS.reconnectMaxAttempts.toString()
                delay = AppSettings.DEFAULTS.reconnectMaxDelaySeconds.toString()
                capacity = AppSettings.DEFAULTS.logCapacity.toString()
                entries = AppSettings.DEFAULTS.exportEntries.toString()
                bytes = AppSettings.DEFAULTS.exportBytesKb.toString()
                hex = AppSettings.DEFAULTS.exportHexChars.toString()
                onSettingsChange(AppSettings.DEFAULTS)
                status = StringKey.SETTINGS_RESET_DONE
            },
        ) { Text(strings.t(StringKey.SETTINGS_RESET_DEFAULTS)) }
    }
    status?.let { Text(strings.t(it), style = MaterialTheme.typography.bodySmall) }
    Text(strings.t(StringKey.SETTINGS_RESTART_NOTE), style = MaterialTheme.typography.bodySmall)
}

/** 本地提醒设置：逐项开关默认全部关闭，只有打开开关时才请求系统权限。 */
@Composable
private fun NotificationSettingsSection(
    settings: AppSettings,
    presenter: NotificationPresenter,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val strings = LocalStrings.current
    val permissionState by presenter.state.collectAsState()
    var threshold by remember(settings) { mutableStateOf(settings.lowBatteryThreshold.toString()) }

    SectionTitle(strings.t(StringKey.SETTINGS_NOTIFY_SECTION))
    Text(
        strings.t(StringKey.SETTINGS_NOTIFY_DEFAULT_OFF),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!presenter.supported) {
        Text(
            strings.t(StringKey.SETTINGS_NOTIFY_UNSUPPORTED),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        when (permissionState) {
            BlePermissionState.GRANTED ->
                Text(
                    strings.t(StringKey.SETTINGS_NOTIFY_PERMISSION_GRANTED),
                    style = MaterialTheme.typography.bodySmall,
                )

            BlePermissionState.PERMANENTLY_DENIED -> {
                Text(
                    strings.t(StringKey.SETTINGS_NOTIFY_PERMISSION_DENIED),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { presenter.openAppSettings() }) {
                    Text(strings.t(StringKey.SETTINGS_NOTIFY_OPEN_SETTINGS))
                }
            }

            else ->
                Text(
                    strings.t(StringKey.SETTINGS_NOTIFY_PERMISSION_REQUEST),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }

    NotificationToggleRow(
        label = strings.t(StringKey.SETTINGS_NOTIFY_OTA),
        checked = settings.notifyOtaEnabled,
        onCheckedChange = { enabled ->
            if (enabled && permissionState != BlePermissionState.GRANTED) {
                presenter.requestPermission()
            }
            onSettingsChange(settings.copy(notifyOtaEnabled = enabled))
        },
    )
    NotificationToggleRow(
        label = strings.t(StringKey.SETTINGS_NOTIFY_DISCONNECT),
        checked = settings.notifyDisconnectEnabled,
        onCheckedChange = { enabled ->
            if (enabled && permissionState != BlePermissionState.GRANTED) {
                presenter.requestPermission()
            }
            onSettingsChange(settings.copy(notifyDisconnectEnabled = enabled))
        },
    )
    NotificationToggleRow(
        label = strings.t(StringKey.SETTINGS_NOTIFY_LOW_BATTERY),
        checked = settings.notifyLowBatteryEnabled,
        onCheckedChange = { enabled ->
            if (enabled && permissionState != BlePermissionState.GRANTED) {
                presenter.requestPermission()
            }
            onSettingsChange(settings.copy(notifyLowBatteryEnabled = enabled))
        },
    )
    SettingNumberField(
        label = strings.t(StringKey.SETTINGS_NOTIFY_THRESHOLD),
        value = threshold,
        min = AppSettings.MIN_LOW_BATTERY_THRESHOLD,
        max = AppSettings.MAX_LOW_BATTERY_THRESHOLD,
        onValueChange = { text ->
            threshold = text
            parseSettingInt(text, AppSettings.MIN_LOW_BATTERY_THRESHOLD, AppSettings.MAX_LOW_BATTERY_THRESHOLD)
                ?.let { onSettingsChange(settings.copy(lowBatteryThreshold = it)) }
        },
    )
}

@Composable
private fun NotificationToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 解析 1..max 的整数设置；非法返回 null。 */
private fun parseSettingInt(text: String, min: Int, max: Int): Int? =
    text.trim().toIntOrNull()?.takeIf { it in min..max }

@Composable
private fun SettingNumberField(
    label: String,
    value: String,
    min: Int,
    max: Int,
    onValueChange: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val valid = parseSettingInt(value, min, max) != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        supportingText = {
            Text(
                if (valid) {
                    strings.t(StringKey.SETTINGS_RANGE_HINT, "min" to min.toString(), "max" to max.toString())
                } else {
                    strings.t(StringKey.SETTINGS_INVALID_VALUE, "min" to min.toString(), "max" to max.toString())
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
    val firmwareKeyConfigured = rememberFirmwareSigningKeyPem()?.isNotBlank() == true
    InfoScreen(title = strings.t(StringKey.ABOUT_TITLE), onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(DesignTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            ) {
                Text(
                    strings.t(StringKey.APP_NAME),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(strings.t(StringKey.ABOUT_VERSION, "version" to AppBuildInfo.VERSION_NAME, "code" to AppBuildInfo.VERSION_CODE.toString()))
                Text(strings.t(StringKey.ABOUT_BUILD, "channel" to AppBuildInfo.BUILD_CHANNEL))
                Text(strings.t(StringKey.ABOUT_PLATFORM, "platform" to platformDisplayName()))
                Text(
                    strings.t(
                        StringKey.ABOUT_COMMIT,
                        "commit" to AppBuildInfo.BUILD_COMMIT.ifBlank {
                            strings.t(StringKey.ABOUT_COMMIT_UNKNOWN)
                        },
                    ),
                )
                Text(
                    strings.t(
                        if (firmwareKeyConfigured) {
                            StringKey.ABOUT_FIRMWARE_KEY_CONFIGURED
                        } else {
                            StringKey.ABOUT_FIRMWARE_KEY_MISSING
                        },
                    ),
                )
            }
        }
        Text(
            strings.t(StringKey.ABOUT_BUILD_INFO, "platform" to platformDisplayName()),
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle(strings.t(StringKey.ABOUT_LICENSE_TITLE))
        if (AppDependencyInfo.entries.isEmpty()) {
            Text(strings.t(StringKey.ABOUT_LICENSE_EMPTY), style = MaterialTheme.typography.bodyMedium)
        } else {
            AppDependencyInfo.entries.forEach { dependency ->
                Text(
                    strings.t(
                        StringKey.ABOUT_LICENSE_LINE,
                        "name" to dependency.name,
                        "version" to dependency.version,
                        "license" to dependency.license,
                        "scope" to strings.t(dependencyScopeKey(dependency.scope)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            strings.t(StringKey.ABOUT_LICENSE_BODY),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
    val faq = listOf(
        StringKey.HELP_Q_PERMISSION to StringKey.HELP_A_PERMISSION,
        StringKey.HELP_Q_CONNECT to StringKey.HELP_A_CONNECT,
        StringKey.HELP_Q_OTA to StringKey.HELP_A_OTA,
        StringKey.HELP_Q_LOGS to StringKey.HELP_A_LOGS,
        StringKey.HELP_Q_DEVICES to StringKey.HELP_A_DEVICES,
        StringKey.HELP_Q_QUICK to StringKey.HELP_A_QUICK,
        StringKey.HELP_Q_PARAM to StringKey.HELP_A_PARAM,
        StringKey.HELP_Q_DIAG to StringKey.HELP_A_DIAG,
        StringKey.HELP_Q_ONBOARDING to StringKey.HELP_A_ONBOARDING,
    )
    InfoScreen(title = strings.t(StringKey.HELP_TITLE), onBack = onBack) {
        faq.forEach { (question, answer) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs),
                ) {
                    Text(strings.t(question), style = MaterialTheme.typography.titleSmall)
                    Text(strings.t(answer), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
    val platform = currentStoragePlatform()
    val items = StorageInventory.forPlatform(platform)
    InfoScreen(title = strings.t(StringKey.PRIVACY_TITLE), onBack = onBack) {
        if (platform == StoragePlatform.JVM) {
            Text(strings.t(StringKey.PRIVACY_JVM_NOTE), style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle(strings.t(StringKey.PRIVACY_STORAGE_TITLE))
        items.groupBy { it.item }.forEach { (item, locations) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(DesignTokens.Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xxs),
                ) {
                    Text(strings.t(storageDescriptionKey(item)), style = MaterialTheme.typography.titleSmall)
                    locations.forEach { location ->
                        Text(
                            buildString {
                                append(strings.t(StringKey.PRIVACY_LOCATION_LABEL))
                                append(location.location)
                                if (!location.persistent) {
                                    append(" (")
                                    append(strings.t(StringKey.PRIVACY_NOT_PERSISTED))
                                    append(")")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (location.keys.isNotEmpty()) {
                            Text(
                                strings.t(StringKey.PRIVACY_KEYS_LABEL) + location.keys.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        SectionTitle(strings.t(StringKey.PRIVACY_NETWORK_TITLE))
        Text(strings.t(StringKey.PRIVACY_NETWORK_BODY), style = MaterialTheme.typography.bodyMedium)
        SectionTitle(strings.t(StringKey.PRIVACY_DELETE_TITLE))
        Text(strings.t(StringKey.PRIVACY_DELETE_BODY), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun storageDescriptionKey(item: StorageItem): StringKey = when (item) {
    StorageItem.DEVICE_PROFILES -> StringKey.PRIVACY_ITEM_PROFILES
    StorageItem.OTA_CHECKPOINT -> StringKey.PRIVACY_ITEM_OTA
    StorageItem.AUTH_TOKEN -> StringKey.PRIVACY_ITEM_TOKEN
    StorageItem.PACKET_LOG -> StringKey.PRIVACY_ITEM_LOGS
    StorageItem.APP_SETTINGS -> StringKey.PRIVACY_ITEM_SETTINGS
    StorageItem.BLE_PERMISSION_FLAG -> StringKey.PRIVACY_ITEM_PERMISSION_FLAG
    StorageItem.FIRMWARE_LIBRARY -> StringKey.PRIVACY_ITEM_FIRMWARE_LIBRARY
    StorageItem.DEVICE_EVENTS -> StringKey.PRIVACY_ITEM_EVENTS
    StorageItem.DEVICE_METRICS -> StringKey.PRIVACY_ITEM_METRICS
}

internal fun platformDisplayName(): String = when (currentStoragePlatform()) {
    StoragePlatform.ANDROID -> "Android"
    StoragePlatform.IOS -> "iOS"
    StoragePlatform.JVM -> "JVM"
}

private fun dependencyScopeKey(scope: String): StringKey = when (scope) {
    "android" -> StringKey.ABOUT_SCOPE_ANDROID
    "test" -> StringKey.ABOUT_SCOPE_TEST
    "build" -> StringKey.ABOUT_SCOPE_BUILD
    else -> StringKey.ABOUT_SCOPE_RUNTIME
}

/** 供测试断言：界面展示的存储位置必须覆盖 [StorageInventory] 的全部条目。 */
fun storageLocationItems(platform: StoragePlatform): List<StorageLocation> =
    StorageInventory.forPlatform(platform)

/** 供测试断言：隐私页文案键与存储类别一一对应。 */
fun privacyKeysForAllItems(): Map<StorageItem, StringKey> =
    StorageItem.entries.associateWith { storageDescriptionKey(it) }

/** 供测试读取当前语言下隐私声明的实际文本。 */
fun privacyNetworkDisclosure(language: ResolvedLanguage): String =
    com.valhalla.feature.i18n.AppStrings.get(language, StringKey.PRIVACY_NETWORK_BODY)
