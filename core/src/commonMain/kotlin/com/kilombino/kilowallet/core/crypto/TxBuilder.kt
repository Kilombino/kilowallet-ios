package com.kilombino.kilowallet.core.crypto

import com.kilombino.kilowallet.core.crypto.Hashes.toHex

/**
 * Build and sign P2WPKH transactions. Ported from the Android app's `crypto/TxBuilder.kt`, keeping
 * only what this wallet spends (its own native SegWit coins): the BIP-143 sighash and the
 * unified opt-in sighash, with byte-identical serialisation.
 *
 * On the BLAKE2b chain every input is signed with the UNIFIED sighash, hash type 0x21
 * (SIGHASH_UNIFIED | SIGHASH_ALL): a node without the BLAKE2b fork cannot rebuild that message, so
 * the signature does not verify on the shared-history SHA-256 chain and the spend cannot be
 * replayed there. [build] defaults to unified = true for that reason; plain SIGHASH_ALL is only
 * reachable by asking for it explicitly (the tests do, to pin BIP-143).
 */
object TxBuilder {

    const val SIGHASH_ALL = 0x01

    /** Opt-in unified sighash bit (`doc/unified-sighash.md`, Knots v29.4.1). With ALL: 0x21. */
    const val SIGHASH_UNIFIED = 0x20

    /** A P2WPKH coin being spent, with the key that unlocks it. */
    class Input(
        val txid: String,          // display (big-endian) txid
        val vout: Int,
        val value: Long,           // satoshis
        val privateKey: ByteArray, // 32-byte secret
        val pubkey: ByteArray,     // 33-byte compressed
        val sequence: Long = 0xffffffffL,
    ) {
        init { require(pubkey.size == 33) { "SegWit inputs need a compressed public key" } }
        /** The scriptPubKey of the output being spent. */
        val spentScript: ByteArray get() = Address.scriptPubKey(pubkey, ScriptType.P2WPKH)
        /** The BIP-143 / unified scriptCode: the key's P2PKH script. */
        val scriptCode: ByteArray get() = Address.scriptPubKey(pubkey, ScriptType.P2PKH)
    }

    class Output(val scriptPubKey: ByteArray, val value: Long)

    class Signed(val rawHex: String, val txid: String, val weight: Int) {
        /** Virtual size in vbytes, rounded up. */
        val vbytes: Int get() = (weight + 3) / 4
    }

    // ---- little-endian / varint writers ---------------------------------------------

    internal fun u32le(v: Long): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    internal fun u64le(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }

