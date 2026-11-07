package com.valhalla.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController =
    ComposeUIViewController {
        val route by NotificationRouteBridge.route.collectAsState()
        valhallaRoot(startRoute = route)
    }
