package com.kilombino.kilowallet.core.wallet

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.TxBuilder
import kotlin.math.ceil
import kotlin.math.roundToLong

/** A reviewed, not yet signed, spend: everything the confirmation screen shows. */
data class SendDraft(
    val to: String,
    val amount: Long,
    val fee: Long,
    val change: Long,
    val inputs: List<SpendableUtxo>,
    val outputs: List<TxBuilder.Output>,
    val feeRate: Double,
) {
    val totalSent: Long get() = amount + fee
    /** The fee rate actually paid, from the estimated size. */
    val effectiveRate: Double get() = fee.toDouble() / SendPlanner.estimateVbytes(inputs.size, outputs.map { it.scriptPubKey })
}

/**
 * Coin selection and fees, from the Android app's `WalletViewModel.prepareSend`, `maxSendable`,
 * `estimateFee` and `feeConcern`: largest coins first, a change output only above dust, dust
 * change folded into the fee, outputs sized by their real script length; each output held to its
 * own type's dust limit ([dustFor], Android e4c968d).
 */
object SendPlanner {
    /** Below this, a change output costs more to spend later than it is worth: fold it into the fee. */
    const val DUST_SATS = 294L
    const val MIN_RATE = 0.1
    const val MAX_RATE = 1000.0

    /** P2WPKH inputs and these exact outputs (8 + 1 + script bytes each). */
    fun estimateVbytes(nIn: Int, outputScripts: List<ByteArray>): Double =
        10.5 + 68.0 * nIn + outputScripts.sumOf { 9.0 + it.size }

    fun estimateFee(nIn: Int, outputScripts: List<ByteArray>, ratePerVb: Double): Long =
        ceil(estimateVbytes(nIn, outputScripts) * ratePerVb).toLong()

    /** The most a send can move from [utxos] at [ratePerVb]: everything minus a one-output fee (sized as Taproot). */
    fun maxSendable(ratePerVb: Double, utxos: List<SpendableUtxo>): Long {
        if (utxos.isEmpty()) return 0
        val fee = estimateFee(utxos.size, listOf(ByteArray(34)), ratePerVb.coerceIn(MIN_RATE, MAX_RATE))
        return (utxos.sumOf { it.value } - fee).coerceAtLeast(0)
    }

    /**
     * Plan a spend of [amountSats] to [toAddress] (or everything, when [sendAll]) at [feeRatePerVb]
     * from [utxos], with change to [changeScript]. Throws with a readable message when it can't.
     */
    fun plan(
        toAddress: String, amountSats: Long, feeRatePerVb: Double, utxos: List<SpendableUtxo>,
        changeScript: ByteArray, sendAll: Boolean = false,
    ): SendDraft {
        val to = toAddress.trim()
        require(!to.lowercase().startsWith("sp1")) { "Silent payment addresses are not supported in this version." }
        require(!to.contains("@")) { "Handles like user@domain are not supported in this version: paste the address." }
        val toScript = Address.decodeToScriptPubKey(to)
        Address.requireSpendableDestination(toScript, "The address")
        val rate = feeRatePerVb.coerceIn(MIN_RATE, MAX_RATE)
        require(utxos.isNotEmpty()) { "No spendable coins yet." }

        val withChange = listOf(toScript, changeScript)
        val noChange = listOf(toScript)

        if (sendAll) {
            val sum = utxos.sumOf { it.value }
            val fee = estimateFee(utxos.size, noChange, rate)
            val amount = sum - fee
            require(amount >= dustFor(toScript)) { "The coins ($sum sats) do not cover the fee ($fee sats)." }
            return SendDraft(to, amount, fee, 0, utxos, listOf(TxBuilder.Output(toScript, amount)), rate)
        }

        require(amountSats > 0) { "Enter an amount." }
        // An output under its type's dust limit is refused by the network: say so here.
        dustFor(toScript).let { d -> require(amountSats >= d) { "Enter at least $d sats: less can't be sent to that kind of address." } }
        val chosen = mutableListOf<SpendableUtxo>()
        var sum = 0L
        for (u in utxos.sortedByDescending { it.value }) {
            chosen += u; sum += u.value
            if (sum >= amountSats + estimateFee(chosen.size, withChange, rate)) break
        }
        var fee = estimateFee(chosen.size, withChange, rate)
        var noChangeOutput = false
        if (sum < amountSats + fee) { fee = estimateFee(chosen.size, noChange, rate); noChangeOutput = true }
        require(sum >= amountSats + fee) { "Not enough funds for the amount plus fee." }
        var change = sum - amountSats - fee
        val outputs = mutableListOf(TxBuilder.Output(toScript, amountSats))
        // Taproot change has a higher dust limit (330) than bc1q (294).
        if (change > maxOf(DUST_SATS, dustFor(changeScript) - 1) && !noChangeOutput) {
            outputs += TxBuilder.Output(changeScript, change)
        } else {
            fee = sum - amountSats
            change = 0
        }
        return SendDraft(to, amountSats, fee, change, chosen, outputs, rate)
    }

