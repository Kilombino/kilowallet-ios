package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A pool's one address type: every input, equal output and change of its round is of it. */
enum class PoolType { P2WPKH, P2TR }

/**
 * Kilojoin, the Android app's `coinjoin/Protocol.kt` (Kilowallet 1.3.0), message for message: pools
 * over nostr where no coordinator holds funds and the relay only carries messages. Versions 1 to 5
 * are all read; this app opens v3 (blind outputs), v4 (several equal outputs each) and v5 (a
 * payment of different amounts, the others getting the same sum back in equal outputs).
 *
 * Events (relay.kilombino.com):
 *  - [KIND_POOL] (parameterised replaceable, `d` = pool id, `t` = kilojoin), signed by the pool's
 *    own key, plaintext: the pool's terms and its current state.
 *  - [KIND_MSG], tag `p` = pool key, NIP-44 encrypted: join requests to the pool key, replies
 *    from the creator, and the pool channel (members write with the pool key, the creator with its
 *    own key from v3 on).
 *
 * A join proves ownership of real unspent coins (a signature by each coin's key, checked against
 * the chain), one seat per coin. Each member's equal outputs are signed blindly by the creator and
 * handed in from fresh keys, so nobody can tie an output to a seat. Every member builds the same
 * transaction and signs only after checking its own outputs, its change and the fee.
 */
object Protocol {
    const val RELAY = "wss://relay.kilombino.com"
    const val KIND_POOL = 32022
    /** Seconds between an open pool's re-announcements by its creator. */
    const val HEARTBEAT = 10 * 60L
    /** An open pool not re-announced for this long has lost its creator and is not listed. */
    const val STALE_AFTER = 35 * 60L
    const val KIND_MSG = 2023
    const val TAG = "kilojoin"
    const val V1 = 1
    /** v2 pools: one address type, several coins per person. */
    const val VERSION = 2
    /** v3 pools: blinded outputs and the creator's own key. */
    const val V3 = 3
    /** v4 pools: v3 with several equal outputs per person. */
    const val V4 = 4
    /** v5 pools: the creator pays recipients of different amounts; joiners get equal outputs. */
    const val V5 = 5
    /** Smallest payment in a v5 pool: the dust limit of a Taproot output, the highest of the two types. */
    const val MIN_PAYMENT = 330L
    /** Most equal outputs one person may get in a v4 pool (and most payments in a v5 one). */
    const val MAX_OUTPUTS = 5
    /** Most coins one person may bring to a v2 pool. */
    const val MAX_INPUTS = 10
    /** BLAKE2b mainnet. Tests against a regtest node set another name so their pools never list here. */
    var NETWORK = "blake2b"

    /** Least each person puts in a pool (amount × outputs); below it a pool is only a test. */
    const val MIN_AMOUNT = 10_000L
    const val MAX_AMOUNT = 100_000_000L
    /** Smallest output a pool may announce at all: a test pool's amount, or each of several outputs. */
    const val TEST_MIN_AMOUNT = 1_000L

    /** A test pool: less than [MIN_AMOUNT] each in all. The app lists and creates only real ones. */
    fun isTest(t: Terms): Boolean = t.amount * t.outputs < MIN_AMOUNT
    const val MIN_PEERS = 2
    const val MAX_PEERS = 20

    /** How long people get to answer "close now?"; whoever has not answered is left out. */
    const val VOTE_SECONDS = 10 * 60L
    /** From "closing" until every equal output and change is in. */
    const val OUTPUTS_SECONDS = 5 * 60L
    /** From the transaction being ready until everyone has signed it (needs the user's touch). */
    const val SIGN_SECONDS = 20 * 60L

    /** A private pool's password never travels: a key derived from it, and an HMAC of the join key under it. */
    fun passwordKey(poolId: String, password: String): ByteArray =
        Hashes.sha256("kilojoin/v1/pw|$poolId|$password".encodeToByteArray())

    fun passwordProof(pwKey: ByteArray, joinPub: String): String = Hashes.hmacSha256(pwKey, joinPub.encodeToByteArray()).toHex()

    fun tokenHash(token: String): String = Hashes.sha256("kilojoin/v1/token|$token".encodeToByteArray()).toHex()

