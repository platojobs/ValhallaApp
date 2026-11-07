@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.valhalla.feature.error

import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSException
import platform.Foundation.NSSetUncaughtExceptionHandler

private var installed = false
private var globalReporter: ErrorReporter? = null

private fun handleUncaughtException(exception: NSException?) {
    val current = exception ?: return
    val error =
        IllegalStateException(
            "NSException ${current.name}: ${current.reason ?: "unknown"}",
        )
    runCatching {
        globalReporter?.record("uncaught:ios", error, ErrorKind.FAILURE)
    }
}

actual fun installUncaughtExceptionBridge(reporter: ErrorReporter) {
    if (installed) return
    installed = true
    globalReporter = reporter

    NSSetUncaughtExceptionHandler(staticCFunction(::handleUncaughtException))
}
