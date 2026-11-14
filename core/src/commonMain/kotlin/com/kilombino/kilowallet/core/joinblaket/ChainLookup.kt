package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.crypto.BlockHeader
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.TxParse

/**
 * What the taker must look up on the chain about coins it does not own: fidelity bonds and
 * makers' inputs (`get_utxo`, `get_block_height`, `get_block_time` in
 * `jmwallet/backends/base.py`). Blocking: call it from the IO dispatcher.
 *
 * Every method throws when the backend cannot answer, so "unavailable" is never confused with
 * "spent" (`taker/coinjoin_session.py:1070-1158`).
 */
interface ChainLookup {
    /** The tip height. */
    fun height(): Int

    /**
     * The output [txid]:[vout] if it is unspent (mempool spends count as spent; an unconfirmed
     * output has 0 confirmations), else null. [scriptPubKey], when the caller knows it (a bond's
     * P2WSH, an extended UTXO reference), lets a script-indexed backend find the coin; the answer
     * carries the chain's own value and scriptPubKey either way.
     */
    fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray? = null): IoauthCheck.ChainUtxo?

    /** The `time` field of the header of block [height]. */
    fun blockTime(height: Int): Long
}

/**
 * [ChainLookup] over an Electrum server (Shulcrum: BLAKE2b headers of 164 bytes), as Android's
 * `ElectrumChainLookup`: the coin's value and scriptPubKey come from the transaction itself, which
 * must hash to [txid] (so the server cannot change them); that it is unspent and its height come
 * from `blockchain.scripthash.listunspent` on that scriptPubKey. The height is the server's word
 * unless [prove] checks it (the app: SPV, the merkle branch into a header with real proof of
 * work), in which case a coin it cannot prove is treated as unconfirmed.
 */
class ElectrumChainLookup(
    private val client: ElectrumClient,
    private val prove: ((txid: String, height: Int) -> Boolean?)? = null,
) : ChainLookup {
    override fun height(): Int = client.blockHeight()

    override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? {
        fun unspent(spk: ByteArray) = client.listUnspent(Hashes.sha256(spk).reversedArray().toHex())
            .firstOrNull { it.txid.equals(txid, true) && it.vout.toLong() == vout }
        // With the script known, a coin the server does not list is simply not there (no tx fetch).
        val listed = scriptPubKey?.let { unspent(it) ?: return null }
        val out = TxParse.parse(client.transaction(txid)).outputs.getOrNull(vout.toInt()) ?: return null
        if (scriptPubKey != null && !scriptPubKey.contentEquals(out.scriptPubKey)) return null
        val u = listed ?: unspent(out.scriptPubKey) ?: return null
        val proven = u.height > 0 && (prove == null || prove.invoke(txid, u.height) == true)
        val confirmations = if (proven) height() - u.height + 1 else 0
        return IoauthCheck.ChainUtxo(out.value, out.scriptPubKey, confirmations)
    }

    override fun blockTime(height: Int): Long = headerTime(client.blockHeader(height))

    companion object {
        /** The `time` field (bytes 68..71, little-endian) of a header given as hex. */
        fun headerTime(headerHex: String): Long = Wire.readLe(Hashes.hexToBytes(headerHex), 68, 4)

        /**
         * SPV for [txid] at [height]: its merkle branch leads to the merkle root of that block's
         * header, which meets its own target and a target no easier than [floorBits] (the wallet's
         * `Scanner.SPV_FLOOR_BITS`). Null when the server could not answer.
         */
        fun spvProof(client: ElectrumClient, txid: String, height: Int, floorBits: Long): Boolean? = runCatching {
            val header = BlockHeader.parse(client.blockHeader(height))
            val (branch, pos) = client.merkle(txid, height)
            BlockHeader.inBlock(txid, branch, pos, header) && BlockHeader.meetsItsTarget(header) &&
                BlockHeader.compare(BlockHeader.target(header.bits), BlockHeader.target(floorBits)) <= 0
        }.getOrNull()
    }
}
