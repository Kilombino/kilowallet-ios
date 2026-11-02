package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.WebSocket
import com.kilombino.kilowallet.core.kilojoin.WebSocketListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The relay's way with and without Tor (the same behaviour as Android's RelayClient). */
class RelayRouteTest {
    private val calls = ArrayList<String>()
    private val ws = object : WebSocket { override fun send(text: String) = true; override fun ping() = true; override fun close() {} }
    private val listener = object : WebSocketListener { override fun onText(text: String) {}; override fun onPong() {}; override fun onClosed(error: String?) {} }

    private fun route(tor: Int?, onionWorks: Boolean, clearWorks: Boolean = true) = RelayRoute(
        torPort = { tor },
        direct = { url, _ -> calls += "direct $url"; ws },
        viaTor = { url, _, port, iso ->
            calls += "tor:$port $url $iso"
            if (url == Protocol.RELAY_ONION && !onionWorks) error("onion unreachable")
            if (url == Protocol.RELAY && !clearWorks) error("no route")
            ws
        })

    @Test fun withTorTheOnionFirst() {
        val r = route(9050, onionWorks = true)
        r.openIsolated(Protocol.RELAY, listener, "kj-abc")
        assertEquals(listOf("tor:9050 ${Protocol.RELAY_ONION} kj-abc"), calls)
        assertEquals("onion", r.last)
    }

    @Test fun anOnionThatFailsFallsBackToWssOverTorOnTheSameCircuit() {
        val r = route(9050, onionWorks = false)
        r.openIsolated(Protocol.RELAY, listener, "kj-out-1")
        assertEquals(listOf("tor:9050 ${Protocol.RELAY_ONION} kj-out-1", "tor:9050 ${Protocol.RELAY} kj-out-1"), calls)
        assertEquals("wss over Tor", r.last)
    }

    @Test fun theErrorOnlyWhenBothFail() {
        val e = assertFailsWith<IllegalStateException> { route(9050, onionWorks = false, clearWorks = false).open(Protocol.RELAY, listener) }
        assertTrue("onion unreachable" in e.message!! && "no route" in e.message!!)
    }

    @Test fun withoutTorNothingChanges() {
        val r = route(null, onionWorks = true)
        r.openIsolated(Protocol.RELAY, listener, "kj-abc")
        assertEquals(listOf("direct ${Protocol.RELAY}"), calls)
        assertEquals("direct", r.last)
    }
}
