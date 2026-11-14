package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.joinblaket.TakerText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared code's JoinBlaket reasons are Android's sentences; the iOS screens say every one of
 * them without the protocol's own name for the transaction (App Store naming), values kept.
 */
class JoinBlaketTextTest {
    private val banned = Regex("coin ?join|mix", RegexOption.IGNORE_CASE)

    @Test fun everyReasonIsSaidTheIosWay() {
        I18n.lang.value = I18n.EN
        val samples = listOf(
            TakerText.fill(TakerText.NO_PODLE_COIN, 5, 200_000L),
            TakerText.NO_FRESH_COMMITMENT,
            TakerText.fill(TakerText.NOT_ENOUGH_COINS, 1_002_349L, 900_000L),
            TakerText.BAD_TX, TakerText.INPUT_SPENT, TakerText.NOT_BROADCAST,
            TakerText.DIRECTORY, TakerText.CONNECTION_LOST, TakerText.fill(TakerText.NO_MAKERS, 1_000_000L),
            TakerText.fill(TakerText.FEE_CAP, 100), TakerText.CANCELLED, TakerText.NOT_CONFIRMED,
            TakerText.fill(TakerText.TOO_FEW, 1, 2), TakerText.ABOVE_CEILING, TakerText.NO_SIGNATURE, TakerText.BAD_SIGNATURE,
            TakerText.fill(TakerText.INVALID_REQUEST, "the address is not valid"),
        )
        for (s in samples) {
            val out = JoinBlaketModel.reasonText(s)
            assertTrue(!banned.containsMatchIn(out), "$s -> $out")
        }
        assertEquals("None of your coins can open this JoinBlaket payment: one needs at least 5 confirmations and at least 200 000 sats (20% of the amount).",
            JoinBlaketModel.reasonText(TakerText.fill(TakerText.NO_PODLE_COIN, 5, 200_000L)))
        assertEquals(listOf("1", "2"), TakerText.values(TakerText.TOO_FEW, TakerText.fill(TakerText.TOO_FEW, 1, 2)))
        assertEquals(null, TakerText.values(TakerText.TOO_FEW, "something else"))
    }
}
