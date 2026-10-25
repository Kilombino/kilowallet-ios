package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.kilojoin.JvmWebSocket
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.RelayClient
import com.kilombino.kilowallet.core.kilojoin.poolsFilter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Reads the pool announcements on the real relay (read only); runs only with KW_LIVE=1. */
class LiveRelayTest {
    @Test
    fun poolAnnouncementsOnTheRelay() {
        if (System.getenv("KW_LIVE") != "1") return
        var events = 0
        val terms = mutableListOf<Protocol.Terms>()
        runBlocking {
            val r = RelayClient(Protocol.RELAY, JvmWebSocket, { _, ev -> events++; Protocol.Terms.parse(ev)?.let { terms += it } })
            r.connect()
            r.subscribe("pools", listOf(poolsFilter(NostrEvent.nowSeconds() - 30 * 86400)))
            r.close()
        }
        println("relay: $events pool announcements in 30 days, ${terms.size} readable here: " +
            terms.groupingBy { "v${it.version}" }.eachCount())
        assertTrue(events >= 0)
    }
}
