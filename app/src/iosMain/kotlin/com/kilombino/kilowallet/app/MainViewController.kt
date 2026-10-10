package com.kilombino.kilowallet.app

import androidx.compose.ui.window.ComposeUIViewController
import com.kilombino.kilowallet.app.ui.App
import platform.UIKit.UIViewController

/** Entry point for Swift: `MainViewControllerKt.MainViewController(bridge:demo:)`. */
fun MainViewController(bridge: IosBridge, demo: String?): UIViewController {
    val platform = IosPlatform(bridge, demo?.takeIf { it.isNotBlank() })
    return ComposeUIViewController { App(platform) }
}
