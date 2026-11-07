package com.valhalla.feature.i18n

/**
 * 业务/平台层向 UI 传递的可本地化消息。
 *
 * 逻辑层只产出「key + 参数」，不拼中文；UI 用 [render] 在进入界面时按当前语言格式化，
 * 这样语言切换后已存在的状态也能立即跟随。
 */
data class UiMessage(
    val key: StringKey,
    val args: Map<String, String> = emptyMap(),
    /** 嵌套消息（如「失败原因」本身也需要本地化），渲染时先递归解析再填充占位符。 */
    val nested: Map<String, UiMessage> = emptyMap(),
) {
    companion object {
        fun of(
            key: StringKey,
            vararg args: Pair<String, String>,
        ): UiMessage = UiMessage(key, args.toMap())
    }
}

fun StringResolver.render(message: UiMessage): String {
    val args = message.args + message.nested.mapValues { (_, value) -> render(value) }
    return AppStrings.format(language, message.key, args)
}
