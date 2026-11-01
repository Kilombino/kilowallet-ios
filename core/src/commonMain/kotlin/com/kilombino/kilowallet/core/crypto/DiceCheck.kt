package com.kilombino.kilowallet.core.crypto

import kotlin.math.pow

/**
 * A sanity check on hand-typed dice rolls, after Coldcard's (the Android app's `crypto/DiceCheck.kt`): real rolls of a fair die look
 * random, while numbers made up on the spot ("I have no dice, I'll just tap") rarely do. People
 * avoid repeats, drift into runs like 1-2-3-4, or lean on a few favourite faces. Nothing here
 * proves the rolls are real; it only catches the patterns that give a made-up sequence away, so
 * the app can ask the user to roll actual dice. Every threshold is far out in the tail of what a
 * fair die produces (well under one false alarm in a thousand seeds), so honest rolls pass.
 */
object DiceCheck {
    /** Why [rolls] (digits '1'..'6') don't look like real dice, or an empty list when they do. */
    fun problems(rolls: String): List<String> {
        val r = rolls.filter { it in '1'..'6' }.map { it - '0' }
        val n = r.size
        if (n < 30) return emptyList()
        val out = mutableListOf<String>()

        // Faces: chi-square against six equal counts (5 degrees of freedom). 25 is p ≈ 0.0001.
        val counts = IntArray(7).also { c -> r.forEach { c[it]++ } }
        val e = n / 6.0
        val chi = (1..6).sumOf { (counts[it] - e) * (counts[it] - e) / e }
        if (chi > 25.0) out += "Some numbers come up far more often than others, which a fair die doesn't do."
        if ((1..6).count { counts[it] == 0 } >= 1 && n >= 50) out += "A face never came up in $n rolls."

        // Repeats: a real die repeats the last number one time in six. Made-up sequences avoid it.
        val repeats = (1 until n).count { r[it] == r[it - 1] }
        if (binomCdf(repeats, n - 1, 1.0 / 6) < 1e-3) out += "Hardly any number repeats right after itself, which real dice do about one roll in six."
        // …or overdo it, typing the same key again and again.
        var run = 1; var longest = 1
        for (i in 1 until n) { run = if (r[i] == r[i - 1]) run + 1 else 1; if (run > longest) longest = run }
        if (longest >= 8) out += "The same number comes $longest times in a row."

        // Walking up or down the keypad: 1-2-3-4… A step of +1 or −1 happens 10 times in 36.
        val steps = (1 until n).count { kotlin.math.abs(r[it] - r[it - 1]) == 1 }
        if (1 - binomCdf(steps - 1, n - 1, 10.0 / 36) < 1e-4) out += "Too many rolls step up or down by one (like 1-2-3-4), the way fingers walk over keys."

        // Too few distinct pairs: a short pattern typed over and over.
        val pairs = (1 until n).map { r[it - 1] * 10 + r[it] }.toSet().size
        if (n >= 50 && pairs < 20) out += "The same few pairs of numbers repeat over and over."
        return out
    }

    /** P(X ≤ k) for X ~ Binomial(n, p). */
    private fun binomCdf(k: Int, n: Int, p: Double): Double {
        if (k < 0) return 0.0
        var term = (1 - p).pow(n.toDouble()); var sum = term
        for (i in 1..minOf(k, n)) { term *= (n - i + 1).toDouble() / i * p / (1 - p); sum += term }
        return minOf(1.0, sum)
    }
}