    data class Terms(
        val id: String,
        val poolPub: String,
        val amount: Long,
        val feeRate: Double,
        val maxPeers: Int,
        val expiresAt: Long,
        val state: String,      // open, closing, done, aborted
        val peers: Int,
        val createdAt: Long,
        /** Fewest people the round may close with (2 to [maxPeers]). */
        val minPeers: Int = MIN_PEERS,
        /** Joining needs the pool's password (shared by its creator outside the app). */
        val private: Boolean = false,
        val version: Int = V1,
        /** The one address type of the round (always P2WPKH in v1). */
        val script: PoolType = PoolType.P2WPKH,
        /** Most coins one person may bring (always 1 in v1). */
        val maxInputs: Int = 1,
        /** v3: the creator's own nostr key (x-only hex), which alone runs the pool. */
        val creatorPub: String? = null,
        /** v3: the RSA key the creator signs outputs blindly with ([BlindRsa.PublicKey.encode]). */
        val blindKey: String? = null,
        /** v4: equal outputs each person gets, every one of [amount] (1 before v4; each joiner's in v5). */
        val outputs: Int = 1,
        /** v5: the creator's payments, in sats, summing to [mixTotal]; empty before v5. */
        val payments: List<Long> = emptyList(),
    ) {
        val v3: Boolean get() = version >= V3
        val v5: Boolean get() = version >= V5
        /** What each person puts in: [outputs] × [amount]. */
        val mixTotal: Long get() = amount * outputs

        fun toJson(): JsonObject {
            val kv = mutableListOf<Pair<String, Any?>>(
                "v" to version, "app" to "kilowallet", "network" to NETWORK,
                "id" to id, "public_key" to poolPub, "denomination" to amount,
                "fee_rate" to feeRate, "min_peers" to minPeers, "max_peers" to maxPeers, "private" to private,
                "timeout" to expiresAt, "state" to state, "peers" to peers,
            )
            if (version >= 2) { kv += "script" to scriptName(script); kv += "max_inputs" to maxInputs }
            if (version >= V3) { kv += "creator" to creatorPub; kv += "blind_key" to blindKey }
            if (version >= V4) kv += "outputs" to outputs
            if (version >= V5) kv += "payments" to payments
            return J.obj(*kv.toTypedArray())
        }

        companion object {
            fun parse(ev: NostrEvent): Terms? = runCatching { fromJson(J.parse(ev.content), ev.createdAt)?.takeIf {
                // Only the pool's own key may speak for it.
                ev.pubkey == it.poolPub && ev.tag("d") == it.id
            } }.getOrNull()

            /** The terms in [o] if they are valid and of this network; null otherwise. */
            fun fromJson(o: JsonObject, createdAt: Long): Terms? = runCatching {
                val v = o.int("v")
                if (v != V1 && v != VERSION && v != V3 && v != V4 && v != V5 || o.str("network") != NETWORK) return null
                val t = Terms(
                    id = o.str("id"), poolPub = o.str("public_key"),
                    amount = o.long("denomination"), feeRate = o.double("fee_rate"),
                    maxPeers = o.int("max_peers"), expiresAt = o.long("timeout"),
                    state = o.str("state"), peers = o.optInt("peers", 0), createdAt = createdAt,
                    minPeers = o.optInt("min_peers", MIN_PEERS), private = o.optBool("private", false),
                    version = v,
                    script = if (v == V1) PoolType.P2WPKH else parseScript(o.str("script")) ?: return null,
                    maxInputs = if (v == V1) 1 else o.int("max_inputs"),
                    creatorPub = if (v >= V3) o.str("creator") else null,
                    blindKey = if (v >= V3) o.str("blind_key") else null,
                    outputs = if (v >= V4) o.int("outputs") else 1,
                    payments = if (v >= V5) o.arr("payments").map { it.jsonPrimitive.content.toLong() } else emptyList(),
                )
                // A v4 pool always has several outputs each; one each is announced as v3.
                if (v == V4 && t.outputs !in 2..MAX_OUTPUTS) return null
                if (v == V5 && !paymentsOk(t.payments, t.amount, t.outputs)) return null
                if (t.v3) {
                    if (!t.creatorPub!!.matches(Regex("[0-9a-f]{64}")) || t.creatorPub == t.poolPub) return null
                    BlindRsa.PublicKey.decode(t.blindKey!!)
                }
                if (t.maxInputs !in 1..MAX_INPUTS) return null
                if (t.amount < TEST_MIN_AMOUNT || t.amount > MAX_AMOUNT) return null
                if (t.maxPeers !in MIN_PEERS..MAX_PEERS || t.feeRate < 1.0 || t.feeRate > 500.0) return null
                if (t.minPeers !in MIN_PEERS..t.maxPeers) return null
                t
            }.getOrNull()
        }
    }

    /** v5: 1 to [MAX_OUTPUTS] payments of at least [MIN_PAYMENT] each, summing to [outputs] × [amount]. */
    fun paymentsOk(payments: List<Long>, amount: Long, outputs: Int): Boolean =
        payments.size in 1..MAX_OUTPUTS && outputs in 1..MAX_OUTPUTS && payments.all { it >= MIN_PAYMENT } &&
            payments.sum() == amount * outputs

