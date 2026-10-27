package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One wallet's part in one pool: the Kilojoin state machine, the Android app's
 * `coinjoin/PoolSession.kt` (1.3.9) step for step, on coroutines instead of threads.
 *
 * The pool's creator also keeps the roster: it checks every join against the chain, hands out
 * seats, counts the votes and calls the phases. Every member (the creator included) hands in its
 * equal outputs, checks the final transaction, signs its own inputs and broadcasts once all
 * signatures are in. Messages replay from the relay on reconnect, so every step is idempotent.
 *
 * Everything the session does runs under one lock, one step at a time, as the Android app's
 * `synchronized` methods do. Unlike the Android app, nothing here signs on its own: the user signs
 * each round after seeing what [checkPlan] checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolSession(private val env: Env, private val s: State, private val sockets: WebSocketFactory) {

    /** What the session needs from the wallet. All calls may block; none run on the UI thread. */
    interface Env {
        /** True when [coin] is unspent on the BLAKE2b chain with exactly its stated value. */
        fun coinUnspent(coin: PoolTx.Coin): Boolean
        /** [coinUnspent] for several coins at once; null where the chain could not be asked. */
        fun coinsUnspent(coins: List<PoolTx.Coin>): List<Boolean?> = coins.map { c -> runCatching { coinUnspent(c) }.getOrNull() }
        fun broadcast(rawHex: String): String
        /** Confirmations of [txid], 0 in the mempool, null when unknown. */
        fun confirmations(txid: String): Int?
        fun save(state: State)
        fun event(e: Event)
        fun now(): Long = NostrEvent.nowSeconds()
        fun log(msg: String) {}
    }

    sealed class Event(val pool: String) {
        class Joined(pool: String, val peers: Int) : Event(pool)
        class Welcomed(pool: String) : Event(pool)
        class Rejected(pool: String, val reason: String) : Event(pool)
        class CloseRequested(pool: String, val peers: Int, val byMe: Boolean) : Event(pool)
        class CloseRefused(pool: String) : Event(pool)
        class Closing(pool: String, val peers: Int) : Event(pool)
        class SignNeeded(pool: String, val peers: Int) : Event(pool)
        class Broadcast(pool: String, val txid: String) : Event(pool)
        class Confirmed(pool: String, val txid: String) : Event(pool)
        class Aborted(pool: String, val reason: String) : Event(pool)
        /** Under an hour left, and enough people to close it now. */
        class ExpiringSoon(pool: String, val peers: Int, val minutes: Int) : Event(pool)
        class Changed(pool: String) : Event(pool)
    }

    enum class Phase { JOINING, OPEN, VOTING, CLOSING, SIGNING, BROADCAST, CONFIRMED, ABORTED, REJECTED }

    /** Everything that must survive the app being closed. */
    class State(
        val terms: Protocol.Terms,
        val creator: Boolean,
        var poolSecret: String?,          // hex; known to the creator from the start, to members once welcomed
        val joinSecret: String,           // hex; the one-off key this wallet joined with
        var token: String?,               // our seat's secret token
        val seat: Protocol.Seat,          // our coins and change, as signed at join time
        val joinProofs: List<String>,     // hex DER, one per coin, sent with the join request
        val changeSig: String,            // hex DER
        /** Our equal outputs: one before v4, [Protocol.Terms.outputs] in a v4 pool (v5 creator: one per payment). */
        val mixScripts: List<ByteArray>,
        val coinPaths: List<String>,      // BIP-32 paths of our coins' keys, to sign at the end
        val pwKey: String? = null,        // creator of a private pool: key derived from its password
        val pwProof: String? = null,      // joiner of a private pool: proof sent with the join
        var phase: Phase = Phase.JOINING,
        var reason: String = "",
        var seats: MutableList<Protocol.Seat> = mutableListOf(),   // the latest roster
        var round: MutableList<Protocol.Seat> = mutableListOf(),   // seats in the closing round
        var rosterSeq: Int = 0,
        var voteId: String? = null,
        var voteBy: String? = null,       // token hash of whoever asked to close
        var voteDeadline: Long = 0,
        var phaseDeadline: Long = 0,
        val outputs: MutableSet<String> = linkedSetOf(),           // equal output scripts, hex
        val sigs: MutableMap<String, String> = linkedMapOf(),      // outpoint → sig hex
        var postedOutput: Boolean = false,
        var postedSig: Boolean = false,
        var votedOn: String? = null,
        var txid: String? = null,
        val created: Long = NostrEvent.nowSeconds(),
        // Creator only.
        val accepts: MutableSet<String> = mutableSetOf(),
        val joinPubs: MutableMap<String, String> = mutableMapOf(), // join pub → token hash
        val tokens: MutableMap<String, String> = mutableMapOf(),   // token hash → token
        var warnedExpiry: Boolean = false,
        // v3, creator only: its own key, and the RSA key it signs outputs blindly with.
        val creatorSecret: String? = null,
        val blindPriv: String? = null,
        var roundId: String? = null,
        // v3, member: our outputs' blinding (one per equal output), kept so a resent request is the same one.
        var blindPrefixes: List<String> = emptyList(),
        var blindInvs: List<String> = emptyList(),
        var blindedMsgs: List<String> = emptyList(),
        var outSigs: List<String> = emptyList(),
        val handedIn: MutableSet<Int> = mutableSetOf(),
        // v3, creator: per round, the blind signature given to each seat, and the outputs handed in.
        val blindSigs: MutableMap<String, String> = mutableMapOf(),
        val blindedOf: MutableMap<String, String> = mutableMapOf(),
        val regOutputs: MutableSet<String> = linkedSetOf(),
        // v5: the creator's payments as listed when the round closed, "script hex:sats".
        val payOutputs: MutableList<String> = mutableListOf(),
        /**
         * v3: how many times the round was closed again without seats that never asked for their outputs'
         * blind signatures (a phone asleep at closing). 0 for the first closing.
         */
        var closeSeq: Int = 0,
        /** Creator: the vote its closing went out for. Another yes or the vote's deadline doesn't close it again. */
        var closedVote: String? = null,
        /** Member: when the closing we took was sent; only a later one from the creator replaces it (see "closing"). */
        var closingAt: Long = 0,
    ) {
        val poolId: String get() = terms.id
        val joinProof: String get() = joinProofs.first()
        val coinPath: String get() = coinPaths.first()
        val mixScript: ByteArray get() = mixScripts.first()
        val done: Boolean get() = phase in setOf(Phase.CONFIRMED, Phase.ABORTED, Phase.REJECTED)

        fun toJson(): JsonObject = J.obj(
            "terms" to J.with(terms.toJson(), "created_at" to terms.createdAt),
            "creator" to creator, "pool_secret" to (poolSecret ?: ""), "join_secret" to joinSecret,
            "token" to (token ?: ""), "seat" to seat.toJson(), "join_proof" to joinProof,
            "join_proofs" to joinProofs, "coin_paths" to coinPaths,
            "change_sig" to changeSig, "mix_script" to mixScript.toHex(), "coin_path" to coinPath,
            "mix_scripts" to mixScripts.map { it.toHex() },
            "pw_key" to (pwKey ?: ""), "pw_proof" to (pwProof ?: ""),
            "phase" to phase.name, "reason" to reason,
            "seats" to Protocol.seatsJson(seats), "round" to Protocol.seatsJson(round),
            "roster_seq" to rosterSeq, "vote_id" to (voteId ?: ""), "vote_by" to (voteBy ?: ""), "vote_deadline" to voteDeadline,
            "phase_deadline" to phaseDeadline, "outputs" to outputs.toList(), "sigs" to sigs.toMap(),
            "posted_output" to postedOutput, "posted_sig" to postedSig, "voted_on" to (votedOn ?: ""),
            "txid" to (txid ?: ""), "created" to created, "accepts" to accepts.toList(),
            "join_pubs" to joinPubs.toMap(), "tokens" to tokens.toMap(), "warned_expiry" to warnedExpiry,
            "creator_secret" to (creatorSecret ?: ""), "blind_priv" to (blindPriv ?: ""), "round_id" to (roundId ?: ""),
            "blind_prefixes" to blindPrefixes, "blind_invs" to blindInvs, "blinded_msgs" to blindedMsgs, "out_sigs" to outSigs,
            "handed_in" to handedIn.toList(), "blind_sigs" to blindSigs.toMap(), "blinded_of" to blindedOf.toMap(),
            "reg_outputs" to regOutputs.toList(), "pay_outputs" to payOutputs.toList(),
            "close_seq" to closeSeq, "closed_vote" to (closedVote ?: ""), "closing_at" to closingAt,
        )

        companion object {
            private fun JsonObject.s(k: String): String? = optStr(k).ifEmpty { null }
            fun parse(o: JsonObject): State {
                val t = o.obj("terms")
                val terms = Protocol.Terms.fromJson(t, t.optLong("created_at")) ?: error("the pool's terms are not valid here")
                val type = terms.script
                fun list(k: String, one: String): List<String> = o.strings(k) ?: listOf(o.str(one))
                fun list0(k: String): List<String> = o.strings(k) ?: emptyList()
                return State(
                    terms, o.bool("creator"), o.s("pool_secret"), o.str("join_secret"), o.s("token"),
                    Protocol.Seat.parse(o.obj("seat"), type), list("join_proofs", "join_proof"), o.str("change_sig"),
                    list("mix_scripts", "mix_script").map { Hashes.hexToBytes(it) }, list("coin_paths", "coin_path"),
                    o.s("pw_key"), o.s("pw_proof"),
                    Phase.valueOf(o.str("phase")), o.optStr("reason"),
                    Protocol.parseSeats(o.arr("seats"), type).toMutableList(),
                    Protocol.parseSeats(o.arr("round"), type).toMutableList(),
                    o.optInt("roster_seq"), o.s("vote_id"), o.s("vote_by"), o.optLong("vote_deadline"), o.optLong("phase_deadline"),
                    list0("outputs").toMutableSet(),
                    o.map("sigs"), o.optBool("posted_output"), o.optBool("posted_sig"), o.s("voted_on"),
                    o.s("txid"), o.optLong("created"),
                    list0("accepts").toMutableSet(),
                    o.map("join_pubs"), o.map("tokens"), o.optBool("warned_expiry"),
                    o.s("creator_secret"), o.s("blind_priv"), o.s("round_id"),
                    list0("blind_prefixes"), list0("blind_invs"), list0("blinded_msgs"), list0("out_sigs"),
                    (o.optArr("handed_in")?.map { it.jsonPrimitive.content.toInt() } ?: emptyList()).toMutableSet(),
                    o.map("blind_sigs"), o.map("blinded_of"),
                    list0("reg_outputs").toMutableSet(),
                    list0("pay_outputs").toMutableList(),
                    o.optInt("close_seq"), o.s("closed_vote"), o.optLong("closing_at"),
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher.limitedParallelism(1))
    private val lock = Mutex()
    private var relay: RelayClient? = null
    private var ticker: Job? = null
    private var events: Channel<NostrEvent>? = null
    private val poolKey: ByteArray? get() = s.poolSecret?.let { NostrEvent.secretOf(it) }
    private val joinKey = NostrEvent.secretOf(s.joinSecret)
    private val joinPub = NostrEvent.pubOf(joinKey)
    private val myTokenHash: String? get() = s.token?.let { Protocol.tokenHash(it) }
    private val terms = s.terms
    private val creatorKey: ByteArray? get() = s.creatorSecret?.let { NostrEvent.secretOf(it) }
    private val blindPk: BlindRsa.PublicKey? by lazy { terms.blindKey?.let { BlindRsa.PublicKey.decode(it) } }
    private val verifiedSeats = mutableSetOf<String>()
    private var lastBlindSent = 0L
    /** Member, v3: listed seats the chain says are not real, and since when (the creator gets [FAKE_GRACE] to take them out). */
    private val fakeSince = mutableMapOf<String, Long>()
    /** Creator: when the seats' coins were last checked against the chain; member: the fake seats. */
    private var lastSeatCheck = 0L
    private val outputScheduled = mutableSetOf<Int>()
    private val perPerson: Int get() = terms.outputs

    /** v5: the creator's seat, the one it joined with the pool's own key. */
    private fun isCreatorSeat(seat: Protocol.Seat) = seat.joinPub == terms.poolPub

    /** Seats whose equal outputs are handed in blindly: all of them, but the creator's in v5. */
    private val handingIn: Int get() = if (terms.v5) s.round.count { !isCreatorSeat(it) } else s.round.size

    val state: State get() = s
    val done: Boolean get() = s.done
    val running: Boolean get() = ticker?.isActive == true

    /** The plan once every output is in; null before. */
    fun plan(): PoolTx.Plan? = runCatching { buildPlan() }.getOrNull()

    /** Why the plan does not pay us right (null when it does, or when there is no plan yet). */
    fun planProblem(): String? = plan()?.let { checkPlan(it) }

    // ------------------------------------------------------------------------------- lifecycle

    fun start() {
        if (done || running) return
        val ch = Channel<NostrEvent>(Channel.UNLIMITED)
        events = ch
        ticker = scope.launch {
            launch { for (ev in ch) lock.withLock { runCatching { onEvent(ev) }.onFailure { env.log("event: ${it.message}") } } }
            lock.withLock { runCatching { connect() }.onFailure { env.log("connect: ${it.message}") } }
            delay(5_000)
            while (isActive) {
                lock.withLock { tick() }
                if (done && s.phase != Phase.BROADCAST) break
                delay(15_000)
            }
        }
    }

    fun stop() {
        ticker?.cancel(); ticker = null
        events?.close(); events = null
        relay?.close(); relay = null
    }

    /** [stop], and the session's coroutines gone for good. */
    fun dispose() { stop(); scope.cancel() }

    private suspend fun connect() {
        val ch = events
        val r = RelayClient(Protocol.RELAY, sockets, { _, ev -> ch?.trySend(ev) }, { relay = null })
        r.connect()
        relay = r
        r.subscribe("in", listOf(filter(listOf(Protocol.KIND_MSG), p = joinPub)))
        r.subscribe("pool", listOf(filter(listOf(Protocol.KIND_MSG), p = terms.poolPub)))
        // The pool's public listing, re-announced by its creator while it is alive.
        if (!s.creator) r.subscribe("listing", listOf(filter(listOf(Protocol.KIND_POOL), authors = terms.poolPub, d = terms.id)))
        // v3: outputs come to the creator's own key, each from a fresh key.
        if (s.creator && terms.v3) r.subscribe("outs", listOf(filter(listOf(Protocol.KIND_MSG), p = terms.creatorPub)))
        if (s.phase == Phase.JOINING && !s.creator) sendJoin()
        if (s.creator && s.phase == Phase.JOINING) creatorStart()
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private suspend fun ensureConnected() {
        val r = relay
        if (r != null && r.connected) {
            val quiet = nowMs() - r.lastHeard
            // Ping after a quiet minute; nothing back for another minute means a dead link.
            if (quiet < 60_000) return
            if (quiet < 120_000) { if (r.ping()) return }
            env.log("relay silent for ${quiet / 1000} s: reconnecting")
            r.close(); relay = null
        }
        // Reconnecting replays the channel, so anything missed meanwhile is handled now.
        runCatching { connect() }.onFailure { env.log("reconnect: ${it.message}") }
    }

    private fun save() { env.save(s); env.event(Event.Changed(s.poolId)) }

    // ------------------------------------------------------------------------------- sending

    private suspend fun publish(kind: Int, tags: List<List<String>>, content: String, key: ByteArray): Boolean {
        ensureConnected()
        val ev = NostrEvent.sign(key, kind, tags, content)
        val r = relay?.publish(ev)
        env.log("publish kind $kind -> ${r?.first} ${r?.second ?: "no relay"}")
        return r?.first == true
    }

    /** A message on the pool channel: from the pool key, encrypted to itself. */
    private suspend fun channel(o: JsonObject): Boolean {
        val k = poolKey ?: return false
        val conv = Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", terms.poolPub)), Nip44.encrypt(o.toString(), conv), k)
    }

    /** What runs the pool: from the creator's own key in v3, from the pool key before. */
    private suspend fun creatorChannel(o: JsonObject): Boolean {
        if (!terms.v3) return channel(o)
        val k = creatorKey ?: return false
        val conv = Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", terms.poolPub)), Nip44.encrypt(o.toString(), conv), k)
    }

    private val replyKey: ByteArray? get() = if (terms.v3) creatorKey else poolKey

    private suspend fun direct(toPub: String, o: JsonObject, from: ByteArray): Boolean {
        val conv = Nip44.conversationKey(from, Hashes.hexToBytes(toPub))
        return publish(Protocol.KIND_MSG, listOf(listOf("p", toPub)), Nip44.encrypt(o.toString(), conv), from)
    }

    private var lastAnnounce = 0L

    private suspend fun announce() {
        val k = poolKey ?: return
        lastAnnounce = env.now()
        val state = when (s.phase) {
            Phase.JOINING, Phase.OPEN, Phase.VOTING -> "open"
            Phase.CLOSING, Phase.SIGNING -> "closing"
            Phase.BROADCAST, Phase.CONFIRMED -> "done"
            else -> "aborted"
        }
        val peers = if (state == "open" || s.round.isEmpty()) s.seats.size else s.round.size
        val t = terms.copy(state = state, peers = peers)
        publish(Protocol.KIND_POOL, listOf(listOf("d", terms.id), listOf("t", Protocol.TAG), listOf("network", Protocol.NETWORK)),
            t.toJson().toString(), k)
    }

    private suspend fun sendJoin() {
        val kv = mutableListOf<Pair<String, Any?>>("type" to "join", "seat" to s.seat.toJson(),
            "proof" to s.joinProof, "change_sig" to s.changeSig)
        if (s.joinProofs.size > 1) kv += "proofs" to s.joinProofs
        s.pwProof?.let { kv += "pw" to it }
        direct(terms.poolPub, J.obj(*kv.toTypedArray()), joinKey)
    }

    // ------------------------------------------------------------------------------- receiving

    /** When the pool was last announced; 0 until the relay has said. */
    private var listedAt = 0L

    private suspend fun onEvent(ev: NostrEvent) {
        if (ev.kind == Protocol.KIND_POOL) {
            if (ev.pubkey == terms.poolPub && ev.tag("d") == terms.id && ev.createdAt > listedAt) listedAt = ev.createdAt
            return
        }
        if (ev.kind != Protocol.KIND_MSG) return
        when {
            // Pool channel: author and recipient are the pool key.
            ev.pubkey == terms.poolPub && ev.tag("p") == terms.poolPub -> {
                val k = poolKey ?: return
                val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(terms.poolPub))) ?: return
                onChannel(J.parse(msg), ev.createdAt, fromCreator = !terms.v3)
            }
            // v3: the creator's own messages on the channel.
            terms.v3 && ev.pubkey == terms.creatorPub && ev.tag("p") == terms.poolPub -> {
                val k = poolKey ?: return
                val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(terms.creatorPub))) ?: return
                onChannel(J.parse(msg), ev.createdAt, fromCreator = true)
            }
            // A reply from the pool (v3: from its creator) to our join key.
            ev.pubkey == (if (terms.v3) terms.creatorPub else terms.poolPub) && ev.tag("p") == joinPub -> {
                val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(joinKey, Hashes.hexToBytes(ev.pubkey))) ?: return
                onDirect(J.parse(msg))
            }
            // v3: an equal output handed in from a fresh key, readable by the creator only.
            s.creator && terms.v3 && ev.tag("p") == terms.creatorPub -> {
                val k = creatorKey ?: return
                val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(ev.pubkey))) ?: return
                onOutputHandedIn(J.parse(msg))
            }
            // A join request to the pool (only the creator can read and answer it).
            s.creator && ev.tag("p") == terms.poolPub && ev.pubkey != terms.poolPub && ev.pubkey != terms.creatorPub -> {
                val k = poolKey ?: return
                val msg = Nip44.decrypt(ev.content, Nip44.conversationKey(k, Hashes.hexToBytes(ev.pubkey))) ?: return
                onJoinRequest(ev.pubkey, J.parse(msg))
            }
        }
    }

    private suspend fun onDirect(o: JsonObject) {
        if (s.phase != Phase.JOINING) return
        when (o.optStr("type")) {
            "welcome" -> {
                val secret = o.str("pool_secret")
                // Anyone can write to our join key; only a secret that matches the pool's key is real.
                if (NostrEvent.pubOf(NostrEvent.secretOf(secret)) != terms.poolPub) return
                val token = o.str("token")
                s.poolSecret = secret.padStart(64, '0'); s.token = token; s.phase = Phase.OPEN
                s.rosterSeq = o.optInt("seq") - 1
                save(); env.event(Event.Welcomed(s.poolId))
                // The channel was subscribed before we could read it: replay it now.
                relay?.unsubscribe("pool")
                relay?.subscribe("pool", listOf(filter(listOf(Protocol.KIND_MSG), p = terms.poolPub)), waitEose = false)
            }
            "reject" -> {
                s.phase = Phase.REJECTED; s.reason = o.optStr("reason", "rejected")
                save(); env.event(Event.Rejected(s.poolId, s.reason))
            }
        }
    }

    // ------------------------------------------------------------------------------- creator

    private suspend fun creatorStart() {
        val token = randomHex(16)
        s.token = token
        s.tokens[Protocol.tokenHash(token)] = token
        s.seats = mutableListOf(if (terms.v3) s.seat.copy(tokenHash = Protocol.tokenHash(token), joinPub = terms.poolPub, proofs = s.joinProofs)
            else s.seat.copy(tokenHash = Protocol.tokenHash(token)))
        s.phase = Phase.OPEN
        save()
        announce()
        creatorChannel(roster())
    }

    private fun roster(): JsonObject {
        s.rosterSeq++; save()
        return J.obj("type" to "roster", "seq" to s.rosterSeq, "seats" to Protocol.seatsJson(s.seats))
    }

    private suspend fun reject(joinPub: String, reason: String) {
        env.log("reject: $reason")
        replyKey?.let { direct(joinPub, J.obj("type" to "reject", "reason" to reason), it) }
    }

    private suspend fun onJoinRequest(from: String, o: JsonObject) {
        if (o.optStr("type") != "join") return
        if (s.joinPubs.containsKey(from)) return // already handled (history replay)
        if (s.phase != Phase.OPEN) return reject(from, "the pool is no longer open")
        // A seated coin spent since it joined would make every newcomer leave again: out first.
        evictSpent()
        if (s.phase != Phase.OPEN) return reject(from, "the pool is no longer open")
        if (s.seats.size >= terms.maxPeers) return reject(from, "the pool is full")
        s.pwKey?.let { k ->
            val ok = constantTimeEquals(Protocol.passwordProof(Hashes.hexToBytes(k), from).encodeToByteArray(), o.optStr("pw").encodeToByteArray())
            if (!ok) return reject(from, "wrong password")
        }
        val seat = runCatching { Protocol.Seat.parse(o.obj("seat"), terms.script) }.getOrNull() ?: return reject(from, "malformed request")
        val coins = seat.coins
        if (coins.size > terms.maxInputs) return reject(from, "this pool takes at most ${terms.maxInputs} coin(s) per person")
        val outpoints = coins.map { it.outpoint }
        if (outpoints.toSet().size != outpoints.size) return reject(from, "the same coin twice")
        val held = s.seats.filter { st -> st.coins.any { it.outpoint in outpoints } }
        if (held.any { it.tokenHash == myTokenHash }) return reject(from, "that coin already has a seat")
        val proofs = o.strings("proofs") ?: listOf(o.str("proof"))
        if (proofs.size != coins.size) return reject(from, "one proof per coin is needed")
        if (coins.indices.any { !PoolTx.checkOwnership(terms.id, from, coins[it], Hashes.hexToBytes(proofs[it])) })
            return reject(from, "the coin's signature is wrong")
        val expected = PoolTx.change(seat.inputValue, terms.amount, terms.feeRate, coins.size, terms.script, perPerson)
            ?: return reject(from, "the coin is too small for this pool")
        if (seat.changeValue != expected) return reject(from, "the change does not match the pool's fee")
        if ((expected > 0) != (seat.changeScript != null)) return reject(from, "the change does not match the pool's fee")
        seat.changeScript?.let { if (!PoolTx.isType(it, terms.script)) return reject(from, "the change is of another address type") }
        val changeOk = runCatching {
            Ec.verifyDer(seat.coin.pubkey, Protocol.changeMessage(terms.id, coins, seat.changeScript, seat.changeValue),
                Hashes.hexToBytes(o.str("change_sig")))
        }.getOrDefault(false)
        if (!changeOk) return reject(from, "the change's signature is wrong")
        if (coins.any { !runCatching { env.coinUnspent(it) }.getOrDefault(false) }) return reject(from, "that coin is not unspent on the chain")
        // The same coin joining again, proven by its own key: the wallet lost its earlier seat (it left
        // without being heard, or its app was reinstalled), so that seat gives way. Its old join key
        // stays listed, or every replay of the channel would hand the seat back and forth.
        if (held.isNotEmpty()) {
            val gone = held.map { it.tokenHash }.toSet()
            s.seats.removeAll { it.tokenHash in gone }
            gone.forEach { th -> s.tokens.remove(th); s.accepts.remove(th) }
        }
        val token = randomHex(16)
        val th = Protocol.tokenHash(token)
        val placed = if (terms.v3) seat.copy(tokenHash = th, joinPub = from, proofs = proofs) else seat.copy(tokenHash = th)
        s.tokens[th] = token; s.joinPubs[from] = th; s.seats.add(placed)
        val roster = roster()
        direct(from, J.obj("type" to "welcome", "pool_secret" to s.poolSecret?.padStart(64, '0'), "token" to token,
            "seq" to s.rosterSeq), replyKey!!)
        creatorChannel(roster)
        announce()
        // A coin taking its seat back is nobody new.
        if (held.isEmpty()) env.event(Event.Joined(s.poolId, s.seats.size))
        // Full: ask everyone whether to close now (the creator's own seat counts as a yes).
        if (s.seats.size >= terms.maxPeers) {
            if (terms.v3) creatorChannel(J.obj("type" to "vote_open", "by" to myTokenHash, "vote_id" to randomHex(8)))
            else channel(J.obj("type" to "close_request", "token" to s.token, "vote_id" to randomHex(8)))
        }
    }

    /**
     * Creator: the seats among [seats] (never our own) whose coins the chain says are spent. A
     * server out of reach proves nothing: those seats stay.
     */
    private fun spentSeats(seats: List<Protocol.Seat>): Set<String> {
        val others = seats.filter { it.tokenHash != myTokenHash }
        val coins = others.flatMap { st -> st.coins.map { st.tokenHash to it } }
        if (coins.isEmpty()) return emptySet()
        val unspent = runCatching { env.coinsUnspent(coins.map { it.second }) }.getOrNull() ?: return emptySet()
        if (unspent.size != coins.size) return emptySet()
        return coins.indices.filter { unspent[it] == false }.map { coins[it].first }.toSet()
    }

    /**
     * Creator: takes the seats [gone] out of the pool, as their own leave would: a new roster, the
     * listing, and a vote under way reopened (too few now) or closed (all the rest said yes).
     */
    private suspend fun dropSeats(gone: Set<String>, why: String): Boolean {
        if (!s.seats.removeAll { it.tokenHash in gone }) return false
        env.log("seats out ($why): ${gone.size}")
        gone.forEach { s.accepts.remove(it) }; save()
        creatorChannel(roster()); announce()
        if (s.phase == Phase.VOTING && s.seats.size < terms.minPeers)
            creatorChannel(J.obj("type" to "reopen", "vote_id" to s.voteId))
        else if (s.phase == Phase.VOTING && s.seats.all { it.tokenHash in s.accepts })
            callClosing(s.seats.toList(), "agreed")
        return true
    }

    /**
     * Creator: a coin is checked unspent when it joins, but its owner may spend it afterwards. Its seat
     * would stay listed for good, and every v3 member checks the seats it is shown: each newcomer would
     * find it fake and leave. Seats whose coins are gone are taken out like a leave.
     */
    private suspend fun evictSpent(): Boolean {
        lastSeatCheck = env.now()
        val gone = spentSeats(s.seats)
        return gone.isNotEmpty() && dropSeats(gone, "coin spent")
    }

    private suspend fun callClosing(seats: List<Protocol.Seat>, why: String) {
        if (seats.size < terms.minPeers) {
            if (s.phase == Phase.VOTING) creatorChannel(J.obj("type" to "reopen", "vote_id" to s.voteId))
            return
        }
        // One closing per vote. Until it comes back from the relay we are still voting, and a last yes
        // or the vote's deadline (looked at every 15 s) used to close it again, each time with a new
        // round id: the others kept the first one, their requests for blind signatures no longer
        // matched ours, and the round timed out (fixed in the Android app's 1.3.9 too).
        if (s.voteId != null && s.closedVote == s.voteId) return
        s.closedVote = s.voteId
        // Never a round with a coin that is already spent: it could not be broadcast.
        val spent = spentSeats(seats)
        if (spent.isNotEmpty()) {
            s.closedVote = null
            lastSeatCheck = env.now()
            if (!dropSeats(spent, "coin spent at closing") && s.phase == Phase.VOTING)
                creatorChannel(J.obj("type" to "reopen", "vote_id" to s.voteId))
            return
        }
        s.round = seats.toMutableList()
        if (terms.v3) { s.roundId = randomHex(8); s.blindSigs.clear(); s.blindedOf.clear(); s.regOutputs.clear(); save() }
        val sent = creatorChannel(J.obj("type" to "closing", "why" to why, "seats" to Protocol.seatsJson(seats),
            "deadline" to env.now() + Protocol.OUTPUTS_SECONDS, "round_id" to (if (terms.v3) s.roundId else null)))
        // Not taken by the relay: the next yes or look at the deadline tries again.
        if (!sent) s.closedVote = null
        save()
    }

    private suspend fun creatorOnVote(tokenHash: String, voteId: String, accept: Boolean) {
        if (s.phase != Phase.VOTING || voteId != s.voteId) return
        if (s.seats.none { it.tokenHash == tokenHash }) return
        if (!accept) {
            if (tokenHash != myTokenHash) {
                s.seats.removeAll { it.tokenHash == tokenHash }; s.accepts.remove(tokenHash); save()
                creatorChannel(roster()); announce()
            }
            creatorChannel(J.obj("type" to "reopen", "vote_id" to voteId))
            return
        }
        s.accepts.add(tokenHash); save()
        if (s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    // ------------------------------------------------------------------------------- channel

    private suspend fun onChannel(o: JsonObject, at: Long, fromCreator: Boolean) {
        val v3 = terms.v3
        when (o.optStr("type")) {
            "roster" -> {
                if (!fromCreator || s.phase !in setOf(Phase.OPEN, Phase.VOTING)) return
                val seq = o.optInt("seq")
                if (s.creator || seq <= s.rosterSeq) return
                s.rosterSeq = seq
                val seats = Protocol.parseSeats(o.arr("seats"), terms.script)
                val before = s.seats.size
                s.seats = seats.toMutableList()
                if (myTokenHash != null && seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "left the pool"
                }
                save()
                // v3: every seat's coins are checked here too, not only by the creator. A seat that can't
                // be checked now (the server is out of reach) is checked again with the next roster; one
                // that is not real gets the creator some minutes to take it out (its coin may have been
                // spent after it joined) before we leave; a round never closes with it (see "closing").
                if (v3 && s.phase != Phase.ABORTED && checkFakeSeats(seats)) return
                if (!s.creator && seats.size > before && before > 0) env.event(Event.Joined(s.poolId, seats.size))
            }
            "close_request" -> {
                if (v3) {
                    if (!s.creator || s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
                    val th = o.str("token_hash"); val id = o.str("vote_id")
                    if (s.seats.none { it.tokenHash == th } || !Protocol.macOk(s.tokens[th], o.str("mac"), "close", id)) return
                    creatorChannel(J.obj("type" to "vote_open", "by" to th, "vote_id" to id))
                    return
                }
                openVote(Protocol.tokenHash(o.str("token")), o.str("vote_id"), at)
            }
            "vote_open" -> if (v3 && fromCreator) openVote(o.str("by"), o.str("vote_id"), at)
            "vote" -> if (s.creator) {
                if (v3) {
                    val th = o.str("token_hash"); val id = o.str("vote_id"); val yes = o.bool("accept")
                    if (!Protocol.macOk(s.tokens[th], o.str("mac"), "vote", id, yes)) return
                    creatorOnVote(th, id, yes)
                } else creatorOnVote(Protocol.tokenHash(o.str("token")), o.str("vote_id"), o.bool("accept"))
            }
            "reopen" -> {
                if (!fromCreator || s.phase != Phase.VOTING || o.optStr("vote_id") != s.voteId) return
                s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear(); save()
                env.event(Event.CloseRefused(s.poolId))
            }
            "closing" -> {
                // v3: the round closed again ("again": n) without seats that never asked for their blind
                // signatures; only a member still collecting outputs takes it, and only a newer one (replays).
                val again = o.optInt("again", 0)
                if (!fromCreator) return
                // A creator before Android 1.3.9 could close the same vote again with a new round id, and goes
                // on with its latest: a later closing replaces ours while we wait for our blind signatures.
                val replaces = v3 && !s.creator && again == 0 && s.phase == Phase.CLOSING && s.closeSeq == 0 &&
                    s.outSigs.isEmpty() && at > s.closingAt && o.optStr("round_id").let { it.isNotEmpty() && it != s.roundId }
                if (again == 0 && s.phase !in setOf(Phase.OPEN, Phase.VOTING) && !replaces) return
                if (again > 0 && (!v3 || s.creator || s.phase != Phase.CLOSING || again <= s.closeSeq)) return
                val seats = Protocol.parseSeats(o.arr("seats"), terms.script)
                if (terms.v5 && seats.count { isCreatorSeat(it) } != 1) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed without the payment"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (myTokenHash == null || seats.none { it.tokenHash == myTokenHash }) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed without us"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (seats.size < terms.minPeers) {
                    s.phase = Phase.ABORTED; s.reason = "the round closed with fewer than ${terms.minPeers} people"; save()
                    env.event(Event.Aborted(s.poolId, s.reason)); return
                }
                if (v3) {
                    val id = o.optStr("round_id")
                    if (id.isEmpty() || (s.creator && id != s.roundId)) return
                    // Every seat of the round checked again now: a coin spent since it joined, or one never
                    // checked that can't be checked now, and we don't go on with this round.
                    if (!s.creator) seats.firstOrNull { st ->
                        val known = st.tokenHash in verifiedSeats
                        when (seatIsReal(st, fresh = true)) { true -> false; false -> true; null -> !known }
                    }?.let {
                        s.phase = Phase.ABORTED; s.reason = "the round closed with a seat whose coin is not real or could not be checked"; save()
                        env.event(Event.Aborted(s.poolId, s.reason)); return
                    }
                    s.roundId = id
                }
                if (again > 0) {
                    // Our outputs' blind signatures still hold (the same key signed them): they go in again, to this round.
                    s.closeSeq = again; s.outputs.clear(); s.handedIn.clear(); s.postedOutput = false; outputScheduled.clear()
                    env.log("the round closed again without ${s.round.size - seats.size} seat(s) that never asked for their signature")
                }
                if (replaces) env.log("the creator closed the round again: round ${s.roundId}")
                s.round = seats.toMutableList(); s.phase = Phase.CLOSING; s.closingAt = at
                s.phaseDeadline = o.optLong("deadline", at + Protocol.OUTPUTS_SECONDS)
                save()
                if (s.creator) announce()
                env.event(Event.Closing(s.poolId, seats.size))
                if (!v3) scheduleOutput()
                else if (s.creator) {
                    // The creator's own outputs need no blinding from itself (v5: they are the payments).
                    if (!terms.v5) s.mixScripts.forEach { s.regOutputs.add(it.toHex()) }
                    s.postedOutput = true; save()
                    listOutputsIfAll()
                } else sendBlind()
            }
            "output" -> {
                if (v3 || s.phase != Phase.CLOSING) return
                val script = o.str("script")
                if (!scriptOk(script)) return
                s.outputs.add(script); save()
                if (s.outputs.size > s.round.size) return abort("more equal outputs than people")
                if (s.outputs.size == s.round.size) toSigning()
            }
            "blind" -> if (v3 && s.creator && s.phase == Phase.CLOSING) {
                val th = o.str("token_hash"); val id = o.str("round_id")
                val blinded = blindedList(o) ?: return
                if (id != s.roundId || s.round.none { it.tokenHash == th }) return
                val digest = blindDigest(blinded)
                if (!Protocol.macOk(s.tokens[th], o.str("mac"), "blind", id, digest)) return
                val sig = s.blindSigs[th]?.let { if (s.blindedOf[th] == digest) it else return }
                    ?: BlindRsa.KeyPair.decode(s.blindPriv ?: return).let { kp ->
                        blinded.joinToString(",") { BlindRsa.blindSign(kp, Hashes.hexToBytes(it)).toHex() }
                    }.also { s.blindSigs[th] = it; s.blindedOf[th] = digest; save() }
                val reply = if (perPerson == 1) J.obj("type" to "blind_sig", "token_hash" to th, "round_id" to id, "sig" to sig)
                    else J.obj("type" to "blind_sig", "token_hash" to th, "round_id" to id, "sigs" to sig.split(","))
                creatorChannel(reply)
            }
            "blind_sig" -> if (v3 && fromCreator && s.phase == Phase.CLOSING && !s.creator) {
                if (o.str("token_hash") != myTokenHash || o.str("round_id") != s.roundId || s.outSigs.isNotEmpty()) return
                val pk = blindPk ?: return
                if (s.blindInvs.size != perPerson || s.blindPrefixes.size != perPerson) return
                val given = if (perPerson == 1) listOf(o.str("sig")) else o.strings("sigs") ?: return
                if (given.size != perPerson) return abort("the creator's blind signature is wrong")
                val sigs = s.mixScripts.indices.map { i ->
                    val prepared = Hashes.hexToBytes(s.blindPrefixes[i]) + s.mixScripts[i]
                    runCatching { BlindRsa.finalize(pk, prepared, Hashes.hexToBytes(given[i]), BigNat.fromHex(s.blindInvs[i])).toHex() }
                        .getOrElse { return abort("the creator's blind signature is wrong") }
                }
                s.outSigs = sigs; save()
                scheduleOutput()
            }
            "outputs" -> if (v3 && fromCreator && s.phase == Phase.CLOSING && o.optStr("round_id") == s.roundId) {
                val list = o.strings("scripts") ?: return
                if (list.size != handingIn * perPerson || list.toSet().size != list.size || !list.all { scriptOk(it) })
                    return abort("the list of equal outputs is wrong")
                val pays = if (!terms.v5) emptyList() else {
                    val p = o.optArr("payments") ?: return abort("the payments are missing")
                    val l = p.map { (it as JsonObject).let { x -> x.str("script") to x.long("value") } }
                    if (l.map { it.second }.sorted() != terms.payments.sorted() || !l.all { scriptOk(it.first) } ||
                        l.map { it.first }.toSet().size != l.size || l.any { it.first in list }) return abort("the payments are not the ones announced")
                    l.map { "${it.first}:${it.second}" }
                }
                s.outputs.clear(); s.outputs.addAll(list); s.payOutputs.clear(); s.payOutputs.addAll(pays); save()
                toSigning()
            }
            "sig" -> {
                if (s.phase != Phase.SIGNING && s.phase != Phase.BROADCAST) return
                val plan = buildPlan() ?: return
                val outpoint = o.str("outpoint")
                val coin = plan.coins.firstOrNull { it.outpoint == outpoint } ?: return
                val sig = o.str("sig")
                if (!PoolTx.verify(plan, coin, Hashes.hexToBytes(sig))) return
                s.sigs[outpoint] = sig; save()
                maybeBroadcast(plan)
            }
            "tx" -> if (s.txid == null && s.phase in setOf(Phase.SIGNING, Phase.BROADCAST)) {
                val plan = buildPlan() ?: return
                if (s.sigs.size == plan.coins.size) return maybeBroadcast(plan)
                val txid = o.str("txid")
                if (txid != PoolTx.txid(plan)) return
                s.txid = txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); save()
                if (s.creator) announce()
                env.event(Event.Broadcast(s.poolId, txid))
            }
            "leave" -> if (s.creator && s.phase in setOf(Phase.OPEN, Phase.VOTING)) {
                val th = if (v3) o.str("token_hash").takeIf { Protocol.macOk(s.tokens[it], o.str("mac"), "leave") } ?: return
                    else Protocol.tokenHash(o.optStr("token"))
                dropSeats(setOf(th), "left")
            }
            // A round closed again takes an abort from its creator only: a seat left out of it (an older
            // app waking up after the first closing ran out) would otherwise end the round for the rest.
            "abort" -> if (!done && s.phase != Phase.BROADCAST && !(v3 && !fromCreator && s.closeSeq > 0)) {
                s.phase = Phase.ABORTED; s.reason = o.optStr("reason", "aborted"); save()
                // A member's abort can reach the creator before its own deadline does: the listing must
                // still say the pool is over.
                if (s.creator) announce()
                env.event(Event.Aborted(s.poolId, s.reason))
            }
        }
    }

    private suspend fun openVote(th: String, voteId: String, at: Long) {
        if (s.phase != Phase.OPEN || s.seats.size < terms.minPeers) return
        if (s.seats.none { it.tokenHash == th }) return
        s.phase = Phase.VOTING; s.voteId = voteId; s.voteBy = th
        s.voteDeadline = at + Protocol.VOTE_SECONDS
        s.accepts.clear(); s.accepts.add(th)
        save()
        env.event(Event.CloseRequested(s.poolId, s.seats.size, th == myTokenHash))
        if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    private fun scriptOk(script: String) =
        script.matches(Regex(if (terms.script == PoolType.P2TR) "5120[0-9a-f]{64}" else "0014[0-9a-f]{40}"))

    /**
     * v3: a listed seat's coins are signed for its join key and unspent on the chain; null when the
     * chain can't be asked right now. [fresh]: asked again even if it checked out before.
     */
    private fun seatIsReal(seat: Protocol.Seat, fresh: Boolean = false): Boolean? {
        if (seat.tokenHash == myTokenHash) return true
        if (!fresh && seat.tokenHash in verifiedSeats) return true
        val jp = seat.joinPub ?: return false
        if (seat.proofs.size != seat.coins.size) return false
        if (!seat.coins.indices.all { PoolTx.checkOwnership(terms.id, jp, seat.coins[it], Hashes.hexToBytes(seat.proofs[it])) }) return false
        for (c in seat.coins) {
            val unspent = runCatching { env.coinUnspent(c) }.getOrElse { return null }
            if (!unspent) { verifiedSeats.remove(seat.tokenHash); return false }
        }
        verifiedSeats.add(seat.tokenHash)
        return true
    }

    /**
     * Member, v3: notes the listed seats that are not real and, once one has stayed listed for
     * [FAKE_GRACE], gives up our seat. A creator on a current version takes a spent coin's seat out
     * within [SEAT_CHECK_SECONDS]; one that doesn't is not a pool to stay in. True when we left.
     */
    private suspend fun checkFakeSeats(seats: List<Protocol.Seat>): Boolean {
        val now = env.now()
        lastSeatCheck = now
        fakeSince.keys.retainAll(seats.map { it.tokenHash }.toSet())
        for (st in seats) when (seatIsReal(st, fresh = st.tokenHash in fakeSince)) {
            false -> if (st.tokenHash !in fakeSince) fakeSince[st.tokenHash] = now
            true -> fakeSince.remove(st.tokenHash)
            null -> {}
        }
        if (fakeSince.values.none { now - it >= FAKE_GRACE }) return false
        sendLeave()
        s.phase = Phase.ABORTED; s.reason = "the pool listed a seat whose coin is not real"; save()
        env.event(Event.Aborted(s.poolId, s.reason))
        return true
    }

    /** Tells the creator we give up our seat, so the coin is free to join again. */
    private suspend fun sendLeave() {
        if (terms.v3) channel(J.obj("type" to "leave", "token_hash" to (myTokenHash ?: ""), "mac" to (s.token?.let { Protocol.mac(it, "leave") } ?: "")))
        else channel(J.obj("type" to "leave", "token" to (s.token ?: "")))
    }

    /** v3 member: ask the creator to sign our outputs blindly (the same request again if resent). */
    private suspend fun sendBlind() {
        if (s.outSigs.isNotEmpty()) { scheduleOutput(); return }
        val pk = blindPk ?: return abort("the pool has no blind-signing key")
        val round = s.roundId ?: return
        if (s.blindedMsgs.size != perPerson) {
            val prefixes = mutableListOf<String>(); val invs = mutableListOf<String>(); val msgs = mutableListOf<String>()
            for (script in s.mixScripts) {
                val prepared = BlindRsa.prepare(script)
                val b = BlindRsa.blind(pk, prepared)
                prefixes += prepared.copyOfRange(0, BlindRsa.PREFIX_LEN).toHex()
                invs += b.inv.toHex(); msgs += b.blindedMsg.toHex()
            }
            s.blindPrefixes = prefixes; s.blindInvs = invs; s.blindedMsgs = msgs; save()
        }
        val digest = blindDigest(s.blindedMsgs)
        lastBlindSent = env.now()
        val token = s.token ?: return
        channel(J.obj("type" to "blind", "token_hash" to myTokenHash, "round_id" to round,
            "mac" to Protocol.mac(token, "blind", round, digest),
            "blinded" to (if (perPerson == 1) s.blindedMsgs.first() else s.blindedMsgs)))
    }

    /** The blinded messages of a `blind` request: one string (v3) or exactly [perPerson] of them (v4). */
    private fun blindedList(o: JsonObject): List<String>? {
        val list = if (perPerson == 1) listOf(o.optStr("blinded")) else runCatching { o.strings("blinded") }.getOrNull() ?: return null
        if (list.size != perPerson || list.any { !it.matches(Regex("([0-9a-f]{2})+")) }) return null
        return list
    }

    private fun blindDigest(blinded: List<String>): String =
        Hashes.sha256(blinded.map { Hashes.hexToBytes(it) }.reduce { a, b -> a + b }).toHex()

    /** v3 creator: an output handed in with its unblinded signature, from a key nobody can tie to a seat. */
    private suspend fun onOutputHandedIn(o: JsonObject) {
        if (o.optStr("type") != "output" || s.phase != Phase.CLOSING || o.optStr("round_id") != s.roundId) return
        val script = o.str("script")
        if (!scriptOk(script)) return
        val prefix = Hashes.hexToBytes(o.str("prefix"))
        if (prefix.size != BlindRsa.PREFIX_LEN) return
        val pk = blindPk ?: return
        if (!BlindRsa.verify(pk, prefix + Hashes.hexToBytes(script), Hashes.hexToBytes(o.str("sig")))) return
        s.regOutputs.add(script); save()
        listOutputsIfAll()
    }

    /** v3 creator: once every seat of the round has its outputs in, list them all at once. */
    private suspend fun listOutputsIfAll() {
        if (s.phase != Phase.CLOSING) return
        val want = handingIn * perPerson
        if (s.regOutputs.size > want) return abort("more equal outputs than people")
        if (s.regOutputs.size < want) return
        val pays = if (terms.v5) s.mixScripts.indices.map { i -> J.obj("script" to s.mixScripts[i].toHex(), "value" to terms.payments[i]) } else null
        creatorChannel(J.obj("type" to "outputs", "round_id" to s.roundId, "scripts" to s.regOutputs.sorted(), "payments" to pays))
    }

    /** Each of our outputs after a random wait of its own, so nobody can tie them to us or to each other. */
    private fun scheduleOutput() {
        if (s.postedOutput) return
        for (i in s.mixScripts.indices) {
            if (i in s.handedIn || i in outputScheduled) continue
            outputScheduled += i
            val delaySec = handInDelaySeconds()
            scope.launch {
                delay(delaySec * 1000)
                lock.withLock {
                    outputScheduled -= i
                    if (s.phase == Phase.CLOSING && !s.postedOutput && i !in s.handedIn) {
                        val ok = if (terms.v3) handInOutput(i) else channel(J.obj("type" to "output", "script" to s.mixScript.toHex()))
                        if (ok) {
                            s.handedIn += i
                            if (s.handedIn.size == s.mixScripts.size) s.postedOutput = true
                            save()
                        }
                    }
                }
            }
        }
    }

    /**
     * v3: our output [i] with its unblinded signature, from a key made for it alone, over a
     * connection of its own, encrypted to the creator: nothing in it points back at our seat.
     */
    private suspend fun handInOutput(i: Int): Boolean {
        val sig = s.outSigs.getOrNull(i) ?: return false
        val fresh = NostrEvent.newSecret()
        val o = J.obj("type" to "output", "round_id" to s.roundId, "script" to s.mixScripts[i].toHex(),
            "prefix" to s.blindPrefixes[i], "sig" to sig)
        val ev = NostrEvent.sign(fresh, Protocol.KIND_MSG, listOf(listOf("p", terms.creatorPub!!)),
            Nip44.encrypt(o.toString(), Nip44.conversationKey(fresh, Hashes.hexToBytes(terms.creatorPub))))
        val r = RelayClient(Protocol.RELAY, sockets, { _, _ -> })
        return try {
            r.connect()
            val res = r.publish(ev)
            env.log("hand in output -> ${res.first} ${res.second}")
            res.first
        } catch (e: Exception) { env.log("hand in output failed: ${e.message}"); false }
        finally { runCatching { r.close() } }
    }

    private fun payments(): List<TxBuilder.Output> = s.payOutputs.map {
        TxBuilder.Output(Hashes.hexToBytes(it.substringBefore(':')), it.substringAfter(':').toLong())
    }

    private fun buildPlan(): PoolTx.Plan? {
        if (s.round.size < Protocol.MIN_PEERS || s.outputs.size != handingIn * perPerson) return null
        if (terms.v5 && s.payOutputs.size != terms.payments.size) return null
        return PoolTx.plan(
            s.round.flatMap { it.coins },
            s.outputs.map { Hashes.hexToBytes(it) },
            s.round.filter { it.changeScript != null && it.changeValue > 0 }.map { TxBuilder.Output(it.changeScript!!, it.changeValue) },
            terms.amount, perPerson, payments(),
        )
    }

    /** What we check before signing [plan]: null when it pays us right, else why not. */
    fun checkPlan(plan: PoolTx.Plan): String? {
        val change = s.seat.changeScript?.let { TxBuilder.Output(it, s.seat.changeValue) }
        if (!terms.v5) return PoolTx.checkOurs(plan, s.mixScripts, change, terms.amount, terms.feeRate, s.round.map { it.coins.size }, perPerson)
        val mine = if (s.creator) s.mixScripts.indices.map { TxBuilder.Output(s.mixScripts[it], terms.payments[it]) }
            else s.mixScripts.map { TxBuilder.Output(it, terms.amount) }
        return PoolTx.checkPayment(plan, mine, change, terms.amount, terms.feeRate, s.round.map { it.coins.size },
            s.round.map { if (isCreatorSeat(it)) terms.payments.size else perPerson }, perPerson, terms.payments,
            s.round.count { it.changeScript != null && it.changeValue > 0 })
    }

    private suspend fun toSigning() {
        val plan = runCatching { buildPlan() }.getOrElse { return abort("the transaction does not add up: ${it.message}") } ?: return
        checkPlan(plan)?.let { return abort(it) }
        s.phase = Phase.SIGNING; s.phaseDeadline = env.now() + Protocol.SIGN_SECONDS; save()
        env.event(Event.SignNeeded(s.poolId, plan.coins.size))
    }

    /** The user checked and approved: sign each of our inputs, [privateKeys] in the order of [State.coinPaths]. */
    suspend fun sign(privateKeys: List<ByteArray>): Unit = lock.withLock {
        check(s.phase == Phase.SIGNING) { "nothing to sign right now" }
        require(privateKeys.size == s.seat.coins.size) { "one key per coin is needed" }
        val plan = buildPlan() ?: error("the transaction is not complete")
        checkPlan(plan)?.let { error(it) }
        s.seat.coins.forEachIndexed { i, c ->
            val mine = plan.coins.first { it.outpoint == c.outpoint }
            val sig = PoolTx.sign(plan, mine, privateKeys[i]).toHex()
            s.sigs[mine.outpoint] = sig
            check(channel(J.obj("type" to "sig", "outpoint" to mine.outpoint, "sig" to sig))) { "the relay did not take our signature" }
        }
        s.postedSig = true; save()
        maybeBroadcast(plan)
    }

    private suspend fun maybeBroadcast(plan: PoolTx.Plan) {
        if (s.sigs.size < plan.coins.size || s.txid != null) return
        val tx = PoolTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) })
        val ok = runCatching { env.broadcast(tx.rawHex) }
        // Another member may have broadcast it already: the same txid is just as good.
        if (ok.isFailure && runCatching { env.confirmations(tx.txid) }.getOrNull() == null) {
            val msg = ok.exceptionOrNull()?.message ?: "broadcast failed"
            if (!msg.contains("already", true)) return abort("broadcast failed: $msg")
        }
        s.txid = tx.txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); save()
        channel(J.obj("type" to "tx", "txid" to tx.txid))
        announce()
        env.event(Event.Broadcast(s.poolId, tx.txid))
    }

    private suspend fun abort(reason: String) {
        if (done) return
        s.phase = Phase.ABORTED; s.reason = reason; save()
        // v3: the creator's from its own key, which every v3 wallet takes as the creator's.
        if (s.creator && terms.v3) creatorChannel(J.obj("type" to "abort", "reason" to reason))
        else channel(J.obj("type" to "abort", "reason" to reason))
        if (s.creator) announce()
        env.event(Event.Aborted(s.poolId, reason))
    }

    /**
     * v3 creator, the outputs' time over: the seats of the round that never even asked for their blind
     * signatures (a phone asleep, an app closed) are taken out and the round closes again with the rest,
     * if they are still enough people. A seat that got its signature but handed nothing in can't be told
     * apart (that is the point of blinding): then the round is cancelled, as before. True when it closed again.
     */
    private suspend fun closeAgain(): Boolean {
        if (s.closeSeq >= MAX_RECLOSES) return false
        val missing = s.round.filter { it.tokenHash != myTokenHash && !(terms.v5 && isCreatorSeat(it)) && it.tokenHash !in s.blindSigs }
        if (missing.isEmpty()) return false
        val gone = missing.map { it.tokenHash }.toSet()
        val rest = s.round.filter { it.tokenHash !in gone }
        if (rest.size < Protocol.MIN_PEERS || rest.size < terms.minPeers) return false
        if (terms.v5 && rest.count { isCreatorSeat(it) } != 1) return false
        if (spentSeats(rest).isNotEmpty()) return false
        s.seats.removeAll { it.tokenHash in gone }; gone.forEach { s.accepts.remove(it) }
        s.closeSeq++
        s.round = rest.toMutableList(); s.roundId = randomHex(8); s.regOutputs.clear()
        // The blind signatures given stay: the same requests get the same answers in the new round.
        if (!terms.v5) s.mixScripts.forEach { s.regOutputs.add(it.toHex()) }
        s.postedOutput = true
        s.phaseDeadline = env.now() + Protocol.OUTPUTS_SECONDS
        save()
        env.log("closing again without ${gone.size} seat(s) that never asked for their signature")
        creatorChannel(J.obj("type" to "closing", "why" to "again", "seats" to Protocol.seatsJson(rest),
            "deadline" to s.phaseDeadline, "round_id" to s.roundId, "again" to s.closeSeq))
        announce()
        env.event(Event.Closing(s.poolId, rest.size))
        listOutputsIfAll()
        return true
    }

    // ------------------------------------------------------------------------------- user actions

    /** Ask everyone to close the pool now (enough people in). */
    suspend fun requestClose(): Unit = lock.withLock {
        check(s.phase == Phase.OPEN) { "the pool is not open" }
        check(s.seats.size >= terms.minPeers) { "at least ${terms.minPeers} people are needed" }
        val id = randomHex(8)
        when {
            !terms.v3 -> channel(J.obj("type" to "close_request", "token" to s.token, "vote_id" to id))
            s.creator -> creatorChannel(J.obj("type" to "vote_open", "by" to myTokenHash, "vote_id" to id))
            else -> channel(J.obj("type" to "close_request", "token_hash" to myTokenHash, "vote_id" to id,
                "mac" to Protocol.mac(s.token ?: error("no seat"), "close", id)))
        }.let { check(it) { "the relay did not take the request" } }
    }

    suspend fun vote(accept: Boolean): Unit = lock.withLock {
        val id = s.voteId ?: return@withLock
        if (s.phase != Phase.VOTING || s.votedOn == id) return@withLock
        if (terms.v3) channel(J.obj("type" to "vote", "token_hash" to myTokenHash, "vote_id" to id, "accept" to accept,
            "mac" to Protocol.mac(s.token ?: return@withLock, "vote", id, accept)))
        else channel(J.obj("type" to "vote", "token" to s.token, "vote_id" to id, "accept" to accept))
        s.votedOn = id
        // A member saying "not yet" leaves this round; the creator keeps its pool open.
        if (!accept && !s.creator) { s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE }
        save()
    }

    /** Leave an open pool: the creator ends it for everyone, a member just gives up its seat. */
    suspend fun leave(): Unit = lock.withLock {
        if (s.phase !in setOf(Phase.JOINING, Phase.OPEN, Phase.VOTING)) return@withLock
        if (s.creator) { abort("the creator closed the pool"); return@withLock }
        if (s.phase == Phase.JOINING) { s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE; save(); return@withLock }
        sendLeave()
        s.phase = Phase.ABORTED; s.reason = LEFT_BY_CHOICE; save()
    }

    // ------------------------------------------------------------------------------- timers

    private suspend fun tick() {
        runCatching {
            val now = env.now()
            ensureConnected()
            if (s.creator && s.phase in setOf(Phase.OPEN, Phase.VOTING) && now - lastAnnounce > Protocol.HEARTBEAT) announce()
            if (!s.creator && s.phase in setOf(Phase.JOINING, Phase.OPEN, Phase.VOTING) && listedAt > 0 &&
                now - listedAt > Protocol.STALE_AFTER) {
                s.phase = Phase.ABORTED
                s.reason = "the pool's creator stopped answering"
                save(); env.event(Event.Aborted(s.poolId, s.reason))
                return
            }
            // The seats' coins, again from time to time: the creator takes out the spent ones, a member
            // looks again at those it found fake (and leaves once their grace is over).
            if (s.phase in setOf(Phase.OPEN, Phase.VOTING) && now - lastSeatCheck >= SEAT_CHECK_SECONDS) {
                if (s.creator) evictSpent()
                else if (terms.v3 && fakeSince.isNotEmpty() && checkFakeSeats(s.seats)) return@runCatching
                else lastSeatCheck = now
            }
            when (s.phase) {
                Phase.OPEN, Phase.JOINING -> if (s.phase == Phase.OPEN && !s.warnedExpiry && now <= terms.expiresAt &&
                    terms.expiresAt - now <= EXPIRY_WARN_SECONDS && s.seats.size >= terms.minPeers && s.seats.size < terms.maxPeers) {
                    s.warnedExpiry = true; save()
                    env.event(Event.ExpiringSoon(s.poolId, s.seats.size, ((terms.expiresAt - now) / 60).toInt().coerceAtLeast(1)))
                } else if (now > terms.expiresAt) {
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (s.phase == Phase.JOINING && !s.creator && now - s.created > JOIN_TIMEOUT) {
                    s.phase = Phase.ABORTED
                    s.reason = "the pool did not answer (it may have ended, or its creator's app is closed)"
                    save(); env.event(Event.Aborted(s.poolId, s.reason))
                }
                Phase.VOTING -> if (now > terms.expiresAt) {
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (!s.creator && now > s.voteDeadline + VOTE_GRACE) {
                    s.phase = Phase.ABORTED
                    s.reason = "the pool's creator stopped answering"
                    save(); env.event(Event.Aborted(s.poolId, s.reason))
                } else if (s.creator && now > s.voteDeadline) {
                    val yes = s.seats.filter { it.tokenHash in s.accepts }
                    if (yes.size >= terms.minPeers) callClosing(yes, "agreed")
                    else {
                        val silent = s.seats.filter { it.tokenHash !in s.accepts && it.tokenHash != myTokenHash }
                        if (silent.isNotEmpty()) {
                            s.seats.removeAll(silent.toSet()); save()
                            creatorChannel(roster()); announce()
                        }
                        creatorChannel(J.obj("type" to "reopen", "vote_id" to s.voteId))
                    }
                }
                Phase.CLOSING -> if (terms.v3 && s.creator && now > s.phaseDeadline + RECLOSE_AFTER && closeAgain()) {}
                else if (now > s.phaseDeadline + 60) abort("not everyone sent their output in time")
                else if (terms.v3 && !s.creator) {
                    if (s.outSigs.isEmpty() && now - lastBlindSent > 30) sendBlind()
                    else if (s.outSigs.isNotEmpty() && !s.postedOutput) scheduleOutput()
                } else if (terms.v3 && s.creator) listOutputsIfAll()
                Phase.SIGNING -> if (now > s.phaseDeadline + 60) abort("not everyone signed in time")
                Phase.BROADCAST -> s.txid?.let { txid ->
                    val c = runCatching { env.confirmations(txid) }.getOrNull()
                    if (c != null && c >= 1) { s.phase = Phase.CONFIRMED; save(); env.event(Event.Confirmed(s.poolId, txid)); relay?.close(); relay = null }
                    else if (c == null) {
                        if (s.phaseDeadline > 0 && now > s.phaseDeadline + BROADCAST_GIVE_UP) {
                            s.phase = Phase.ABORTED; s.reason = "the transaction never appeared: a coin of the round was spent elsewhere"
                            save(); env.event(Event.Aborted(s.poolId, s.reason)); relay?.close(); relay = null
                        }
                    } else s.phaseDeadline = now // seen (unconfirmed): wait for it, however long it takes
                }
                else -> {}
            }
        }.onFailure { env.log("tick: ${it.message}") }
    }

    private fun randomHex(n: Int) = secureRandomBytes(n).toHex()

    companion object {
        /** Reason of a round this wallet left on purpose: its card goes away by itself. */
        const val LEFT_BY_CHOICE = "you left this round"
        const val BROADCAST_GIVE_UP = 12 * 3600L
        const val JOIN_TIMEOUT = 5 * 60
        const val VOTE_GRACE = 3 * 60L
        const val EXPIRY_WARN_SECONDS = 3600
        /** Seconds between checks of the seats' coins while a pool is open (tests make it shorter). */
        var SEAT_CHECK_SECONDS = 120L
        /** Seconds a member stays in a pool that lists a seat whose coin is not real. */
        var FAKE_GRACE = 600L
        /** Seconds past the outputs' deadline when a v3 creator closes the round again without the silent seats (members wait 60). */
        var RECLOSE_AFTER = 15L
        /** Times a round may close again so. */
        const val MAX_RECLOSES = 2

        /** Each output's random wait before it is handed in: 3 to 42 seconds (shorter only in tests). */
        var handInDelaySeconds: () -> Long = { 3L + (secureRandomBytes(1)[0].toInt() and 0xFF) % 40 }

        /**
         * Everything to create a seat, done once at join/create time with the coins' keys at hand:
         * the ownership proofs and the signed change. The keys themselves are not kept.
         * [mixScripts]: one unused address per equal output the pool gives each person (v5 creator: one per payment).
         */
        fun newState(
            terms: Protocol.Terms, creator: Boolean, poolSecret: String?, coins: List<PoolTx.Coin>, coinKeys: List<ByteArray>,
            coinPaths: List<String>, mixScripts: List<ByteArray>, changeScript: ByteArray?,
            password: String? = null, creatorSecret: String? = null, blindPriv: String? = null,
        ): State {
            if (creator && terms.v3) require(creatorSecret != null && blindPriv != null) { "a v3 pool's creator needs its keys" }
            require(coins.isNotEmpty() && coinKeys.size == coins.size && coinPaths.size == coins.size) { "one key and path per coin" }
            require(coins.size <= terms.maxInputs) { "this pool takes at most ${terms.maxInputs} coin(s) per person" }
            require(coins.all { it.type == terms.script }) { "these coins are not of the pool's address type" }
            require(coins.map { it.outpoint }.toSet().size == coins.size) { "the same coin twice" }
            coins.indices.forEach { require(Ec.publicKey(coinKeys[it]).contentEquals(coins[it].pubkey)) { "a key does not match its coin" } }
            val outs = if (terms.v5 && creator) terms.payments.size else terms.outputs
            require(mixScripts.size == outs) { if (terms.v5 && creator) "one address per payment" else "this pool gives ${terms.outputs} equal output(s) each" }
            require(mixScripts.all { PoolTx.isType(it, terms.script) }) { "an output is not of the pool's address type" }
            require(mixScripts.map { it.toHex() }.toSet().size == mixScripts.size) { "the same address twice" }
            require(changeScript == null || mixScripts.none { it.contentEquals(changeScript) }) { "the change and an output share an address" }
            val changeValue = PoolTx.changeOf(coins.sumOf { it.value }, terms.mixTotal, terms.feeRate, coins.size, terms.script, outs)
                ?: error("these coins are too small for the pool")
            val cs = if (changeValue > 0) changeScript ?: error("a change address is needed") else null
            cs?.let { require(PoolTx.isType(it, terms.script)) { "the change is not of the pool's address type" } }
            val joinSecret = NostrEvent.newSecret()
            val joinPub = NostrEvent.pubOf(joinSecret)
            val proofs = coins.indices.map {
                PoolTx.proveOwnership(terms.id, if (creator) terms.poolPub else joinPub, coins[it], coinKeys[it]).toHex()
            }
            val changeSig = Ec.signDer(coinKeys[0], Protocol.changeMessage(terms.id, coins, cs, changeValue)).toHex()
            return State(
                terms = terms, creator = creator, poolSecret = poolSecret, joinSecret = joinSecret.toHex(),
                token = null, seat = Protocol.Seat("", coins, cs, changeValue), joinProofs = proofs, changeSig = changeSig,
                mixScripts = mixScripts, coinPaths = coinPaths,
                pwKey = if (creator && terms.private) Protocol.passwordKey(terms.id, password ?: error("a private pool needs a password")).toHex() else null,
                pwProof = if (!creator && terms.private)
                    Protocol.passwordProof(Protocol.passwordKey(terms.id, password ?: error("this pool needs its password")), joinPub) else null,
                creatorSecret = if (creator) creatorSecret else null, blindPriv = if (creator) blindPriv else null,
            )
        }

        /** A new pool's terms and keys; the v3 ones ([creatorSecret], [blindPriv]) stay with its creator. */
        data class NewPool(val terms: Protocol.Terms, val secret: String, val creatorSecret: String? = null, val blindPriv: String? = null)

        /**
         * Terms for a new pool, with its own fresh keys. This app opens v3 pools (v4 with several
         * outputs each, v5 with [payments]); [v3] false (v1/v2) only for the tests.
         */
        fun newPool(amount: Long, feeRate: Double, maxPeers: Int, hours: Int, minPeers: Int = Protocol.MIN_PEERS,
                    private: Boolean = false, script: PoolType = PoolType.P2WPKH,
                    maxInputs: Int = 1, v3: Boolean = true, outputs: Int = 1,
                    payments: List<Long> = emptyList(), now: Long = NostrEvent.nowSeconds()): NewPool {
            val k = NostrEvent.newSecret()
            val id = secureRandomBytes(8).toHex()
            require(minPeers in Protocol.MIN_PEERS..maxPeers) { "the minimum must be between 2 and the maximum" }
            require(maxInputs in 1..Protocol.MAX_INPUTS) { "1 to ${Protocol.MAX_INPUTS} coins per person" }
            val v1 = script == PoolType.P2WPKH && maxInputs == 1
            require(outputs in 1..Protocol.MAX_OUTPUTS) { "1 to ${Protocol.MAX_OUTPUTS} equal outputs per person" }
            require(outputs == 1 || v3) { "several outputs each need a v4 pool" }
            require(payments.isEmpty() || (v3 && Protocol.paymentsOk(payments, amount, outputs))) {
                "the payments must add up to what each joiner gets"
            }
            if (v3) {
                val ck = NostrEvent.newSecret()
                val blind = BlindRsa.generate()
                return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                    minPeers, private, if (payments.isNotEmpty()) Protocol.V5 else if (outputs > 1) Protocol.V4 else Protocol.V3,
                    script, maxInputs, NostrEvent.pubOf(ck), blind.public.encode(), outputs, payments),
                    k.toHex(), ck.toHex(), blind.encodePrivate())
            }
            return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                minPeers, private, if (v1) Protocol.V1 else Protocol.VERSION, script, maxInputs), k.toHex())
        }
    }
}
