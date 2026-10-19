package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.dustFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Bitcoin Core's dust limits at 3 sat/vB, by output type (Android app: ui/DustTest.kt, e4c968d). */
class DustTest {
    private fun s(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun limits() {
        assertEquals(294L, dustFor(s(0x00, 0x14) + ByteArray(20)))   // bc1q
        assertEquals(330L, dustFor(s(0x51, 0x20) + ByteArray(32)))   // bc1p
        assertEquals(330L, dustFor(s(0x00, 0x20) + ByteArray(32)))   // P2WSH
        assertEquals(540L, dustFor(s(0xa9, 0x14) + ByteArray(20) + s(0x87))) // P2SH
        assertEquals(546L, dustFor(s(0x76, 0xa9, 0x14) + ByteArray(20) + s(0x88, 0xac))) // P2PKH
    }

    private val coin = SpendableUtxo("ab".repeat(32), 0, 50_000, 0, 0, 100)
    private val bc1qChange = s(0x00, 0x14) + ByteArray(20) { 0x11 }
    private val taprootChange = s(0x51, 0x20) + ByteArray(32) { 0x22 }

    @Test fun recipientHeldToItsOwnType() {
        val bc1q = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val p2pkh = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        assertEquals(294, SendPlanner.plan(bc1q, 294, 1.0, listOf(coin), bc1qChange).amount)
        val low = assertFailsWith<IllegalArgumentException> { SendPlanner.plan(bc1q, 293, 1.0, listOf(coin), bc1qChange) }
        assertTrue(low.message!!.contains("294"))
        assertFailsWith<IllegalArgumentException> { SendPlanner.plan(p2pkh, 545, 1.0, listOf(coin), bc1qChange) }
        assertEquals(546, SendPlanner.plan(p2pkh, 546, 1.0, listOf(coin), bc1qChange).amount)
    }

    @Test fun taprootChangeUnder330GoesToTheFee() {
        val to = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        // 152.5 vB at 1 sat/vB with a Taproot change output: 50 000 − 49 527 − 153 = 320 sats of change.
        val d = SendPlanner.plan(to, 49_527, 1.0, listOf(coin), taprootChange)
        assertEquals(1, d.outputs.size)
        assertEquals(473, d.fee)
        // The same 320 sats would be kept as bc1q change (over 294).
        val q = SendPlanner.plan(to, 49_527 - 12, 1.0, listOf(coin), bc1qChange)
        assertEquals(2, q.outputs.size)
    }
}