    /**
     * v5: how a joiner gets the payments' [total] back: [wanted] equal outputs when the total splits
     * evenly into that many of at least [minAmount], else one output of the whole total. Returns
     * (amount of each output, outputs).
     */
    fun v5Split(total: Long, wanted: Int = 1, minAmount: Long = TEST_MIN_AMOUNT): Pair<Long, Int> =
        if (wanted in 2..MAX_OUTPUTS && total % wanted == 0L && total / wanted >= minAmount) total / wanted to wanted
        else total to 1

    fun scriptName(t: PoolType): String = if (t == PoolType.P2TR) "p2tr" else "p2wpkh"
    fun parseScript(s: String): PoolType? = when (s) { "p2wpkh" -> PoolType.P2WPKH; "p2tr" -> PoolType.P2TR; else -> null }

    /** A coin as it travels in messages (its address type is the pool's, not sent). */
    fun coinJson(c: PoolTx.Coin): JsonObject = J.obj("txid" to c.txid, "vout" to c.vout, "value" to c.value, "pubkey" to c.pubkey.toHex())

    fun parseCoin(o: JsonObject, type: PoolType = PoolType.P2WPKH): PoolTx.Coin =
        PoolTx.Coin(o.str("txid"), o.int("vout"), o.long("value"), Hashes.hexToBytes(o.str("pubkey")), type).also {
            require(it.txid.matches(Regex("[0-9a-f]{64}")) && it.vout >= 0 && it.value > 0 && it.pubkey.size == 33)
        }

    /**
     * A member's seat: their coins, their change (script + value, 0 = no change) and the signature by
     * the first coin's key over all of it, so nobody can swap someone else's change.
     */
    data class Seat(
        val tokenHash: String, val coins: List<PoolTx.Coin>, val changeScript: ByteArray?, val changeValue: Long,
        /** v3 roster: the key the seat joined with and its ownership proofs, for every member to check. */
        val joinPub: String? = null, val proofs: List<String> = emptyList(),
    ) {
        init { require(coins.isNotEmpty()) { "a seat with no coin" } }
        val coin: PoolTx.Coin get() = coins.first()
        val inputValue: Long get() = coins.sumOf { it.value }

        fun toJson(): JsonObject {
            val kv = mutableListOf<Pair<String, Any?>>("token_hash" to tokenHash, "coin" to coinJson(coin),
                "change_script" to (changeScript?.toHex() ?: ""), "change_value" to changeValue)
            if (coins.size > 1) kv += "coins" to coins.map { coinJson(it) }
            if (joinPub != null) { kv += "join_pub" to joinPub; kv += "proofs" to proofs }
            return J.obj(*kv.toTypedArray())
        }

        override fun equals(other: Any?): Boolean = other is Seat && toJson() == other.toJson()
        override fun hashCode(): Int = toJson().hashCode()

        companion object {
            fun parse(o: JsonObject, type: PoolType = PoolType.P2WPKH): Seat {
                val cs = o.optStr("change_script")
                val list = o.optArr("coins")?.map { parseCoin(it.jsonObject, type) } ?: listOf(parseCoin(o.obj("coin"), type))
                val proofs = o.strings("proofs") ?: emptyList()
                return Seat(o.str("token_hash"), list, if (cs.isEmpty()) null else Hashes.hexToBytes(cs), o.long("change_value"),
                    o.optStr("join_pub").ifEmpty { null }, proofs)
            }
        }
    }

    /** What the change signature covers: the v1 message for one coin, all the outpoints for several. */
    fun changeMessage(poolId: String, coins: List<PoolTx.Coin>, script: ByteArray?, value: Long): ByteArray =
        if (coins.size == 1) Hashes.sha256("kilojoin/v1/change|$poolId|${coins[0].outpoint}|${script?.toHex() ?: ""}|$value".encodeToByteArray())
        else Hashes.sha256("kilojoin/v2/change|$poolId|${coins.joinToString(",") { it.outpoint }}|${script?.toHex() ?: ""}|$value".encodeToByteArray())

    /** v3: a member's proof that it holds the seat with [token], bound to what it says ([parts]). */
    fun mac(token: String, vararg parts: Any): String =
        Hashes.hmacSha256(token.encodeToByteArray(), ("kilojoin/v3|" + parts.joinToString("|")).encodeToByteArray()).toHex()

    fun macOk(token: String?, mac: String, vararg parts: Any): Boolean =
        token != null && constantTimeEquals(mac(token, *parts).encodeToByteArray(), mac.encodeToByteArray())

    fun seatsJson(seats: Collection<Seat>): JsonArray = JsonArray(seats.map { it.toJson() })
    fun parseSeats(a: JsonArray, type: PoolType = PoolType.P2WPKH): List<Seat> = a.map { Seat.parse(it.jsonObject, type) }
}
