package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.ark.Ark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * iOS's background run for Ark (BGProcessingTask, iosApp/iosApp/iOSApp.swift): start the engine and
 * renew coins that expire within two weeks, waiting up to [waitS] seconds for the round. Calls
 * [done] with true when nothing needed renewing or the renewal finished. Swift:
 * `ArkBackgroundKt.arkRenewInBackground(bridge:waitS:done:)`.
 */
fun arkRenewInBackground(bridge: IosBridge, waitS: Int, done: (Boolean) -> Unit) {
    background.launch {
        val ok = runCatching {
            val ark = IosPlatform(bridge, null).ark ?: return@runCatching true
            Ark.engine = ark.engine; Ark.host = ark.host
            if (!Ark.enabled() || !Ark.hasWords()) return@runCatching true
            Ark.ensureStarted()
            if (!Ark.hasWallet()) return@runCatching true
            val left = Ark.blocksToNearestExpiry() ?: return@runCatching true
            if (left >= 2 * ArkModel.RENEW_WITHIN_BLOCKS) return@runCatching true
            val known = Ark.history().filter { it.kind == "renewal" }.map { it.id }.toSet()
            Ark.refreshAll()
            repeat(waitS / 5) {
                delay(5_000)
                val m = Ark.history().firstOrNull { it.kind == "renewal" && it.id !in known }
                if (m != null && m.status != "pending") return@runCatching m.status == "successful"
            }
            false
        }.getOrDefault(false)
        done(ok)
    }
}
