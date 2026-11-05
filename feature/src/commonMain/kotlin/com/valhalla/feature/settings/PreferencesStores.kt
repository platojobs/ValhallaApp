package com.valhalla.feature.settings

import com.valhalla.core.automation.AutomationRuleStore
import com.valhalla.core.config.ConfigPresetStore
import com.valhalla.data.settings.AppSettingsStore
import com.valhalla.data.settings.SettingsKeys

/** 预设存储到平台偏好存储（整表字符串）。 */
class PreferencesConfigPresetStore(private val store: AppSettingsStore) : ConfigPresetStore {

    override fun loadRaw(): String? = store.read(SettingsKeys.CONFIG_PRESETS)

    override fun saveRaw(raw: String) {
        store.write(SettingsKeys.CONFIG_PRESETS, raw)
    }
}

/** 自动化规则存储到平台偏好存储（整表字符串）。 */
class PreferencesAutomationRuleStore(private val store: AppSettingsStore) : AutomationRuleStore {

    override fun loadRaw(): String? = store.read(SettingsKeys.AUTOMATION_RULES)

    override fun saveRaw(raw: String) {
        store.write(SettingsKeys.AUTOMATION_RULES, raw)
    }
}
