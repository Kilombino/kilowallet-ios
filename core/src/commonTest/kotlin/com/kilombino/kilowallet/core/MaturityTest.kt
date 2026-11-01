package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.wallet.Scanner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The coinbase maturity rule must answer exactly as the Android app's (see [MaturityVectors]). */
class MaturityTest {
    @Test
    fun constantsMatchAndroid() {
        assertEquals(MaturityVectors.HISTORICAL_MATURITY, Scanner.HISTORICAL_MATURITY)
        assertEquals(MaturityVectors.LONG_MATURITY, Scanner.LONG_MATURITY)
        assertEquals(MaturityVectors.LONG_START, Scanner.LONG_START)
    }

    @Test
    fun everyVectorMatchesAndroid() {
        val rows = MaturityVectors.TSV.trim().lines()
        assertEquals(130, rows.size)
        for (row in rows) {
            val v = row.split("\t").map { it.toInt() }
            val (h, tip) = v[0] to v[1]
            assertEquals(v[2], Scanner.maturityFor(h), "maturityFor($h)")
            assertEquals(v[3], Scanner.maturesAt(h), "maturesAt($h)")
            assertEquals(v[4] == 1, Scanner.mayBeImmature(h, tip), "mayBeImmature($h, $tip)")
            assertEquals(v[5] == 1, Scanner.immature(true, h, tip), "immature(coinbase, $h, $tip)")
            assertEquals(v[6] == 1, Scanner.immature(false, h, tip), "immature(plain, $h, $tip)")
        }
    }
}
