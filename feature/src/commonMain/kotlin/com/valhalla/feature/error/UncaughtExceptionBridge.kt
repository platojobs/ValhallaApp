package com.valhalla.feature.error

/**
 * 把宿主未捕获异常桥接到 [ErrorReporter]：
 * - Android/JVM：`Thread.setDefaultUncaughtExceptionHandler`；
 * - iOS：`NSSetUncaughtExceptionHandler`（仅 Objective-C 异常；Kotlin/Native 未捕获异常
 *   不会经过该回调，属于已知边界）。
 *
 * 桥接只做本地环形记录 + 脱敏后批量投递，不阻止原始处理器继续执行。
 */
expect fun installUncaughtExceptionBridge(reporter: ErrorReporter)
