package com.valhalla.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.valhalla.feature.ui.ValhallaApp

/** 跨平台 App 入口：只负责主题与特性层根界面装配。 */
@Composable
fun App(startRoute: String? = null) {
    MaterialTheme {
        ValhallaApp(startRoute = startRoute)
    }
}
