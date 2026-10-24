package com.kilombino.kilowallet.core.wallet

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.crypto.TxParse

/**
 * Replace-by-fee for an unconfirmed send of this wallet, from the Android app's
 * `WalletViewModel.prepareBump` and `prepareCancel` (1.2.5): the replacement spends the very same
 * coins, and must pay at least the old fee plus 1 sat/vB of its own size, plus one sat (BIP-125
 * rules 3 and 4; full RBF is on by default in Knots and Bitcoin Core 28+, so a send is replaceable
 * even if it had not signalled it). The review and the signing are a normal send's.
 */
object ReplacePlanner {

    /** The fee the replacement must pay at least: [rate] for its size, and the old fee + 1 sat/vB of its size + 1. */
    fun replacementFee(nIn: Int, outputScripts: List<ByteArray>, rate: Double, oldFee: Long): Long = maxOf(
        SendPlanner.estimateFee(nIn, outputScripts, rate.coerceIn(SendPlanner.MIN_RATE, SendPlanner.MAX_RATE)),
        oldFee + SendPlanner.estimateFee(nIn, outputScripts, 1.0) + 1,
    )

    /**
     * Speed up [original] (txid [txid]): the same inputs ([coins], in its input order) and the same
     * recipients; the extra fee comes out of its change, the largest output to one of this wallet's
     * change addresses ([isChange]).
     */
    fun bump(txid: String, original: TxParse.Tx, coins: List<SpendableUtxo>, isChange: (ByteArray) -> Boolean, rate: Double): SendDraft {
        require(coins.size == original.inputs.size) { "This transaction spends coins that are not in this wallet: it can't be replaced from here." }
        val outs = original.outputs.map { TxBuilder.Output(it.scriptPubKey, it.value) }
        val changeIdx = outs.indices.filter { isChange(outs[it].scriptPubKey) }.maxByOrNull { outs[it].value }
            ?: error("This send has no change output to pay a higher fee from.")
        val oldFee = coins.sumOf { it.value } - outs.sumOf { it.value }
        val newFee = replacementFee(coins.size, outs.map { it.scriptPubKey }, rate, oldFee)
        val change = outs[changeIdx].value - (newFee - oldFee)
        require(change >= dustFor(outs[changeIdx].scriptPubKey)) {
            "The change (${outs[changeIdx].value} sats) can't cover the new fee of $newFee sats."
        }
        val newOuts = outs.mapIndexed { n, o -> if (n == changeIdx) TxBuilder.Output(o.scriptPubKey, change) else o }
        val sent = newOuts.filterIndexed { n, _ -> n != changeIdx }
        val to = sent.firstOrNull()?.let { Address.fromScriptPubKey(it.scriptPubKey) } ?: "(replacement)"
        return SendDraft(to, sent.sumOf { it.value }, newFee, change, coins, newOuts, rate,
            replaces = txid, replacedFee = oldFee)
    }

    /**
     * Cancel [original] (txid [txid]): the same coins, all of them to [backTo] (a fresh change
     * address of this wallet), so the recipients get nothing. If the original confirms first, the
     * network simply refuses this one.
     */
    fun cancel(txid: String, original: TxParse.Tx, coins: List<SpendableUtxo>, backTo: ByteArray, rate: Double): SendDraft {
        require(coins.size == original.inputs.size) { "This transaction spends coins that are not in this wallet: it can't be cancelled from here." }
        val sum = coins.sumOf { it.value }
        val oldFee = sum - original.outputs.sumOf { it.value }
        val newFee = replacementFee(coins.size, listOf(backTo), rate, oldFee)
        val back = sum - newFee
        require(back >= dustFor(backTo)) { "The coins ($sum sats) can't cover a $newFee-sat fee." }
        return SendDraft(Address.fromScriptPubKey(backTo) ?: "(this wallet)", back, newFee, 0, coins,
            listOf(TxBuilder.Output(backTo, back)), rate, replaces = txid, replacedFee = oldFee, cancels = true)
    }
}
