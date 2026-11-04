package com.valhalla.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.valhalla.core.AppBuildInfo
import com.valhalla.data.settings.StorageItem
import com.valhalla.data.settings.StorageLocation
import com.valhalla.data.settings.StoragePlatform
import com.valhalla.data.settings.currentStoragePlatform
import com.valhalla.data.settings.StorageInventory
import com.valhalla.feature.i18n.AppLanguageMode
import com.valhalla.feature.i18n.LocalStrings
import com.valhalla.feature.i18n.ResolvedLanguage
import com.valhalla.feature.i18n.StringKey
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
    onLanguageChange: (AppLanguageMode) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    onPrivacy: () -> Unit,
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
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
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

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
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
            }
        }
        Text(
            strings.t(StringKey.ABOUT_BUILD_INFO, "platform" to platformDisplayName()),
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle(strings.t(StringKey.ABOUT_LICENSE_TITLE))
        Text(
            strings.t(StringKey.ABOUT_LICENSE_BODY),
            style = MaterialTheme.typography.bodyMedium,
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
}

private fun platformDisplayName(): String = when (currentStoragePlatform()) {
    StoragePlatform.ANDROID -> "Android"
    StoragePlatform.IOS -> "iOS"
    StoragePlatform.JVM -> "JVM"
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
