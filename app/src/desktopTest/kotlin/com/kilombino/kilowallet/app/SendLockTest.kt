package com.kilombino.kilowallet.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** confirmSend refuses to sign when a coin of the payment is now held by a closing/signing Kilojoin round. */
class SendLockTest {
    @Test
    fun aCoinTakenByARoundIsRefused() {
        assertTrue(takenByRound(listOf("aa:0", "bb:1"), setOf("bb:1")))
    }

    @Test
    fun freeCoinsPass() {
        assertFalse(takenByRound(listOf("aa:0", "bb:1"), setOf("cc:2")))
        assertFalse(takenByRound(listOf("aa:0"), emptySet()))
    }
}
