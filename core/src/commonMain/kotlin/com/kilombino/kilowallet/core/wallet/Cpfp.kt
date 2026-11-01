package com.kilombino.kilowallet.core.wallet

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.crypto.TxParse
import kotlin.math.ceil

/**
 * Child pays for parent, from the Android app's `WalletViewModel.prepareCpfp`: a stuck unconfirmed
 * transaction that paid this wallet is pulled along by spending our outputs of it back to
 * ourselves, with a fee big enough that parent and child together pay the rate asked. Works for
 * any transaction that pays us, including ones we did not send (where RBF is impossible).
 */
object CpfpPlanner {

    /**
     * The parent's virtual size, not trusting its served witness: the txid covers the transaction
     * without it, so a server could pad the witness and make the child pay for bytes that are not
     * there. Each input's witness is counted from its type (P2WPKH 108 WU, Taproot with the unified
     * sighash 67); the served size is used only when it is smaller, or when a type is unknown.
     */
    fun parentVsize(raw: String, spentScripts: List<ByteArray>): Int {
        val served = TxParse.vsize(raw)
        val witnessWu = spentScripts.map { sc ->
            when {
                sc.size == 22 && sc[0] == 0.toByte() -> 108            // P2WPKH
                sc.size == 34 && sc[0] == 0x51.toByte() -> 67          // P2TR key path + 0x21
                sc.size == 23 && sc[0] == 0xa9.toByte() -> 108         // P2SH-P2WPKH (the usual case)
                sc.size == 25 && sc[0] == 0x76.toByte() -> 0           // P2PKH: no witness
                else -> -1                                              // can't tell
            }
        }
        if (witnessWu.any { it < 0 }) return served
        val wu = TxParse.baseSize(raw) * 4 + witnessWu.sum() + if (witnessWu.any { it > 0 }) 2 else 0
        return minOf(served, (wu + 3) / 4)
    }

    /**
     * Plan the child: [ours] are the parent's unspent outputs to this wallet, [to] a fresh change
     * script of ours, [parentFee] and [parentVsize] the parent's. Throws with a readable message
     * when the rate is not above what the parent already pays or our part can't cover the fee.
     */
    fun plan(parentTxid: String, parentFee: Long, parentVsize: Int, ours: List<SpendableUtxo>, to: ByteArray, ratePerVb: Double): SendDraft {
        require(ours.isNotEmpty()) { "This transaction has no unspent output of this wallet to speed it up with." }
        val rate = ratePerVb.coerceIn(1.0, 1000.0)
        val childVsize = SendPlanner.estimateFee(ours.size, listOf(to), 1.0, ours.count { it.type == com.kilombino.kilowallet.core.crypto.ScriptType.P2TR })
        val need = ceil(rate * (parentVsize + childVsize)).toLong() - parentFee
        require(need > childVsize) {
            val paid = (parentFee * 10 / parentVsize) / 10.0
            "It already pays $paid sat/vB: choose a higher rate than that."
        }
        val sum = ours.sumOf { it.value }
        require(sum - need > SendPlanner.DUST_SATS) { "Your part of it ($sum sats) can't cover a $need-sat fee." }
        return SendDraft(Address.fromScriptPubKey(to) ?: "(this wallet)", sum - need, need, 0, ours,
            listOf(TxBuilder.Output(to, sum - need)), rate,
            cpfpParent = parentTxid, packageRate = (parentFee + need).toDouble() / (parentVsize + childVsize))
    }
}
