package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxBuilder
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The transaction side of a Kilojoin round, kept free of networking so it can be tested alone:
 * the Android app's `coinjoin/CoinjoinTx.kt`, byte for byte (tools/android-vectors).
 *
 * Shape: one or more inputs per person, all of the pool's one address type; [Protocol.Terms.outputs]
 * equal outputs of exactly the pool's amount per person (v5: the creator's payments instead); and a
 * change output per person who has any, of that same type. Each person pays their own share of the
 * miner fee out of their change, so the equal outputs stay equal and nobody pays for anyone else.
 *
 * Ordering is canonical (inputs by outpoint, equal outputs by script, then the change outputs and
 * payments by script), so every member builds the very same transaction and txid, and no position
 * gives away whose output is whose.
 *
 * Signatures are the unified sighash with SIGHASH_ALL (0x21): every input signs every input and
 * every output, and the spend is valid only on the BLAKE2b chain, never on Corecoin.
 *
 * This app's wallet is native SegWit (bc1q): it joins and opens P2WPKH pools only. Taproot pools
 * are listed (and their terms read) but not joined.
 */
object PoolTx {
    const val VERSION = 2L
    const val SEQUENCE = 0xfffffffdL
    /** Same threshold as the wallet's sends: below it, change goes to the fee instead. */
    const val DUST = 294L
    private const val OVERHEAD_VB = 10.5

    /** Input vbytes: P2WPKH 68 (72-byte signature at most); Taproot key path 57.75. */
    fun inputVb(type: PoolType): Double = if (type == PoolType.P2TR) 57.75 else 68.0
    /** Output vbytes: P2WPKH 31, P2TR 43. */
    fun outputVb(type: PoolType): Double = if (type == PoolType.P2TR) 43.0 else 31.0

    /** A member's registered input, as everybody sees it ([type] is the pool's). */
    data class Coin(val txid: String, val vout: Int, val value: Long, val pubkey: ByteArray,
                    val type: PoolType = PoolType.P2WPKH) {
        val outpoint: String get() = "$txid:$vout"
        /** The coin's scriptPubKey (P2WPKH only: this app never derives Taproot keys). */
        val script: ByteArray get() {
            require(type == PoolType.P2WPKH) { "Taproot pools are not supported in this app" }
            return Address.scriptPubKey(pubkey, ScriptType.P2WPKH)
        }
        override fun equals(other: Any?): Boolean = other is Coin && outpoint == other.outpoint && value == other.value &&
            pubkey.contentEquals(other.pubkey) && type == other.type
        override fun hashCode(): Int = outpoint.hashCode()
    }

    /**
     * What each person pays the miners: their [inputs], their [outputs] equal outputs, their change
     * and half the transaction overhead (the share in a pool of two, the most anyone can owe).
     */
    fun feeShare(feeRate: Double, withChange: Boolean = true, inputs: Int = 1, type: PoolType = PoolType.P2WPKH, outputs: Int = 1): Long =
        ceil(feeRate * (inputVb(type) * inputs + outputVb(type) * outputs + (if (withChange) outputVb(type) else 0.0) + OVERHEAD_VB / 2)).toLong()

    /** The change for [inputValue] in a pool of [amount] at [feeRate], 0 when it would be dust, null if too small. */
    fun change(inputValue: Long, amount: Long, feeRate: Double, inputs: Int = 1, type: PoolType = PoolType.P2WPKH, outputs: Int = 1): Long? =
        changeOf(inputValue, amount * outputs, feeRate, inputs, type, outputs)

    /** The same for a seat that puts [mixTotal] in over [outputs] outputs of any amounts (v5: the creator's payments). */
    fun changeOf(inputValue: Long, mixTotal: Long, feeRate: Double, inputs: Int = 1, type: PoolType = PoolType.P2WPKH, outputs: Int = 1): Long? {
        val withChange = inputValue - mixTotal - feeShare(feeRate, true, inputs, type, outputs)
        if (withChange > DUST) return withChange
        val without = inputValue - mixTotal - feeShare(feeRate, false, inputs, type, outputs)
        return if (without >= 0) 0L else null
    }

    /** The smallest single coin that can join a pool: [outputs] times the amount plus the fee share without change. */
    fun minimumCoin(amount: Long, feeRate: Double, type: PoolType = PoolType.P2WPKH, outputs: Int = 1): Long =
        amount * outputs + feeShare(feeRate, false, 1, type, outputs)

    /**
     * The amount of each output for a pool opened from whole coins worth [total] together ([inputs]
     * of them), so that they go in with no change; null when the coins don't cover the fee.
     */
    fun noChangeAmount(total: Long, inputs: Int, feeRate: Double, type: PoolType = PoolType.P2WPKH, outputs: Int = 1): Long? {
        val left = total - feeShare(feeRate, false, inputs, type, outputs)
        return if (left <= 0 || outputs < 1) null else left / outputs
    }