    /** Whether the fee looks like a slip (> 100 sat/vB, > 100 000 sats, or > 5% of the amount). */
    fun feeConcern(d: SendDraft): String? {
        val rate = d.effectiveRate
        val reasons = buildList {
            if (rate > 100) add("${rate.roundToLong()} sat/vB")
            if (d.fee > 100_000) add("${formatSats(d.fee)} sats")
            if (d.totalSent > 0 && d.fee * 100 > d.totalSent * 5) add("${d.fee * 100 / d.totalSent}% of the amount")
        }
        return if (reasons.isEmpty()) null else "This fee is high: " + reasons.joinToString(", ") + "."
    }

    /** The change scriptPubKey at [index], derived publicly from the account zpub. */
    fun changeScript(zpub: String, index: Int): ByteArray {
        val parsed = Bip32.parseExtendedPubKey(zpub)
        return Address.scriptPubKey(Bip32.derivePath(parsed, 1, index).pubkey(), parsed.scriptType)
    }
}

/**
 * The smallest standard output for [script] (Bitcoin Core's dust limit at 3 sat/vB), from the
 * Android app's `ui/WalletViewModel.kt` `dustFor`: 294 for bc1q, 330 for bc1p and P2WSH, 540 for
 * P2SH, 546 for P2PKH. An output under it is refused by the network, so change below it goes to
 * the fee instead.
 */
fun dustFor(script: ByteArray): Long = when {
    script.size == 22 && script[0] == 0.toByte() -> 294L               // P2WPKH
    script.size == 34 && (script[0] == 0x51.toByte() || script[0] == 0.toByte()) -> 330L // P2TR, P2WSH
    script.size == 23 && script[0] == 0xa9.toByte() -> 540L            // P2SH
    else -> 546L
}

/**
 * Signing with the recovery words, as the Android app's `WalletViewModel.confirmSend` does on the
 * BLAKE2b chain: keys at m/84'/0'/0'/chain/index, sequence 0xfffffffd (replace-by-fee), and the
 * unified sighash 0x21 on every input so the spend cannot be replayed on the SHA-256 chain.
 */
object Signer {
    fun sign(draft: SendDraft, words: List<String>, passphrase: String, zpub: String): TxBuilder.Signed {
        val account = Bip32.parseExtendedPubKey(zpub)
        val seed = Bip39.toSeed(words, passphrase)
        val master = Bip32Priv.fromSeed(seed)
        seed.fill(0)
        val nodes = draft.inputs.map { Bip32Priv.derivePath(master, "m/84'/0'/0'/${it.chainIndex}/${it.index}") }
        try {
            val inputs = draft.inputs.mapIndexed { i, u ->
                TxBuilder.Input(u.txid, u.vout, u.value, nodes[i].key, nodes[i].publicKey(), 0xfffffffdL)
            }
            // The words must be this wallet's: every key must be the one its zpub derives for that coin.
            draft.inputs.forEachIndexed { i, u ->
                check(Bip32.derivePath(account, u.chainIndex, u.index).pubkey().contentEquals(inputs[i].pubkey)) {
                    "These recovery words do not belong to this wallet. Nothing was signed."
                }
            }
            return TxBuilder.build(inputs, draft.outputs, unified = true)
        } finally {
            nodes.forEach { it.wipe() }
            master.wipe()
        }
    }

    /** The account zpub (BIP-84, account 0) for these words. */
    fun accountZpub(words: List<String>, passphrase: String): String {
        val seed = Bip39.toSeed(words, passphrase)
        val master = Bip32Priv.fromSeed(seed)
        seed.fill(0)
        return try { Bip32Priv.accountXpub(master, 84, 0) } finally { master.wipe() }
    }
}

/** 12 345 678 with narrow no-break spaces (Android app: `groupSats`). */
fun formatSats(sats: Long): String {
    val digits = kotlin.math.abs(sats).toString()
    val sb = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return (if (sats < 0) "-" else "") + sb
}

/** 0.00012345 — sats as whole coins with 8 decimals. */
fun formatBtc(sats: Long): String {
    val a = kotlin.math.abs(sats)
    return (if (sats < 0) "-" else "") + "${a / 100_000_000L}." + (a % 100_000_000L).toString().padStart(8, '0')
}
