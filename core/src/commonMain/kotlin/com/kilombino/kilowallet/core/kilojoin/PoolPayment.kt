package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bech32
import com.kilombino.kilowallet.core.crypto.TxParse

/**
 * Paying through a pool, from SEND: the Android app's `coinjoin/CoinjoinPayment.kt`. When every
 * recipient gets the same amount, a v4 pool of that amount opens, one equal output per recipient,
 * those outputs going straight to the recipients' addresses. With different amounts it is a
 * payment pool (v5): the others put in the same total and get it back in equal outputs.
 *
 * The payment only goes out when the pool closes; if it never fills, nothing is paid and the coins
 * stay here. The recipients' addresses are theirs: never derived, reserved or watched as this
 * wallet's.
 */
object PoolPayment {
    /**
     * Whether the recipients fit a pool: [type] when they do. [ok]: all the same [amount], a pool of
     * equal outputs; [paymentOk]: a payment pool of their [amounts] (v5); else [reason] says why not.
     */
    class Check(val type: PoolType?, val amount: Long, val scripts: List<ByteArray>, val reason: String?,
                val amounts: List<Long> = emptyList(), val paymentOk: Boolean = false) {
        val ok: Boolean get() = reason == null && amount > 0
        val any: Boolean get() = ok || paymentOk
    }

    /** One address type for every recipient, and this app's pools are native SegWit. */
    const val NEEDS = "Needs native SegWit (bc1q) addresses only."

    /**
     * Whether [recipients] (address, sats) can be paid by one pool: 1 to [Protocol.MAX_OUTPUTS] of
     * them, all plain native SegWit (bc1q) addresses (this app has no Taproot pools), none twice.
     * [taprootOk] is the Android app's switch, kept for the vectors; this app passes false.
     */
    fun check(recipients: List<Pair<String, Long>>, taprootOk: Boolean = false, minAmount: Long = Protocol.MIN_AMOUNT): Check {
        fun no(reason: String) = Check(null, 0, emptyList(), reason)
        if (recipients.isEmpty()) return no(NEEDS)
        if (recipients.size > Protocol.MAX_OUTPUTS) return no("A pool gives at most ${Protocol.MAX_OUTPUTS} outputs: ${Protocol.MAX_OUTPUTS} recipients at most.")
        val amount = recipients.first().second
        val scripts = recipients.map { (a, _) ->
            val t = a.trim()
            if (t.contains("@") || t.lowercase().startsWith("sp1")) return no("Only plain bc1q addresses can be paid through a pool.")
            runCatching { Address.decodeToScriptPubKey(t) }.getOrNull() ?: return no(NEEDS)
        }
        val type = when {
            scripts.all { PoolTx.isType(it, PoolType.P2WPKH) } -> PoolType.P2WPKH
            scripts.all { PoolTx.isType(it, PoolType.P2TR) } -> PoolType.P2TR
            else -> return no(NEEDS)
        }
        if (type == PoolType.P2TR && !taprootOk) return no(NEEDS)
        if (scripts.map { it.toList() }.toSet().size != scripts.size) return no("The same address twice: every output of a pool is a different address.")
        val amounts = recipients.map { it.second }
        val total = amounts.sum()
        // A payment pool (v5): any amounts above dust, all of them together within a pool's limits.
        val paymentOk = amounts.all { it >= Protocol.MIN_PAYMENT } && total in minAmount..Protocol.MAX_AMOUNT
        // The same amount to everyone (v4): each a pool's output, all of them together a person's minimum.
        val equal = amounts.all { it == amount } && amount in Protocol.TEST_MIN_AMOUNT..Protocol.MAX_AMOUNT && total >= minAmount
        if (!equal && !paymentOk && total < minAmount && amounts.all { it >= Protocol.MIN_PAYMENT }) return Check(type, 0, scripts,
            "Together they add up to $total sats: a pool needs at least $minAmount sats in all.", amounts, false)
        if (!equal && !paymentOk) return Check(type, 0, scripts,
            "Each payment must be at least ${Protocol.MIN_PAYMENT} sats, and all of them together $minAmount to ${Protocol.MAX_AMOUNT} sats.",
            amounts, false)
        return Check(type, if (equal) amount else 0, scripts, null, amounts, paymentOk)
    }

    /** The bc1q / bc1p address of a recipient's script. */
    fun address(script: ByteArray): String = when {
        PoolTx.isType(script, PoolType.P2WPKH) -> Bech32.encodeSegwit("bc", 0, script.copyOfRange(2, 22))
        PoolTx.isType(script, PoolType.P2TR) -> Bech32.encodeSegwit("bc", 1, script.copyOfRange(2, 34))
        else -> error("not a pool's address type")
    }
}

/** Which coins came out of a round (the Android app's `coinjoin/MixedCoins.kt`), as the chain shows it. */
object PooledCoins {
    /**
     * True when output [vout] of [rawHex] is one of a Kilojoin round's equal outputs: two or more
     * outputs of its exact amount, two or more inputs, at most one change per input, every output
     * of one type, and every input signed with the unified sighash 0x21 that Kilojoin always uses.
     */
    fun looksPooled(rawHex: String, vout: Int): Boolean = runCatching {
        val tx = TxParse.parse(rawHex)
        val mine = tx.outputs.getOrNull(vout) ?: return false
        val v = mine.value
        if (v < Protocol.TEST_MIN_AMOUNT || v % 1000 != 0L) return false
        val equal = tx.outputs.count { it.value == v }
        if (equal < 2 || tx.inputs.size < 2 || tx.outputs.size - equal > tx.inputs.size) return false
        if (tx.outputs.any { it.scriptPubKey.size != mine.scriptPubKey.size || it.scriptPubKey[0] != mine.scriptPubKey[0] }) return false
        tx.inputs.all { i -> i.witness.isNotEmpty() && i.witness[0].size >= 64 && (i.witness[0].last().toInt() and 0xFF) == 0x21 }
    }.getOrDefault(false)
}

/** Where a seat's equal outputs go (the Android app's `coinjoin/Destination.kt`): the address walk. */
object PoolAddresses {
    /** The gap limit of the wallet's own scan (20). */
    const val GAP = 20

    /**
     * [n] indexes of a receive branch ([derive] by index) past the last one with history: from
     * [from], it walks until [GAP] empty addresses in a row, then takes the first unused ones not
     * in [held]. Throws if that would go further than [GAP] past the last used address: the
     * wallet's scan would not find them.
     */
    fun pickUnusedIndexes(n: Int, from: Int, held: Set<List<Byte>>, derive: (Int) -> ByteArray,
                          hasHistory: (ByteArray) -> Boolean): List<Int> {
        var i = from
        var lastUsed = from - 1
        var empty = 0
        while (empty < GAP) {
            if (hasHistory(derive(i))) { lastUsed = i; empty = 0 } else empty++
            i++
        }
        val out = mutableListOf<Int>()
        var j = lastUsed + 1
        while (out.size < n) {
            require(j - lastUsed <= GAP) {
                "That wallet has too many addresses waiting for rounds: finish or cancel some first, " +
                    "or its own scan would not see the next ones."
            }
            if (derive(j).toList() !in held) out += j
            j++
        }
        return out
    }
}