    class Plan(val coins: List<Coin>, val outputs: List<TxBuilder.Output>) {
        /** The private key is never read when only assembling or checking: a placeholder. */
        val inputs: List<TxBuilder.Input> get() = coins.map {
            TxBuilder.Input(it.txid, it.vout, it.value, PLACEHOLDER_KEY, it.pubkey, SEQUENCE)
        }
        val fee: Long get() = coins.sumOf { it.value } - outputs.sumOf { it.value }
    }

    private val PLACEHOLDER_KEY = ByteArray(32).also { it[31] = 1 }

    private fun cmp(a: ByteArray, b: ByteArray): Int = a.toHex().compareTo(b.toHex())

    /**
     * The canonical transaction: [mixScripts] get [amount] each ([perPerson] of them for each person),
     * every (script, value) in [changes] its change, [payments] (v5) the creator's payments.
     * Throws if the pieces do not add up to a sane round.
     */
    fun plan(coins: List<Coin>, mixScripts: List<ByteArray>, changes: List<TxBuilder.Output>, amount: Long, perPerson: Int = 1,
             payments: List<TxBuilder.Output> = emptyList()): Plan {
        require(perPerson >= 1 && mixScripts.size % perPerson == 0) { "every person gets the same number of equal outputs" }
        val people = mixScripts.size / perPerson + (if (payments.isEmpty()) 0 else 1)
        require(people >= 2) { "a round needs at least two people" }
        require(payments.all { it.value >= Protocol.MIN_PAYMENT }) { "a payment is dust" }
        require(coins.size >= people) { "every person brings at least one coin" }
        require(coins.map { it.outpoint }.toSet().size == coins.size) { "the same coin was registered twice" }
        val type = coins.first().type
        require(coins.all { it.type == type }) { "coins of two address types" }
        val others = (payments + changes).map { it.scriptPubKey }
        require((mixScripts + others).all { isType(it, type) }) { "an output of another address type" }
        val allScripts = (mixScripts + others).map { it.toHex() }
        require(allScripts.toSet().size == allScripts.size) { "an address appears twice" }
        val inputScripts = coins.map { it.script.toHex() }.toSet()
        require(allScripts.none { it in inputScripts }) { "an output pays back to an input address" }
        require(changes.all { it.value > DUST }) { "a change output is dust" }
        val outs = mixScripts.sortedWith(::cmp).map { TxBuilder.Output(it, amount) } +
            (changes + payments).sortedWith { a, b -> cmp(a.scriptPubKey, b.scriptPubKey) }
        val p = Plan(coins.sortedWith(compareBy({ it.txid }, { it.vout })), outs)
        require(p.fee >= 0) { "the outputs spend more than the inputs" }
        return p
    }

    /** True when [script] is an output of [type]: `0014<20>` for P2WPKH, `5120<32>` for P2TR. */
    fun isType(script: ByteArray, type: PoolType): Boolean = when (type) {
        PoolType.P2TR -> script.size == 34 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte()
        PoolType.P2WPKH -> script.size == 22 && script[0] == 0.toByte() && script[1] == 0x14.toByte()
    }

    /** Our signature for our input [mine] in [plan]: unified sighash, SIGHASH_ALL, ECDSA ground to a low R. */
    fun sign(plan: Plan, mine: Coin, privateKey: ByteArray): ByteArray {
        require(mine.type == PoolType.P2WPKH) { "Taproot pools are not supported in this app" }
        val i = plan.coins.indexOfFirst { it.outpoint == mine.outpoint }
        require(i >= 0) { "our coin is not in the transaction" }
        require(Ec.publicKey(privateKey).contentEquals(mine.pubkey)) { "that key is not our coin's" }
        val inputs = plan.inputs.toMutableList()
        val x = inputs[i]
        inputs[i] = TxBuilder.Input(x.txid, x.vout, x.value, privateKey, x.pubkey, x.sequence)
        return TxBuilder.witnessSignature(VERSION, inputs, plan.outputs, i, 0, unified = true, grindLowR = true)
    }

    /** True when [sig] is a valid unified SIGHASH_ALL signature of [coin]'s input in [plan]. */
    fun verify(plan: Plan, coin: Coin, sig: ByteArray): Boolean = runCatching {
        val i = plan.coins.indexOfFirst { it.outpoint == coin.outpoint }
        if (i < 0 || sig.isEmpty() || coin.type != PoolType.P2WPKH) return false
        if ((sig.last().toInt() and 0xFF) != (TxBuilder.SIGHASH_UNIFIED or TxBuilder.SIGHASH_ALL)) return false
        val h = TxBuilder.unifiedSighash(VERSION, plan.inputs, plan.outputs, i, 0)
        Ec.verifyDer(coin.pubkey, h, sig.copyOfRange(0, sig.size - 1))
    }.getOrDefault(false)

    /** The txid [plan] will have: it does not cover the witnesses, so it is known before signing. */
    fun txid(plan: Plan): String = TxBuilder.assemble(plan.inputs, plan.outputs, plan.coins.map { ByteArray(72) }, VERSION, 0).txid

    /** The final transaction from everyone's signatures, keyed by outpoint. */
    fun assemble(plan: Plan, sigs: Map<String, ByteArray>): TxBuilder.Signed {
        val w = plan.coins.map { sigs[it.outpoint] ?: error("missing the signature of ${it.outpoint}") }
        return TxBuilder.assemble(plan.inputs, plan.outputs, w, VERSION, 0)
    }

