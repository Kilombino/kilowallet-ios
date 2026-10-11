package com.kilombino.kilowallet.app

import androidx.compose.ui.window.ComposeUIViewController
import com.kilombino.kilowallet.app.ui.App
import platform.UIKit.UIViewController

private var activeModel: WalletModel? = null

fun walletDidEnterBackground() { activeModel?.onBackground() }

/** Entry point for Swift: `MainViewControllerKt.MainViewController(bridge:demo:)`. */
fun MainViewController(bridge: IosBridge, demo: String?): UIViewController {
    val platform = IosPlatform(bridge, demo?.takeIf { it.isNotBlank() })
    val model = WalletModel(platform)
    activeModel = model
    return ComposeUIViewController { App(platform, model) }
}
