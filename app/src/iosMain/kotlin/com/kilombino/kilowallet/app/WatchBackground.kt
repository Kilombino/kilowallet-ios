package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.app.i18n.phoneLanguages
import com.kilombino.kilowallet.core.tor.Tor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * iOS's background refresh for balance alerts (BGAppRefreshTask, iosApp/iosApp/BalanceWatch.swift):
 * the kept addresses against the server, one notification per change, in the app's language.
 * Swift: `WatchBackgroundKt.balanceWatchInBackground(bridge:done:)`.
 */
fun balanceWatchInBackground(bridge: IosBridge, done: (Boolean) -> Unit) {
    watchScope.launch {
        val ok = runCatching {
            val platform = IosPlatform(bridge, null)
            Tor.electrum = platform.prefs.get("tor.electrum") == "1"
            runCatching { I18n.init(platform.prefs, phoneLanguages()); I18n.ensure(I18n.lang.value) }
            for (m in BalanceWatch.check(platform)) bridge.watchNotify("Kilowallet", I18n.tr(m))
            true
        }.getOrDefault(false)
        done(ok)
    }
}