    /** What we check before signing: our equal output is there with the full amount, our change exactly ours, the fee sane. */
    fun checkOurs(plan: Plan, mixScript: ByteArray, change: TxBuilder.Output?, amount: Long, feeRate: Double,
                  seatInputs: List<Int> = plan.coins.map { 1 }): String? =
        checkOurs(plan, listOf(mixScript), change, amount, feeRate, seatInputs, 1)

    /** The same for a v4 round: each of our [mixScripts] once, [perPerson] equal outputs for every seat. */
    fun checkOurs(plan: Plan, mixScripts: List<ByteArray>, change: TxBuilder.Output?, amount: Long, feeRate: Double,
                  seatInputs: List<Int>, perPerson: Int): String? {
        val type = plan.coins.first().type
        val people = seatInputs.size
        if (mixScripts.size != perPerson || mixScripts.map { it.toHex() }.toSet().size != perPerson) return "our equal outputs are wrong"
        for (mixScript in mixScripts) {
            val mix = plan.outputs.count { it.scriptPubKey.contentEquals(mixScript) && it.value == amount }
            if (mix != 1) return "our equal output is missing"
        }
        if (plan.outputs.count { it.value == amount } < people * perPerson) return "the equal outputs are not all equal"
        if (seatInputs.sum() != plan.coins.size) return "the coins do not match the seats"
        if (change != null && plan.outputs.none { it.scriptPubKey.contentEquals(change.scriptPubKey) && it.value == change.value })
            return "our change is missing or wrong"
        val vb = OVERHEAD_VB + inputVb(type) * plan.coins.size + outputVb(type) * plan.outputs.size
        if (plan.fee < floor(feeRate * vb * 0.95)) return "the fee is too low"
        if (plan.fee > seatInputs.sumOf { feeShare(feeRate, true, it, type, perPerson) } + (DUST + 1) * people) return "the fee is too high"
        return null
    }

    /**
     * v5: what every member checks before signing. [mine]: our own outputs (a joiner's equal outputs,
     * or the creator's payments), each there once with its exact value; [change] ours. The
     * transaction must hold exactly the joiners' outputs of [amount], the announced payments (each
     * once) and [changes] changes, and pay a fee in line with every seat's own share.
     */
    fun checkPayment(plan: Plan, mine: List<TxBuilder.Output>, change: TxBuilder.Output?, amount: Long, feeRate: Double,
                     seatInputs: List<Int>, seatOutputs: List<Int>, perPerson: Int, payments: List<Long>, changes: Int): String? {
        val type = plan.coins.first().type
        val people = seatInputs.size
        if (seatOutputs.size != people || payments.isEmpty()) return "the seats do not match the round"
        if (mine.isEmpty() || mine.map { it.scriptPubKey.toHex() }.toSet().size != mine.size) return "our equal outputs are wrong"
        for (m in mine) if (plan.outputs.count { it.scriptPubKey.contentEquals(m.scriptPubKey) && it.value == m.value } != 1)
            return "our equal output is missing"
        val mixed = (people - 1) * perPerson
        if (plan.outputs.size != mixed + payments.size + changes) return "the transaction has other outputs than the round's"
        val values = plan.outputs.groupingBy { it.value }.eachCount()
        for ((v, n) in payments.groupingBy { it }.eachCount())
            if ((values[v] ?: 0) < n + (if (v == amount) mixed else 0)) return "a payment is missing or changed"
        if ((values[amount] ?: 0) < mixed + payments.count { it == amount }) return "the equal outputs are not all equal"
        if (seatInputs.sum() != plan.coins.size) return "the coins do not match the seats"
        if (change != null && plan.outputs.none { it.scriptPubKey.contentEquals(change.scriptPubKey) && it.value == change.value })
            return "our change is missing or wrong"
        val vb = OVERHEAD_VB + inputVb(type) * plan.coins.size + outputVb(type) * plan.outputs.size
        if (plan.fee < floor(feeRate * vb * 0.95)) return "the fee is too low"
        if (plan.fee > seatInputs.indices.sumOf { feeShare(feeRate, true, seatInputs[it], type, seatOutputs[it]) } + (DUST + 1) * people)
            return "the fee is too high"
        return null
    }

    /** Proof that whoever joins really owns [coin]: an ECDSA signature by its key over the pool and join key. */
    fun ownershipMessage(poolId: String, joinPub: String, coin: Coin): ByteArray =
        com.kilombino.kilowallet.core.crypto.Hashes.sha256("kilojoin/v1/own|$poolId|$joinPub|${coin.outpoint}|${coin.value}".encodeToByteArray())

    fun proveOwnership(poolId: String, joinPub: String, coin: Coin, privateKey: ByteArray): ByteArray =
        Ec.signDer(privateKey, ownershipMessage(poolId, joinPub, coin))

    fun checkOwnership(poolId: String, joinPub: String, coin: Coin, der: ByteArray): Boolean =
        Ec.verifyDer(coin.pubkey, ownershipMessage(poolId, joinPub, coin), der)
}
