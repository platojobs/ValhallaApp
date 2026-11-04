package com.valhalla.data.ble

import android.content.Context

/**
 * Android 端 BLE 初始化入口：App 壳在 `Application.onCreate` 或 `MainActivity` 中调用一次，
 * 之后 [createBleClient] 才能拿到 Application Context。
 */
object AndroidBleSupport {

    @Volatile
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    internal fun requireContext(): Context =
        applicationContext ?: throw IllegalStateException("请先调用 AndroidBleSupport.init(context)")
}

actual fun createBleClient(): BleClient = AndroidBleClient(AndroidBleSupport.requireContext())