    internal fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), n.toByte(), (n ushr 8).toByte())
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + u32le(n)
        else -> byteArrayOf(0xff.toByte()) + u64le(n)
    }

    internal fun varBytes(b: ByteArray): ByteArray = varint(b.size.toLong()) + b

    /** The internal (little-endian) outpoint: reversed txid ‖ vout. */
    internal fun outpoint(txid: String, vout: Int): ByteArray =
        Hashes.hexToBytes(txid).reversedArray() + u32le(vout.toLong())

    // ---- BIP-143 sighash ------------------------------------------------------------

    /** The BIP-143 sighash for input [index], SIGHASH_ALL. */
    fun sighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray {
        val prevouts = ByteWriter()
        val sequences = ByteWriter()
        for (i in inputs) {
            prevouts.write(outpoint(i.txid, i.vout))
            sequences.write(u32le(i.sequence))
        }
        val outs = ByteWriter()
        for (o in outputs) { outs.write(u64le(o.value)); outs.write(varBytes(o.scriptPubKey)) }

        val inp = inputs[index]
        val pre = ByteWriter()
        pre.write(u32le(version))
        pre.write(Hashes.doubleSha256(prevouts.toByteArray()))
        pre.write(Hashes.doubleSha256(sequences.toByteArray()))
        pre.write(outpoint(inp.txid, inp.vout))
        pre.write(varBytes(inp.scriptCode))
        pre.write(u64le(inp.value))
        pre.write(u32le(inp.sequence))
        pre.write(Hashes.doubleSha256(outs.toByteArray()))
        pre.write(u32le(locktime))
        pre.write(u32le(SIGHASH_ALL.toLong()))
        return Hashes.doubleSha256(pre.toByteArray())
    }

    // ---- Unified opt-in sighash (anti-replay) ---------------------------------------

    /** Locktime as five little-endian bytes, zero-extended: the unified message widens the field. */
    private fun u40le(v: Long): ByteArray = ByteArray(5) { ((v ushr (8 * it)) and 0xFF).toByte() }

    /**
     * The unified opt-in message for a bare/P2SH (script type 0) or segwit-v0 (script type 1)
     * input, as `doc/unified-sighash.md` specifies it. Verified in the tests against every
     * script-type-0 and -1 vector of Knots' `unified_sighash.json` (142 vectors).
     *
     * Differs from BIP-143: single SHA-256 aggregates; two extra aggregates over every spent amount
     * and scriptPubKey (closing CVE-2020-14199); a 5-byte locktime; an epoch byte, a script-type
     * byte and the input index (or, under ANYONECANPAY, the input itself); and a BIP-340 tagged
     * hash ("UnifiedSighash") instead of the final double SHA-256.
     */
    internal fun unifiedMessage(
        version: Long, locktime: Long, hashType: Int, scriptType: Int,
        prevouts: List<ByteArray>, amounts: List<Long>, spentScripts: List<ByteArray>,
        sequences: List<Long>, outputs: List<Output>, index: Int, scriptCode: ByteArray,
    ): ByteArray {
        val anyoneCanPay = (hashType and 0x80) != 0
        val base = hashType and 0x1f // 1 ALL, 2 NONE, 3 SINGLE

        val msg = ByteWriter()
        msg.write(0x00)                    // epoch
        msg.write(hashType and 0xff)
        msg.write(u32le(version))
        msg.write(u40le(locktime))
        if (!anyoneCanPay) {
            val p = ByteWriter(); prevouts.forEach { p.write(it) }
            val a = ByteWriter(); amounts.forEach { a.write(u64le(it)) }
            val s = ByteWriter(); spentScripts.forEach { s.write(varBytes(it)) }
            val q = ByteWriter(); sequences.forEach { q.write(u32le(it)) }
            msg.write(Hashes.sha256(p.toByteArray()))
            msg.write(Hashes.sha256(a.toByteArray()))
            msg.write(Hashes.sha256(s.toByteArray()))
            msg.write(Hashes.sha256(q.toByteArray()))
        }
        if (base != 0x02 && base != 0x03) {
            val o = ByteWriter()
            for (out in outputs) { o.write(u64le(out.value)); o.write(varBytes(out.scriptPubKey)) }
            msg.write(Hashes.sha256(o.toByteArray()))
        }
        msg.write(scriptType and 0xff)
        if (anyoneCanPay) {
            msg.write(prevouts[index])
            msg.write(u64le(amounts[index])); msg.write(varBytes(spentScripts[index]))
            msg.write(u32le(sequences[index]))
        } else {
            msg.write(u32le(index.toLong()))
        }
        if (scriptType <= 1) msg.write(varBytes(scriptCode))
        else msg.write(0x00)
        if (base == 0x03) {
            require(index < outputs.size) { "SIGHASH_SINGLE has no output at the input's index" }
            val out = outputs[index]
            msg.write(Hashes.sha256(u64le(out.value) + varBytes(out.scriptPubKey)))
        }
        return Hashes.taggedHash("UnifiedSighash", msg.toByteArray())
    }

    /** The unified sighash (0x21) for P2WPKH input [index]: script type 1. */
    fun unifiedSighash(version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long): ByteArray =
        unifiedMessage(
            version = version, locktime = locktime,
            hashType = SIGHASH_UNIFIED or SIGHASH_ALL,
            scriptType = 1,
            prevouts = inputs.map { outpoint(it.txid, it.vout) },
            amounts = inputs.map { it.value },
            spentScripts = inputs.map { it.spentScript },
            sequences = inputs.map { it.sequence },
            outputs = outputs, index = index,
            scriptCode = inputs[index].scriptCode,
        )

    /** The DER signature + sighash byte for input [index]. */
    fun witnessSignature(
        version: Long, inputs: List<Input>, outputs: List<Output>, index: Int, locktime: Long, unified: Boolean,
        /** Ground to a low R (pool inputs, as the Android app signs them); a normal send does not grind. */
        grindLowR: Boolean = false,
    ): ByteArray {
        val h = if (unified) unifiedSighash(version, inputs, outputs, index, locktime)
                else sighash(version, inputs, outputs, index, locktime)
        val hashByte = if (unified) SIGHASH_UNIFIED or SIGHASH_ALL else SIGHASH_ALL
        val der = if (grindLowR) Ec.signDerLowR(inputs[index].privateKey, h) else Ec.signDer(inputs[index].privateKey, h)
        return der + byteArrayOf(hashByte.toByte())
    }

    /**
     * Build, sign and serialise the whole SegWit transaction. [unified] defaults to TRUE: this
     * wallet only spends on the BLAKE2b chain, where every input must carry the 0x21 sighash.
     */
    fun build(
        inputs: List<Input>, outputs: List<Output>, version: Long = 2, locktime: Long = 0,
        unified: Boolean = true,
    ): Signed {
        require(inputs.isNotEmpty()) { "a transaction needs at least one input" }
        require(outputs.isNotEmpty()) { "a transaction needs at least one output" }
        val witnesses = inputs.indices.map { witnessSignature(version, inputs, outputs, it, locktime, unified) }
        return assemble(inputs, outputs, witnesses, version, locktime)
    }

    /** Serialise a P2WPKH transaction from already-made signatures, one per input. */
    fun assemble(
        inputs: List<Input>, outputs: List<Output>, witnesses: List<ByteArray>,
        version: Long = 2, locktime: Long = 0,
    ): Signed {
        require(witnesses.size == inputs.size) { "one signature per input" }

        fun writeInputsOutputs(out: ByteWriter) {
            out.write(varint(inputs.size.toLong()))
            for (i in inputs) {
                out.write(outpoint(i.txid, i.vout))
                out.write(varBytes(ByteArray(0)))   // P2WPKH: empty scriptSig
                out.write(u32le(i.sequence))
            }
            out.write(varint(outputs.size.toLong()))
            for (o in outputs) { out.write(u64le(o.value)); out.write(varBytes(o.scriptPubKey)) }
        }

        val legacy = ByteWriter()
        legacy.write(u32le(version))
        writeInputsOutputs(legacy)
        legacy.write(u32le(locktime))
        val legacyBytes = legacy.toByteArray()
        val txid = Hashes.doubleSha256(legacyBytes).reversedArray().toHex()

        val full = ByteWriter()
        full.write(u32le(version))
        full.write(byteArrayOf(0x00, 0x01)) // segwit marker + flag
        writeInputsOutputs(full)
        for (i in inputs.indices) {
            full.write(varint(2))           // signature, pubkey
            full.write(varBytes(witnesses[i]))
            full.write(varBytes(inputs[i].pubkey))
        }
        full.write(u32le(locktime))
        val fullBytes = full.toByteArray()
        return Signed(fullBytes.toHex(), txid, legacyBytes.size * 3 + fullBytes.size)
    }
}
