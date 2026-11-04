package com.valhalla.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** iOS 入口，由 SwiftUI 壳调用。 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
