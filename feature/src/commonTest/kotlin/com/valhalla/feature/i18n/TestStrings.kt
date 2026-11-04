package com.valhalla.feature.i18n

/** 测试辅助：固定语言渲染 [UiMessage]，避免断言依赖系统语言。 */
internal fun UiMessage.asChinese(): String = StringResolver(ResolvedLanguage.CHINESE).render(this)

internal fun UiMessage.asEnglish(): String = StringResolver(ResolvedLanguage.ENGLISH).render(this)
