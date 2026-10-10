package com.kilombino.kilowallet.core.crypto

/**
 * Address and scriptPubKey derivation, plus the Electrum "scripthash" servers index by.
 * Ported from the Android app's `crypto/Address.kt`. The BLAKE2b chain kept Bitcoin's address
 * formats (it changed the proof of work, not the keys), so these are ordinary `bc1…` addresses.
 *
 * This wallet derives P2WPKH (and, watch-only, P2PKH / P2SH-P2WPKH) addresses; it pays to any
 * standard address, Taproot included, but never derives Taproot keys itself.
 */
object Address {

    private const val HRP_MAINNET = "bc"
    private const val P2PKH_VERSION = 0x00
    private const val P2SH_VERSION = 0x05

    /**
     * The scriptPubKey a RECIPIENT address pays to. Accepts P2PKH (1…), P2SH (3…), and native
     * SegWit / Taproot (bc1…). Throws with a readable message on anything malformed.
     */
    fun decodeToScriptPubKey(address: String): ByteArray {
        val a = address.trim()
        if (a.length > 3 && a.substring(0, 3).lowercase() == "bc1") {
            val sw = Bech32.decodeSegwit(HRP_MAINNET, a)
                ?: throw IllegalArgumentException("Not a valid bc1 address (checksum or format).")
            val op = if (sw.witnessVersion == 0) 0x00 else (0x50 + sw.witnessVersion)
            return byteArrayOf(op.toByte(), sw.program.size.toByte()) + sw.program
        }
        val raw = try {
            Base58.decodeChecked(a)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a valid address: ${e.message}")
        }
        require(raw.size == 21) { "Unexpected address payload length." }
        val version = raw[0].toInt() and 0xFF
        val h = raw.copyOfRange(1, 21)
        return when (version) {
            P2PKH_VERSION -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h + byteArrayOf(0x88.toByte(), 0xac.toByte())
            P2SH_VERSION -> byteArrayOf(0xa9.toByte(), 0x14) + h + byteArrayOf(0x87.toByte())
            else -> throw IllegalArgumentException("Unknown address version byte $version.")
        }
    }

    /** True when [address] parses as a mainnet address. */
    fun isValid(address: String): Boolean = runCatching { decodeToScriptPubKey(address) }.isSuccess

    /** The scriptPubKey a given public key locks coins to, under [type]. */
    fun scriptPubKey(pubkey: ByteArray, type: ScriptType): ByteArray {
        val h160 = Hashes.hash160(pubkey)
        return when (type) {
            ScriptType.P2PKH -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h160 + byteArrayOf(0x88.toByte(), 0xac.toByte())
            ScriptType.P2SH_P2WPKH -> {
                val redeem = byteArrayOf(0x00, 0x14) + h160
                byteArrayOf(0xa9.toByte(), 0x14) + Hashes.hash160(redeem) + byteArrayOf(0x87.toByte())
            }
            ScriptType.P2WPKH -> byteArrayOf(0x00, 0x14) + h160
        }
    }

    /** The human-readable address for a public key under [type]. */
    fun encode(pubkey: ByteArray, type: ScriptType): String {
        val h160 = Hashes.hash160(pubkey)
        return when (type) {
            ScriptType.P2PKH -> Base58.encodeChecked(byteArrayOf(P2PKH_VERSION.toByte()) + h160)
            ScriptType.P2SH_P2WPKH -> {
                val redeem = byteArrayOf(0x00, 0x14) + h160
                Base58.encodeChecked(byteArrayOf(P2SH_VERSION.toByte()) + Hashes.hash160(redeem))
            }
            ScriptType.P2WPKH -> Bech32.encodeSegwit(HRP_MAINNET, 0, h160)
        }
    }

    /** The address a scriptPubKey pays to, or null for a non-standard script. */
    fun fromScriptPubKey(spk: ByteArray): String? = when {
        spk.size == 22 && spk[0].toInt() == 0 && spk[1].toInt() == 0x14 ->
            Bech32.encodeSegwit(HRP_MAINNET, 0, spk.copyOfRange(2, 22))
        spk.size == 34 && spk[0].toInt() == 0 && spk[1].toInt() == 0x20 ->
            Bech32.encodeSegwit(HRP_MAINNET, 0, spk.copyOfRange(2, 34))
        spk.size == 34 && spk[0].toInt() == 0x51 && spk[1].toInt() == 0x20 ->
            Bech32.encodeSegwit(HRP_MAINNET, 1, spk.copyOfRange(2, 34))
        spk.size == 25 && spk[0] == 0x76.toByte() && spk[1] == 0xa9.toByte() && spk[2].toInt() == 0x14 ->
            Base58.encodeChecked(byteArrayOf(P2PKH_VERSION.toByte()) + spk.copyOfRange(3, 23))
        spk.size == 23 && spk[0] == 0xa9.toByte() && spk[1].toInt() == 0x14 ->
            Base58.encodeChecked(byteArrayOf(P2SH_VERSION.toByte()) + spk.copyOfRange(2, 22))
        else -> null
    }

    /** Electrum indexes by `sha256(scriptPubKey)` **reversed**. */
    fun electrumScriptHash(scriptPubKey: ByteArray): String {
        val digest = Hashes.sha256(scriptPubKey)
        digest.reverse()
        return with(Hashes) { digest.toHex() }
    }

    fun scriptHashFor(pubkey: ByteArray, type: ScriptType): String = electrumScriptHash(scriptPubKey(pubkey, type))

    /**
     * Refuses outputs anyone could spend: witness v1 that is not a 32-byte Taproot key, and the
     * future witness versions 2–16 (Android app: `requireSpendableDestination`).
     */
    fun requireSpendableDestination(script: ByteArray, who: String) {
        if (script.size < 4) return
        val op = script[0].toInt() and 0xff
        if (op !in 0x51..0x60) return
        val version = op - 0x50
        val program = script.size - 2
        require(version == 1 && program == 32) {
            "$who is a witness version $version output with a $program-byte program: anyone could spend it. Not sending."
        }
    }
}
