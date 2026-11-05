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
        previous?.uncaughtException(thread, error)
    }
}
