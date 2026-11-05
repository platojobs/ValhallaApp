package com.valhalla.feature.error

private var installed = false

actual fun installUncaughtExceptionBridge(reporter: ErrorReporter) {
    if (installed) return
    installed = true

    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            reporter.record("uncaught:${thread.name}", error, ErrorKind.FAILURE)
        }
        // 保持原有行为（默认处理器会终止进程并打印日志），避免吞掉系统崩溃流程。
        previous?.uncaughtException(thread, error)
    }
}
