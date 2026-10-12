package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.chain.JvmTransport
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.wallet.ScanEvent
import com.kilombino.kilowallet.core.wallet.Scanner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Talks to the real default server; runs only with KW_LIVE=1 (CI runs it too, as a smoke test). */
class LiveServerTest {
    @Test
    fun scanAgainstDefaultServer() {
        if (System.getenv("KW_LIVE") != "1") return
        val pin = System.getenv("KW_LIVE_PIN")
        require(pin != null && pin.matches(Regex("[0-9a-fA-F]{64}"))) {
            "KW_LIVE_PIN must be independently verified before a live wallet scan."
        }
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val events = runBlocking { Scanner(JvmTransport).scan(zpub, NodeEndpoint.DEFAULT, pin.lowercase(), gap = 3).toList() }
        val done = events.filterIsInstance<ScanEvent.Done>().singleOrNull()
        println(events.filter { it !is ScanEvent.Deriving && it !is ScanEvent.GapProgress }.joinToString("\n").take(3000))
        assertTrue(done != null, "scan did not finish: ${events.lastOrNull()}")
        assertTrue(done.height > 961_640)
        assertTrue(done.fingerprint?.length == 64)
    }
}
