package com.kilombino.kilowallet.core.kilojoin

import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.TxBuilder
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One wallet's part in one pool: the Kilojoin state machine, the Android app's
 * `coinjoin/PoolSession.kt` (1.5.2: Kilojoin v6, and the 6-confirmation rules after signing) step for step,
 * on coroutines instead of threads.
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
        /**
         * Confirmations of the transaction that spends [coin], when that is not [roundTxid] (0 in the mempool);
         * null when the coin is unspent, spent by the round itself, or the chain could not tell. Null never frees a round.
         */
        fun conflictDepth(coin: PoolTx.Coin, roundTxid: String): Int? = null
        fun save(state: State)
        fun event(e: Event)
        fun now(): Long = NostrEvent.nowSeconds()
        fun log(msg: String) {}
        /**
         * The token hashes of every seat this wallet holds in pool [poolId] (this one included, once
         * welcomed). They are one person: every decision on how many people a round has counts them
         * as one ([people]). Nothing of it goes on the wire; other wallets count seats as before.
         */
        fun ownSeats(poolId: String): Set<String> = emptySet()
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
        /** When the round went out, and when it was over (confirmed, cancelled or refused): what HISTORY shows (Android 1.4.0). */
        var sentAt: Long = 0,
        var endedAt: Long = 0,
        /**
         * Another seat of this phone in the same pool: a short random id of its own ("" for the first
         * seat, and for every round saved before seats could be more than one, so they keep their key).
         */
        val seatId: String = "",
        /** Not before this time (epoch seconds) is the join sent, or the pool opened: a random wait. */
        val joinAfter: Long = 0,
        // ---- v6 (asynchronous rounds); all 0/empty before v6.
        /** The roster number the current vote is over. */
        var voteSeq: Int = 0,
        /** The number of the newest vote applied (creator and member alike); an older vote_open, reopen or closing is stale. */
        var voteN: Int = 0,
        /** The number of the newest vote that ended without a round, or whose round was called off. */
        var voteEndedN: Int = 0,
        /** Member: the vote whose answer the creator acknowledged recording (`vote_ack`, or our id in a snapshot). */
        var voteAcked: String? = null,
        /** Creator: votes it numbered so far, and their ids, so a replayed request never opens one again. */
        var votesSent: Int = 0,
        val usedVotes: MutableSet<String> = linkedSetOf(),
        /** Creator: votes it opened again by itself after one timed out with silent seats. */
        var revotes: Int = 0,
        /** Creator: the newest message of each type that runs the pool, `{"at":…,"msg":…}`, replayed in snapshots. */
        val lastMsgs: MutableMap<String, String> = linkedMapOf(),
        /** Snapshots: numbered by the creator; a member applies only a newer one. */
        var stateN: Int = 0,
        /** When this wallet had signed all its inputs (its coins are reserved from then until the round resolves). */
        var signedAt: Long = 0,
        /** Confirmations of the round's transaction at the last look (it counts as confirmed at [DEPTH]). */
        var confs: Int = 0,
    ) {
        val poolId: String get() = terms.id
        /** What this seat is kept under on the phone: the pool's id, or with [seatId] for another seat in it. */
        val key: String get() = keyOf(poolId, seatId)
        val joinProof: String get() = joinProofs.first()
        val coinPath: String get() = coinPaths.first()
        val mixScript: ByteArray get() = mixScripts.first()
        val done: Boolean get() = phase in setOf(Phase.CONFIRMED, Phase.ABORTED, Phase.REJECTED)

        fun toJson(): JsonObject = J.with(J.obj(
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
            "sent_at" to sentAt, "ended_at" to endedAt,
            "seat_id" to seatId, "join_after" to joinAfter,
        ), *(if (!terms.v6) emptyArray() else arrayOf<Pair<String, Any?>>(
            "vote_seq" to voteSeq, "vote_n" to voteN, "vote_ended_n" to voteEndedN, "vote_acked" to (voteAcked ?: ""),
            "votes_sent" to votesSent, "used_votes" to usedVotes.toList(), "revotes" to revotes, "last_msgs" to lastMsgs.toMap(),
            "state_n" to stateN, "signed_at" to signedAt)), "confs" to confs)

        companion object {
            private fun JsonObject.s(k: String): String? = optStr(k).ifEmpty { null }
            /** The key of seat [seatId] of pool [poolId]: the pool id alone for its first seat. */
            fun keyOf(poolId: String, seatId: String): String = if (seatId.isEmpty()) poolId else "$poolId#$seatId"
            /** The pool a seat's key belongs to. */
            fun poolOf(key: String): String = key.substringBefore('#')
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
                    o.optLong("sent_at"), o.optLong("ended_at"),
                    o.optStr("seat_id"), o.optLong("join_after"),
                    voteSeq = o.optInt("vote_seq"), voteN = o.optInt("vote_n"), voteEndedN = o.optInt("vote_ended_n"),
                    voteAcked = o.s("vote_acked"), votesSent = o.optInt("votes_sent"),
                    usedVotes = (o.strings("used_votes") ?: emptyList()).toMutableSet(),
                    revotes = o.optInt("revotes"), lastMsgs = o.map("last_msgs"), stateN = o.optInt("state_n"),
                    signedAt = o.optLong("signed_at"), confs = o.optInt("confs"),
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
    /** This seat's own Tor circuit (see [RelayClient]): the same across restarts, another for every seat. */
    private val isolation = "kj-" + Hashes.sha256(s.joinSecret.encodeToByteArray()).toHex().take(16)
    private val joinPub = NostrEvent.pubOf(joinKey)
    private val myTokenHash: String? get() = s.token?.let { Protocol.tokenHash(it) }
    /** True once the join went out (or the pool was opened): another seat waits [State.joinAfter] first. */
    private var joinSent = false
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

    // ---- v6, in memory only (the round itself is in [State]).
    /** A vote_open that came before the roster it is over (events out of order): applied once that roster is in. */
    private var pendingVoteOpen: Pair<JsonObject, Long>? = null
    private var lastVoteSent = 0L
    private var lastSync = 0L
    private var lastStatePub = 0L
    private var lastResolve = 0L
    private var lastSigSent = 0L

    /** v5: the creator's seat, the one it joined with the pool's own key. */
    private fun isCreatorSeat(seat: Protocol.Seat) = seat.joinPub == terms.poolPub

    /** Seats whose equal outputs are handed in blindly: all of them, but the creator's in v5. */
    private val handingIn: Int get() = if (terms.v5) s.round.count { !isCreatorSeat(it) } else s.round.size

    val state: State get() = s
    val done: Boolean get() = s.done

    /** This wallet's seats in the pool, by token hash: [Env.ownSeats] and our own. */
    private fun ownSeats(): Set<String> = env.ownSeats(s.poolId) + listOfNotNull(myTokenHash)

    /** The people among [seats]: our own seats in them count as one (see [distinctPeople]). */
    private fun people(seats: Collection<Protocol.Seat>): Int = distinctPeople(seats.map { it.tokenHash }, ownSeats())

    /** People in the pool now, as this wallet sees them (its own seats as one). */
    fun people(): Int = people(if (s.round.isNotEmpty() && s.phase !in setOf(Phase.OPEN, Phase.VOTING)) s.round else s.seats)
    val running: Boolean get() = ticker?.isActive == true

    /** The plan once every output is in; null before. */
    fun plan(): PoolTx.Plan? = runCatching { buildPlan() }.getOrNull()

    /** Why the plan does not pay us right (null when it does, or when there is no plan yet). */
    fun planProblem(): String? = plan()?.let { checkPlan(it) }

    /** The round's transaction id: sent, or the one being signed. */
    fun plannedTxid(): String? = s.txid ?: if (s.phase == Phase.SIGNING) plan()?.let { PoolTx.txid(it) } else null

    // ------------------------------------------------------------------------------- lifecycle

    fun start() {
        if (done || running) return
        val ch = Channel<NostrEvent>(Channel.UNLIMITED)
        events = ch
        ticker = scope.launch {
            launch { for (ev in ch) lock.withLock { runCatching { onEvent(ev) }.onFailure { env.log("event: ${it.message}") } } }
            lock.withLock { runCatching { connect() }.onFailure { env.log("connect: ${it.message}") } }
            delay(minOf(5L, TICK_SECONDS) * 1000)
            while (isActive) {
                lock.withLock { tick() }
                if (done && s.phase != Phase.BROADCAST) break
                delay(TICK_SECONDS * 1000)
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
        val r = RelayClient(Protocol.RELAY, sockets, { _, ev -> ch?.trySend(ev) }, { relay = null }, isolation)
        r.connect()
        relay = r
        r.subscribe("in", listOf(filter(listOf(Protocol.KIND_MSG), p = joinPub)))
        r.subscribe("pool", listOf(filter(listOf(Protocol.KIND_MSG), p = terms.poolPub)))
        // The pool's public listing, re-announced by its creator while it is alive.
        if (!s.creator) r.subscribe("listing", listOf(filter(listOf(Protocol.KIND_POOL), authors = terms.poolPub, d = terms.id)))
        // v3: outputs come to the creator's own key, each from a fresh key.
        if (s.creator && terms.v3) r.subscribe("outs", listOf(filter(listOf(Protocol.KIND_MSG), p = terms.creatorPub)))
        // Another seat of this phone waits its random moment first (see [State.joinAfter]).
        if (s.phase == Phase.JOINING && env.now() >= s.joinAfter) { if (s.creator) creatorStart() else sendJoin() }
        // v6: back online, ask the creator where the round stands (its answer goes through the usual checks).
        if (terms.v6 && !s.creator && s.token != null && !done) scope.launch { lock.withLock { runCatching { sendSync() } } }
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

    private fun save() {
        // The times HISTORY shows: when it went out, and when it was over (Android 1.4.0).
        if (s.phase in setOf(Phase.BROADCAST, Phase.CONFIRMED) && s.sentAt == 0L) s.sentAt = env.now()
        if (s.phase in setOf(Phase.CONFIRMED, Phase.ABORTED, Phase.REJECTED) && s.endedAt == 0L) s.endedAt = env.now()
        env.save(s); env.event(Event.Changed(s.poolId))
    }

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
        // v6: the terms this wallet joins under, its windows and final time among them.
        if (terms.v6) kv += "terms" to Protocol.termsHash(terms)
        joinSent = true
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
                onOutputHandedIn(J.parse(msg), ev.pubkey)
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
                // v6: welcomed under the terms we joined for, or not at all.
                if (terms.v6 && o.optStr("terms") != Protocol.termsHash(terms)) {
                    s.phase = Phase.REJECTED; s.reason = "the pool's terms are not the ones it announced"
                    save(); env.event(Event.Rejected(s.poolId, s.reason)); return
                }
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
        joinSent = true
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
        return J.obj("type" to "roster", "seq" to s.rosterSeq, "seats" to Protocol.seatsJson(s.seats)).also { keepLast(it) }
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
        if (terms.v6 && o.optStr("terms") != Protocol.termsHash(terms)) return reject(from, "the pool's terms are not the ones you joined for")
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
            "seq" to s.rosterSeq, "terms" to (if (terms.v6) Protocol.termsHash(terms) else null)), replyKey!!)
        creatorChannel(roster)
        announce()
        // A coin taking its seat back is nobody new.
        if (held.isEmpty()) env.event(Event.Joined(s.poolId, s.seats.size))
        // Full: ask everyone whether to close now (the creator's own seat counts as a yes).
        // Not when the seats are only this wallet's own and too few others: that vote could only fail.
        if (s.seats.size >= terms.maxPeers && people(s.seats) >= terms.minPeers) {
            if (terms.v6) sendVoteOpen(myTokenHash!!, randomHex(8))
            else if (terms.v3) creatorChannel(J.obj("type" to "vote_open", "by" to myTokenHash, "vote_id" to randomHex(8)))
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
        // v6: a vote is over the roster it was opened on; another roster ends it, never closes it with fewer.
        if (terms.v6) { if (s.phase == Phase.VOTING) sendReopen(if (why == "left") "withdrawn" else "roster_changed"); return true }
        if (s.phase == Phase.VOTING && people(s.seats) < terms.minPeers)
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
        // Never a round of fewer people than the pool's minimum: this wallet's own seats are one person,
        // however many of them said yes (a round with itself mixes nothing).
        if (people(seats) < terms.minPeers) {
            env.log("not closing: ${seats.size} seats but only ${people(seats)} people")
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
        // v6: only the whole roster the vote was over, every seat of it having said yes.
        if (terms.v6 && (seats.map { it.tokenHash }.toSet() != s.seats.map { it.tokenHash }.toSet() || s.voteSeq != s.rosterSeq)) {
            env.log("not closing: the round is not the roster that was voted"); s.closedVote = null; return
        }
        s.round = seats.toMutableList()
        // v3: a fresh round id; blind signatures and outputs count for this round only.
        // v6: a seat's blind signatures are given once for the pool's whole life (a round called off and voted again
        // gets the same outputs back, never another set).
        if (terms.v3) { s.roundId = randomHex(8); if (!terms.v6) { s.blindSigs.clear(); s.blindedOf.clear() }; s.regOutputs.clear(); save() }
        val closing = J.obj("type" to "closing", "why" to why, "seats" to Protocol.seatsJson(seats),
            "deadline" to (if (terms.v6) capped(env.now() + terms.outputsSeconds) else env.now() + Protocol.OUTPUTS_SECONDS),
            "round_id" to (if (terms.v3) s.roundId else null),
            "vote_id" to (if (terms.v6) s.voteId else null), "n" to (if (terms.v6) s.voteN else null), "seq" to (if (terms.v6) s.voteSeq else null))
        val sent = creatorChannel(closing)
        if (sent && terms.v6) keepLast(closing)
        // Not taken by the relay: the next yes or look at the deadline tries again.
        if (!sent) s.closedVote = null
        save()
    }

    private suspend fun creatorOnVote(tokenHash: String, voteId: String, accept: Boolean, at: Long = 0) {
        if (terms.v6) return creatorOnVoteV6(tokenHash, voteId, accept, at)
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
                // v6: a round called off goes back to the pool, so a member still collecting outputs takes the new roster.
                if (!fromCreator || s.phase !in setOf(Phase.OPEN, Phase.VOTING) && !(terms.v6 && s.phase == Phase.CLOSING)) return
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
                // v6: a vote over this roster that came first.
                pendingVoteOpen?.let { (vo, vat) -> if (vo.optInt("seq") <= s.rosterSeq) { pendingVoteOpen = null; openVoteV6(vo, vat) } }
            }
            "close_request" -> {
                if (v3) {
                    if (!s.creator || s.phase != Phase.OPEN || people(s.seats) < terms.minPeers) return
                    val th = o.str("token_hash"); val id = o.str("vote_id")
                    if (s.seats.none { it.tokenHash == th } || !Protocol.macOk(s.tokens[th], o.str("mac"), "close", id)) return
                    // v6: a request replayed from the relay (after a restart) never opens its vote again.
                    if (terms.v6) { if (id !in s.usedVotes) sendVoteOpen(th, id); return }
                    creatorChannel(J.obj("type" to "vote_open", "by" to th, "vote_id" to id))
                    return
                }
                openVote(Protocol.tokenHash(o.str("token")), o.str("vote_id"), at)
            }
            "vote_open" -> if (terms.v6) { if (fromCreator) openVoteV6(o, at) } else if (v3 && fromCreator) openVote(o.str("by"), o.str("vote_id"), at)
            "vote" -> if (s.creator) {
                if (v3) {
                    val th = o.str("token_hash"); val id = o.str("vote_id"); val yes = o.bool("accept")
                    if (!Protocol.macOk(s.tokens[th], o.str("mac"), "vote", id, yes)) return
                    creatorOnVote(th, id, yes, at)
                } else creatorOnVote(Protocol.tokenHash(o.str("token")), o.str("vote_id"), o.bool("accept"))
            }
            "vote_ack" -> if (terms.v6 && fromCreator && !s.creator) onVoteAck(o)
            "state" -> if (terms.v6 && fromCreator && !s.creator) applyState(o)
            "sync" -> if (terms.v6 && s.creator) onSync(o)
            "reopen" -> {
                if (terms.v6) { if (fromCreator) onReopenV6(o); return }
                if (!fromCreator || s.phase != Phase.VOTING || o.optStr("vote_id") != s.voteId) return
                s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear(); save()
                env.event(Event.CloseRefused(s.poolId))
            }
            "closing" -> {
                // v3: the round closed again ("again": n) without seats that never asked for their blind
                // signatures; only a member still collecting outputs takes it, and only a newer one (replays).
                val again = o.optInt("again", 0)
                if (!fromCreator) return
                // v6: only the round of the vote under way, over the roster it was voted on, and only if we said yes to it
                // here: a closing we have no yes of our own for (history lost, or a creator that lies) never leads to signing.
                if (terms.v6 && !closingOkV6(o)) return
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
                // Our own seats count as one person here: a round of us and one other is not a round of three.
                if (people(seats) < terms.minPeers) {
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
                if (s.creator) publishState(force = true)
                env.event(Event.Closing(s.poolId, people(seats)))
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
                val fresh = s.sigs[outpoint] != sig
                s.sigs[outpoint] = sig; save()
                if (fresh) publishState()
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
                // v6: once we signed, nobody's word ends the round: a peer may still hold every signature.
                if (terms.v6 && signedAll()) { s.reason = o.optStr("reason", "aborted"); save(); return }
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
        // The creator does not call a vote that could only close a round of too few people (its own
        // seats are one): v1/v2 members, who heard the request too, are told to go on waiting.
        if (s.creator && people(s.seats) < terms.minPeers) {
            if (!terms.v3) channel(J.obj("type" to "reopen", "vote_id" to voteId))
            return
        }
        s.phase = Phase.VOTING; s.voteId = voteId; s.voteBy = th
        s.voteDeadline = at + Protocol.VOTE_SECONDS
        s.accepts.clear(); s.accepts.add(th)
        save()
        env.event(Event.CloseRequested(s.poolId, people(s.seats), th == myTokenHash))
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
    private suspend fun onOutputHandedIn(o: JsonObject, from: String) {
        if (o.optStr("type") != "output" || s.phase != Phase.CLOSING || o.optStr("round_id") != s.roundId) {
            if (terms.v6) env.log("output handed in for another round or stage: ignored")
            return
        }
        val script = o.str("script")
        if (!scriptOk(script)) return
        val prefix = Hashes.hexToBytes(o.str("prefix"))
        if (prefix.size != BlindRsa.PREFIX_LEN) return
        val pk = blindPk ?: return
        if (!BlindRsa.verify(pk, prefix + Hashes.hexToBytes(script), Hashes.hexToBytes(o.str("sig")))) return
        s.regOutputs.add(script); save()
        // v6: kept on disk first, then acknowledged to the output's own fresh key (nothing in it points at a seat).
        if (terms.v6) creatorKey?.let { direct(from, J.obj("type" to "output_ack", "round_id" to s.roundId, "script" to script), it) }
        listOutputsIfAll()
    }

    /** v3 creator: once every seat of the round has its outputs in, list them all at once. */
    private suspend fun listOutputsIfAll() {
        if (s.phase != Phase.CLOSING) return
        val want = handingIn * perPerson
        if (s.regOutputs.size > want) return abort("more equal outputs than people")
        if (s.regOutputs.size < want) return
        val pays = if (terms.v5) s.mixScripts.indices.map { i -> J.obj("script" to s.mixScripts[i].toHex(), "value" to terms.payments[i]) } else null
        val o = J.obj("type" to "outputs", "round_id" to s.roundId, "scripts" to s.regOutputs.sorted(), "payments" to pays)
        if (creatorChannel(o) && terms.v6) keepLast(o)
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
                if (terms.v6) { handInV6(i); return@launch }
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
        val r = RelayClient(Protocol.RELAY, sockets, { _, _ -> }, {}, "kj-out-" + secureRandomBytes(8).toHex())
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
        s.phase = Phase.SIGNING; s.phaseDeadline = if (terms.v6) capped(env.now() + terms.signSeconds) else env.now() + Protocol.SIGN_SECONDS; save()
        publishState(force = true)
        env.event(Event.SignNeeded(s.poolId, plan.coins.size))
    }

    /** The user checked and approved: sign each of our inputs, [privateKeys] in the order of [State.coinPaths]. */
    suspend fun sign(privateKeys: List<ByteArray>): Unit = lock.withLock {
        check(s.phase == Phase.SIGNING) { "nothing to sign right now" }
        require(privateKeys.size == s.seat.coins.size) { "one key per coin is needed" }
        val plan = buildPlan() ?: error("the transaction is not complete")
        checkPlan(plan)?.let { error(it) }
        if (terms.v6) {
            // Kept before anything goes out: from here on our coins are reserved until the round resolves.
            s.seat.coins.forEachIndexed { i, c ->
                val mine = plan.coins.first { it.outpoint == c.outpoint }
                s.sigs[mine.outpoint] = PoolTx.sign(plan, mine, privateKeys[i]).toHex()
            }
            s.signedAt = env.now(); save()
            // The relay's OK is the receipt; what it did not take goes again from the timer (and on a snapshot without it).
            postOwnSigs()
            maybeBroadcast(plan)
            return@withLock
        }
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
            if (!msg.contains("already", true)) {
                if (terms.v6) return abort("broadcast failed: $msg")
                // Before v6 too, every signature is here: a failure (a server out of reach, a spend of one of the coins
                // still in the mempool or a block or two deep) frees nothing. It goes again until it is seen, or a coin
                // of it is spent elsewhere [DEPTH] blocks deep (see [tickBroadcast]).
                s.txid = tx.txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); s.reason = "$NOT_SENT: $msg"; save()
                env.log("broadcast failed, it goes again: $msg")
                return
            }
        }
        sent(tx.txid)
    }

    /** The round's transaction went out from here: everyone is told. */
    private suspend fun sent(txid: String) {
        s.txid = txid; s.phase = Phase.BROADCAST; s.phaseDeadline = env.now(); if (s.reason.startsWith(NOT_SENT)) s.reason = ""; save()
        channel(J.obj("type" to "tx", "txid" to txid))
        announce()
        publishState(force = true)
        env.event(Event.Broadcast(s.poolId, txid))
    }

    private suspend fun abort(reason: String) {
        if (done) return
        // v6: after signing, no deadline or failure frees our coins: only the round resolving does (see [resolveSigned]).
        if (terms.v6 && signedAll()) { s.reason = reason; save(); if (s.creator) publishState(force = true); return }
        s.phase = Phase.ABORTED; s.reason = reason; save()
        // v3: the creator's from its own key, which every v3 wallet takes as the creator's.
        if (s.creator && terms.v3) creatorChannel(J.obj("type" to "abort", "reason" to reason).also { keepLast(it) })
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
        if (rest.size < Protocol.MIN_PEERS || people(rest) < terms.minPeers) return false
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
        env.event(Event.Closing(s.poolId, people(rest)))
        listOutputsIfAll()
        return true
    }

    // ------------------------------------------------------------------------------- user actions

    /** Ask everyone to close the pool now (enough people in). */
    suspend fun requestClose(): Unit = lock.withLock {
        check(s.phase == Phase.OPEN) { "the pool is not open" }
        check(s.seats.size >= terms.minPeers) { "at least ${terms.minPeers} people are needed" }
        check(people(s.seats) >= terms.minPeers) { OWN_SEATS_ONE.replace("%d", terms.minPeers.toString()) }
        val id = randomHex(8)
        when {
            !terms.v3 -> channel(J.obj("type" to "close_request", "token" to s.token, "vote_id" to id))
            s.creator && terms.v6 -> { sendVoteOpen(myTokenHash!!, id); true }
            s.creator -> creatorChannel(J.obj("type" to "vote_open", "by" to myTokenHash, "vote_id" to id))
            else -> channel(J.obj("type" to "close_request", "token_hash" to myTokenHash, "vote_id" to id,
                "mac" to Protocol.mac(s.token ?: error("no seat"), "close", id)))
        }.let { check(it) { "the relay did not take the request" } }
    }

    suspend fun vote(accept: Boolean): Unit = lock.withLock {
        val id = s.voteId ?: return@withLock
        if (s.phase != Phase.VOTING || s.votedOn == id) return@withLock
        // A yes to a round whose other seats are only ours: not a round with anyone.
        if (accept) check(people(s.seats) >= terms.minPeers) { OWN_SEATS_ONE.replace("%d", terms.minPeers.toString()) }
        if (terms.v3) channel(J.obj("type" to "vote", "token_hash" to myTokenHash, "vote_id" to id, "accept" to accept,
            "mac" to Protocol.mac(s.token ?: return@withLock, "vote", id, accept)))
        else channel(J.obj("type" to "vote", "token" to s.token, "vote_id" to id, "accept" to accept))
        s.votedOn = id
        lastVoteSent = env.now()
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
            // The random wait of another seat is over: join, or open the pool.
            if (s.phase == Phase.JOINING && !joinSent && now >= s.joinAfter && now <= terms.expiresAt) {
                if (s.creator) creatorStart() else sendJoin()
                return@runCatching
            }
            // v6: its own stages (the open one is as before).
            if (terms.v6 && s.phase in setOf(Phase.VOTING, Phase.CLOSING, Phase.SIGNING, Phase.BROADCAST)) { tickV6(now); return@runCatching }
            when (s.phase) {
                Phase.OPEN, Phase.JOINING -> if (s.phase == Phase.OPEN && !s.warnedExpiry && now <= terms.expiresAt &&
                    terms.expiresAt - now <= EXPIRY_WARN_SECONDS && people(s.seats) >= terms.minPeers && s.seats.size < terms.maxPeers) {
                    s.warnedExpiry = true; save()
                    env.event(Event.ExpiringSoon(s.poolId, people(s.seats), ((terms.expiresAt - now) / 60).toInt().coerceAtLeast(1)))
                } else if (terms.v6 && !s.creator && s.phase == Phase.OPEN && now > terms.expiresAt) {
                    // v6: the pool lives until `final`, not `timeout` (PROTOCOL §14). Open again after a vote that
                    // timed out, the creator's new vote may come a moment after its reopen: a member waits for it, and
                    // for the creator's abort if there is none (Android 1.5.2). A creator that is gone stops re-announcing.
                    if (now > terms.async!!.final) localEnd("the pool reached its final time")
                } else if (now > terms.expiresAt) {
                    // The creator itself (v6 too): past `timeout` a pool open again only ever gets a new vote from the
                    // creator right at its reopen (roundFits), so one still open has nothing left to wait for.
                    if (s.creator) abort("the pool expired") else { s.phase = Phase.ABORTED; s.reason = "the pool expired"; save(); env.event(Event.Aborted(s.poolId, s.reason)) }
                } else if (s.phase == Phase.JOINING && !s.creator && now - maxOf(s.created, s.joinAfter) > JOIN_TIMEOUT) {
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
                    // Our own seats saying yes are one person, however many they are.
                    if (people(yes) >= terms.minPeers) callClosing(yes, "agreed")
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
                // As v6: confirmed [DEPTH] deep, freed only by a conflicting spend [DEPTH] deep, sent again meanwhile.
                Phase.BROADCAST -> tickBroadcast(now)
                else -> {}
            }
        }.onFailure { env.log("tick: ${it.message}") }
    }

    /** The round is over here: no more relay (the session's loop ends with it). */
    private fun finish() { relay?.close(); relay = null }

    // ------------------------------------------------------------------------------- v6: asynchronous rounds

    /** v6: a deadline no later than the pool's final time. */
    private fun capped(t: Long): Long = minOf(t, terms.async?.final ?: t)

    /** v6: a whole round (a vote, the outputs, the signing, each its full window) still fits before `final` from [now]. */
    private fun roundFits(now: Long): Boolean = now + terms.voteSeconds + terms.outputsSeconds + terms.signSeconds <= terms.async!!.final

    /** v6: every one of our inputs is signed: our coins are reserved until the round resolves. */
    private fun signedAll(): Boolean = s.seat.coins.isNotEmpty() && s.seat.coins.all { it.outpoint in s.sigs }

    /** Our coins held by a v6 round we signed whose time is over: the transaction may still go out. */
    val reserved: Boolean get() = terms.v6 && s.phase == Phase.SIGNING && signedAll() && env.now() > s.phaseDeadline

    /** Every one of our inputs is signed (v6: kept before it is sent, so this can be true while the relay has not taken it yet). */
    val signed: Boolean get() = signedAll()

    /** v6 creator: the newest message of its type, for the snapshot. */
    private fun keepLast(o: JsonObject) {
        if (!terms.v6 || !s.creator) return
        s.lastMsgs[o.str("type")] = J.obj("at" to env.now(), "msg" to o).toString()
    }

    /** v6 creator: opens vote [id] asked by seat [by], numbered, over the current roster, and applies it here at once. */
    private suspend fun sendVoteOpen(by: String, id: String) {
        if (id in s.usedVotes) return
        val n = ++s.votesSent
        s.usedVotes += id
        val o = J.obj("type" to "vote_open", "by" to by, "vote_id" to id, "n" to n, "seq" to s.rosterSeq,
            "deadline" to capped(env.now() + terms.voteSeconds))
        save()
        if (!creatorChannel(o)) { env.log("vote_open not taken by the relay"); return }
        keepLast(o)
        openVoteV6(o, env.now())
        publishState(force = true)
    }

    /**
     * v6: a vote over roster `seq`, numbered `n`. Votes are applied in their number's order whatever order the
     * relay gives them in; one that comes before its roster waits for it; one over an older roster is stale.
     */
    private suspend fun openVoteV6(o: JsonObject, at: Long) {
        val n = o.optInt("n"); val seq = o.optInt("seq"); val id = o.str("vote_id"); val th = o.str("by")
        if (n <= s.voteN || n <= s.voteEndedN) return
        if (s.phase !in setOf(Phase.OPEN, Phase.VOTING, Phase.CLOSING)) return
        if (!s.creator && seq > s.rosterSeq) { pendingVoteOpen = o to at; return }
        if (seq != s.rosterSeq) return
        if (s.seats.none { it.tokenHash == th } || s.seats.size < terms.minPeers) return
        if (s.creator && people(s.seats) < terms.minPeers) return
        // A round called off for a new vote: nothing of it was signed. Our blind signatures stay (one set per seat).
        if (s.phase == Phase.CLOSING) { if (signedAll()) return; clearRound() }
        val wasVoting = s.phase == Phase.VOTING
        s.phase = Phase.VOTING; s.voteId = id; s.voteBy = th; s.voteN = n; s.voteSeq = seq
        s.voteDeadline = minOf(o.optLong("deadline", env.now() + terms.voteSeconds), env.now() + terms.voteSeconds, terms.async!!.final)
        s.accepts.clear(); s.accepts.add(th)
        // Whoever asked has said yes: the creator's vote_open is its record of that.
        if (th == myTokenHash) { s.votedOn = id; s.voteAcked = id }
        save()
        if (wasVoting) env.log("vote $n replaces the one before")
        env.event(Event.CloseRequested(s.poolId, people(s.seats), th == myTokenHash))
        if (s.creator && s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
    }

    /** v6 creator: ends the vote under way (or its round, before anyone signed) with an explicit [outcome]; applied here at once. */
    private suspend fun sendReopen(outcome: String) {
        val id = s.voteId ?: return
        val o = J.obj("type" to "reopen", "vote_id" to id, "n" to s.voteN, "outcome" to outcome,
            "round_id" to (if (s.phase == Phase.CLOSING) s.roundId else null))
        if (!creatorChannel(o)) env.log("reopen not taken by the relay")
        keepLast(o)
        onReopenV6(o)
        publishState(force = true)
    }

    private fun onReopenV6(o: JsonObject) {
        val n = o.optInt("n")
        if (n < s.voteN) return
        if (n > s.voteEndedN) { s.voteEndedN = n; save() }
        if (o.optStr("vote_id") != s.voteId) return
        val ok = s.phase == Phase.VOTING || (s.phase == Phase.CLOSING && o.optStr("round_id") == s.roundId && !signedAll())
        if (!ok) return
        if (s.phase == Phase.CLOSING) clearRound()
        s.phase = Phase.OPEN; s.voteId = null; s.accepts.clear()
        s.reason = when (o.optStr("outcome")) {
            "declined" -> "someone said not yet"
            "withdrawn" -> "someone left the pool"
            "timeout" -> "not everyone answered in time"
            "outputs_timeout" -> "not everyone handed in their outputs in time"
            "roster_changed" -> "the people in the pool changed"
            else -> ""
        }
        save()
        env.event(Event.CloseRefused(s.poolId))
    }

    /** Back from a round called off before anyone signed: the pool's state, ready for another vote. Our blinding stays. */
    private fun clearRound() {
        s.round.clear(); s.outputs.clear(); s.handedIn.clear(); s.postedOutput = false; s.regOutputs.clear()
        s.payOutputs.clear(); s.roundId = null; s.phaseDeadline = 0; s.closedVote = null
        outputScheduled.clear()
    }

    /** v6: a closing is taken only for the vote under way, over its roster as voted, with our own yes on record. */
    private fun closingOkV6(o: JsonObject): Boolean {
        val n = o.optInt("n"); val id = o.optStr("vote_id")
        if (s.phase != Phase.VOTING || n != s.voteN || n <= s.voteEndedN || id != s.voteId || o.optInt("seq") != s.voteSeq) return false
        if (s.rosterSeq != s.voteSeq) return false
        val seats = runCatching { Protocol.parseSeats(o.arr("seats"), terms.script) }.getOrNull() ?: return false
        if (seats.map { it.tokenHash }.toSet() != s.seats.map { it.tokenHash }.toSet()) {
            env.log("a closing that is not the roster voted: ignored"); return false
        }
        if (!s.creator && s.votedOn != id) { env.log("a closing for a vote we never said yes to: ignored"); return false }
        return true
    }

    /** v6 creator: one answer per seat and vote, kept on disk before it is acknowledged; a repeat is acknowledged again, never counted. */
    private suspend fun creatorOnVoteV6(th: String, voteId: String, accept: Boolean, at: Long) {
        suspend fun ack(status: String) {
            // A replay of old votes (after a restart) is not answered; every vote of now is, a repeat too: a member
            // repeats its vote only while it has no acknowledgment, every [RESEND_SECONDS].
            if (status != "recorded" && at > 0 && env.now() - at > 600) return
            creatorChannel(J.obj("type" to "vote_ack", "vote_id" to voteId, "token_hash" to th, "accept" to accept,
                "status" to status, "n" to (if (voteId == s.voteId) s.voteN else 0),
                "yes" to s.accepts.count { a -> s.seats.any { it.tokenHash == a } }, "seats" to s.seats.size))
        }
        if (s.seats.none { it.tokenHash == th }) return ack("not_in_roster")
        if (voteId != s.voteId) return ack("superseded")
        if (s.phase != Phase.VOTING) return ack(if (s.closedVote == voteId) "closed" else "superseded")
        if (!accept) {
            // A no is that seat leaving: recorded, acknowledged, and the vote ends with it (no smaller round from it).
            if (th != myTokenHash) {
                s.seats.removeAll { it.tokenHash == th }; s.accepts.remove(th); save()
                ack("recorded")
                creatorChannel(roster()); announce()
            } else ack("recorded")
            sendReopen("declined")
            return
        }
        if (th in s.accepts) return ack("recorded")
        s.accepts.add(th); save()
        ack("recorded")
        if (s.seats.all { it.tokenHash in s.accepts }) callClosing(s.seats.toList(), "agreed")
        else publishState()
    }

    /** v6 member: the creator recorded (or not) our answer. */
    private fun onVoteAck(o: JsonObject) {
        if (o.optStr("token_hash") != myTokenHash || o.optStr("vote_id") != s.votedOn) return
        if (o.optStr("status") == "recorded" && s.voteAcked != s.votedOn) {
            s.voteAcked = s.votedOn; save()
            env.log("our vote is recorded (${o.optInt("yes")}/${o.optInt("seats")} yes)")
        }
    }

    /** v6 member: asks the creator for a snapshot (after reconnecting, or when a stage seems stuck). */
    private suspend fun sendSync() {
        val th = myTokenHash ?: return; val token = s.token ?: return
        val now = env.now()
        if (now - lastSync < 20) return
        lastSync = now
        channel(J.obj("type" to "sync", "token_hash" to th, "at" to now, "mac" to Protocol.mac(token, "sync", now)))
    }

    /** v6 creator: a member asks where the round stands. Only a seat's fresh request is answered (a replay is not). */
    private suspend fun onSync(o: JsonObject) {
        val th = o.optStr("token_hash"); val at = o.optLong("at")
        if (!Protocol.macOk(s.tokens[th], o.optStr("mac"), "sync", at)) return
        if (kotlin.math.abs(env.now() - at) > 600) return
        publishState()
    }

    /**
     * v6 creator: the snapshot. Where the pool stands, with counts only for the outputs (never which seat handed
     * in what), and the newest message of each type that runs it, for a member that lost them to replay through
     * its usual checks. Rate-limited unless [force].
     */
    private suspend fun publishState(force: Boolean = false) {
        if (!terms.v6 || !s.creator) return
        val now = env.now()
        if (!force && now - lastStatePub < 10) return
        lastStatePub = now
        val kv = mutableListOf<Pair<String, Any?>>("type" to "state", "n" to ++s.stateN, "at" to now,
            "phase" to s.phase.name.lowercase(), "final" to terms.async!!.final, "roster_seq" to s.rosterSeq, "reason" to s.reason)
        if (s.phase == Phase.VOTING) kv += "vote" to J.obj("vote_id" to s.voteId, "n" to s.voteN, "seq" to s.voteSeq,
            "deadline" to s.voteDeadline, "seats" to s.seats.size,
            "accepted" to s.accepts.filter { a -> s.seats.any { it.tokenHash == a } })
        if (s.phase == Phase.CLOSING) kv += "round" to J.obj("round_id" to s.roundId, "deadline" to s.phaseDeadline,
            "seats" to s.round.size, "blind_given" to s.round.count { it.tokenHash in s.blindSigs },
            "outputs_in" to s.regOutputs.size, "outputs_wanted" to handingIn * perPerson)
        if (s.phase in setOf(Phase.SIGNING, Phase.BROADCAST, Phase.CONFIRMED))
            kv += "sign" to J.obj("deadline" to s.phaseDeadline, "sigs" to s.sigs.toMap())
        s.txid?.let { kv += "txid" to it }
        kv += "replay" to listOf("roster", "vote_open", "closing", "outputs", "reopen", "abort").mapNotNull { s.lastMsgs[it]?.let { j -> J.parse(j) } }
        save()
        creatorChannel(J.obj(*kv.toTypedArray()))
    }

    /** v6 member: a snapshot from the creator, newer than the last one: everything in it goes through the usual handlers. */
    private suspend fun applyState(o: JsonObject) {
        val n = o.optInt("n")
        if (n <= s.stateN) return
        s.stateN = n; save()
        o.optArr("replay")?.forEach { e ->
            runCatching { val x = e.jsonObject; onChannel(x.obj("msg"), x.optLong("at"), fromCreator = true) }
        }
        o.optObj("vote")?.let { v ->
            val acc = v.strings("accepted") ?: emptyList()
            if (v.optStr("vote_id") == s.votedOn && myTokenHash in acc && s.voteAcked != s.votedOn) { s.voteAcked = s.votedOn; save() }
        }
        o.optObj("sign")?.optObj("sigs")?.let { sg ->
            for ((k, v) in sg) runCatching { onChannel(J.obj("type" to "sig", "outpoint" to k, "sig" to v.jsonPrimitive.content), 0, true) }
            // Ours missing from it: the relay lost it; it goes again.
            if (signedAll() && s.seat.coins.any { it.outpoint !in sg }) { s.postedSig = false; lastSigSent = 0 }
        }
        o.optStr("txid").takeIf { it.isNotEmpty() }?.let { runCatching { onChannel(J.obj("type" to "tx", "txid" to it), 0, true) } }
    }

    /** v6: our signatures on the channel; [State.postedSig] once the relay took every one. */
    private suspend fun postOwnSigs() {
        lastSigSent = env.now()
        var all = true
        for (c in s.seat.coins) {
            val sig = s.sigs[c.outpoint] ?: continue
            if (!channel(J.obj("type" to "sig", "outpoint" to c.outpoint, "sig" to sig))) all = false
        }
        s.postedSig = all; save()
    }

    /**
     * v6: our output [i] handed in as in v3 (a fresh key, a connection of its own), and kept going until the
     * creator acknowledges it to that fresh key: a resend is the same output again, which the creator counts once.
     */
    private suspend fun handInV6(i: Int) {
        // What to hand in, read under the session's lock; the wait for the acknowledgment happens outside it,
        // so the session goes on reading the channel meanwhile.
        val job = lock.withLock {
            if (s.phase != Phase.CLOSING || s.postedOutput || i in s.handedIn) { outputScheduled -= i; return }
            val sig = s.outSigs.getOrNull(i); val round = s.roundId; val prefix = s.blindPrefixes.getOrNull(i)
            if (sig == null || round == null || prefix == null) { outputScheduled -= i; return }
            listOf(round, s.mixScripts[i].toHex(), prefix, sig)
        }
        val ok = handInOutputV6(job[0], job[1], job[2], job[3])
        lock.withLock {
            outputScheduled -= i
            if (ok && s.phase == Phase.CLOSING && s.roundId == job[0] && i !in s.handedIn) {
                s.handedIn += i
                if (s.handedIn.size == s.mixScripts.size) s.postedOutput = true
                save()
            }
        }
    }

    private suspend fun handInOutputV6(round: String, script: String, prefix: String, sig: String): Boolean {
        val creator = terms.creatorPub!!
        val fresh = NostrEvent.newSecret()
        val freshPub = NostrEvent.pubOf(fresh)
        val o = J.obj("type" to "output", "round_id" to round, "script" to script, "prefix" to prefix, "sig" to sig)
        val conv = Nip44.conversationKey(fresh, Hashes.hexToBytes(creator))
        val ev = NostrEvent.sign(fresh, Protocol.KIND_MSG, listOf(listOf("p", creator)), Nip44.encrypt(o.toString(), conv))
        val acked = CompletableDeferred<Boolean>()
        val r = RelayClient(Protocol.RELAY, sockets, { _, e ->
            if (e.pubkey != creator || e.tag("p") != freshPub) return@RelayClient
            val m = Nip44.decrypt(e.content, conv)?.let { runCatching { J.parse(it) }.getOrNull() } ?: return@RelayClient
            if (m.optStr("type") == "output_ack" && m.optStr("round_id") == round && m.optStr("script") == script) acked.complete(true)
        }, {}, "kj-out-" + secureRandomBytes(8).toHex())
        return try {
            r.connect()
            r.subscribe("ack", listOf(filter(listOf(Protocol.KIND_MSG), p = freshPub, authors = creator)), waitEose = false)
            val res = r.publish(ev)
            env.log("hand in output -> ${res.first} ${res.second}")
            (res.first && (withTimeoutOrNull(OUTPUT_ACK_WAIT * 1000) { acked.await() } ?: false))
                .also { env.log(if (it) "output acknowledged" else "output not acknowledged yet") }
        } catch (e: Exception) { env.log("hand in output failed: ${e.message}"); false }
        finally { runCatching { r.close() } }
    }

    /**
     * v6, a round we signed past its time: it resolves only on the chain. The transaction seen (by anyone's
     * broadcast) is the round going on; a coin of it spent by anything else, [DEPTH] blocks deep, means it never
     * can, and frees ours. A spend less deep may still be replaced or reorged away: the round stays reserved, and
     * whoever holds every signature keeps sending it.
     */
    private suspend fun resolveSigned(now: Long) {
        if (now - lastResolve < RESOLVE_EVERY) return
        lastResolve = now
        val plan = buildPlan() ?: return
        val txid = PoolTx.txid(plan)
        val c = runCatching { env.confirmations(txid) }.getOrNull()
        if (c != null) {
            s.txid = txid; s.phase = if (c >= DEPTH) Phase.CONFIRMED else Phase.BROADCAST; if (s.sentAt == 0L) s.sentAt = now
            s.confs = c; s.phaseDeadline = now; save()
            env.event(Event.Broadcast(s.poolId, txid))
            if (c >= DEPTH) env.event(Event.Confirmed(s.poolId, txid))
            return
        }
        if (conflictBuried(plan, txid)) {
            s.phase = Phase.ABORTED
            s.reason = "a coin of the round was spent elsewhere: its transaction can never confirm, and your coins are free"
            save(); env.event(Event.Aborted(s.poolId, s.reason)); finish()
        } else if (s.sigs.size == plan.coins.size &&
            runCatching { env.broadcast(PoolTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) }).rawHex) }.isSuccess)
            sent(txid)
    }

    /**
     * A coin of the round spent by another transaction at least [DEPTH] blocks deep. Only then can the round
     * never confirm: a spend in the mempool, or in a block or two, may still be replaced, evicted or reorged away.
     */
    private fun conflictBuried(plan: PoolTx.Plan, txid: String): Boolean {
        val unspent = runCatching { env.coinsUnspent(plan.coins) }.getOrNull() ?: return false
        if (unspent.size != plan.coins.size) return false
        return plan.coins.indices.any { i ->
            unspent[i] == false && (runCatching { env.conflictDepth(plan.coins[i], txid) }.getOrNull() ?: -1) >= DEPTH
        }
    }

    private suspend fun tickV6(now: Long) {
        val final = terms.async!!.final
        when (s.phase) {
            Phase.VOTING -> when {
                now > final -> if (s.creator) abort("the pool reached its final time") else localEnd("the pool reached its final time")
                !s.creator -> {
                    // Our answer not recorded yet: the same answer again (the creator counts it once).
                    if (s.votedOn == s.voteId && s.voteAcked != s.voteId && now - lastVoteSent >= RESEND_SECONDS) resendVote()
                    if (now > s.voteDeadline + 60 && now - lastSync >= SYNC_EVERY) sendSync()
                }
                now > s.voteDeadline -> {
                    // Time over without every yes. Never a smaller round from it: the silent seats leave the roster,
                    // and the rest are asked again over the new one (each answers anew).
                    val silent = s.seats.filter { it.tokenHash !in s.accepts && it.tokenHash != myTokenHash }.map { it.tokenHash }.toSet()
                    val weSaidYes = myTokenHash in s.accepts
                    if (silent.isNotEmpty()) {
                        s.seats.removeAll { it.tokenHash in silent }; silent.forEach { s.accepts.remove(it) }; save()
                        creatorChannel(roster()); announce()
                    }
                    sendReopen("timeout")
                    if (weSaidYes && silent.isNotEmpty() && people(s.seats) >= terms.minPeers && s.revotes < MAX_REVOTES &&
                        roundFits(now)) {
                        s.revotes++; save()
                        sendVoteOpen(myTokenHash!!, randomHex(8))
                    }
                }
            }
            Phase.CLOSING -> when {
                now > final -> if (s.creator) abort("the pool reached its final time") else localEnd("the pool reached its final time")
                s.creator && now > s.phaseDeadline + RECLOSE_AFTER -> {
                    // Seats that never even asked for their blind signatures leave the roster and the rest vote again
                    // (never a smaller round without asking). Otherwise someone got signatures and handed nothing in.
                    val missing = s.round.filter { it.tokenHash != myTokenHash && !(terms.v5 && isCreatorSeat(it)) && it.tokenHash !in s.blindSigs }
                        .map { it.tokenHash }.toSet()
                    if (missing.isEmpty()) abort("not everyone sent their output in time")
                    else {
                        s.seats.removeAll { it.tokenHash in missing }; missing.forEach { s.accepts.remove(it) }; save()
                        creatorChannel(roster()); announce()
                        sendReopen("outputs_timeout")
                        if (people(s.seats) >= terms.minPeers && s.revotes < MAX_REVOTES && roundFits(now) &&
                            (!terms.v5 || s.seats.count { isCreatorSeat(it) } == 1)) {
                            s.revotes++; save(); sendVoteOpen(myTokenHash!!, randomHex(8))
                        }
                    }
                }
                s.creator -> listOutputsIfAll()
                // The creator calls a round off (or a new vote) shortly after the deadline; a member waits for that a while.
                now > s.phaseDeadline + MEMBER_GRACE -> localEnd("not everyone sent their output in time")
                else -> {
                    if (now > s.phaseDeadline && now - lastSync >= SYNC_EVERY) sendSync()
                    if (s.outSigs.isEmpty() && now - lastBlindSent > 30) sendBlind()
                    else if (s.outSigs.isNotEmpty() && !s.postedOutput) scheduleOutput()
                }
            }
            Phase.SIGNING -> {
                // Our signatures the relay did not take go again.
                if (signedAll() && !s.postedSig && now - lastSigSent >= RESEND_SECONDS) postOwnSigs()
                // Reserved: the same signatures again every day, and the creator's snapshot with all it holds, so a
                // relay that keeps events a week still has them for whoever comes back to complete the round.
                if (signedAll() && s.postedSig && now - lastSigSent >= REPOST_EVERY) postOwnSigs()
                if (s.creator && signedAll() && now - lastStatePub >= REPOST_EVERY) publishState(force = true)
                if (now > s.phaseDeadline + 60 || now > final) {
                    if (!signedAll()) {
                        // We never signed: nothing of ours can go out, the round is over for us.
                        if (s.creator) abort("not everyone signed in time") else localEnd("not everyone signed in time")
                    } else {
                        if (s.reason.isEmpty()) { s.reason = "signed, waiting: the others' signatures, or a coin moving"; save() }
                        resolveSigned(now)
                    }
                } else if (now - lastSync >= SYNC_EVERY && !s.creator && signedAll()) sendSync()
            }
            Phase.BROADCAST -> tickBroadcast(now)
            else -> {}
        }
    }

    /**
     * Every version, a round sent (or fully signed and not sent yet): it counts as confirmed only [DEPTH] blocks deep,
     * a block or two can still be reorged away. Not seen (never, or no more after a reorg): either it fell out of
     * mempools and blocks (it goes again, when we hold every signature) or a coin of it was spent elsewhere, [DEPTH]
     * deep (then it never confirms, and our coins are free). Time alone decides nothing (Android 1.5.1).
     */
    private suspend fun tickBroadcast(now: Long) {
        val txid = s.txid ?: return
        val look = runCatching { env.confirmations(txid) }
        val c = look.getOrNull()
        // The count shown: 0 again when the chain says it is gone (a reorg), as it was when the chain is out of reach.
        if (look.isSuccess && (c ?: 0) != s.confs) { s.confs = c ?: 0; save() }
        // Seen: it went out (from here or from another wallet), whatever our own first try said.
        if (c != null && s.reason.startsWith(NOT_SENT)) { s.reason = ""; save() }
        if (c != null && c >= DEPTH) { s.phase = Phase.CONFIRMED; save(); env.event(Event.Confirmed(s.poolId, txid)); finish() }
        else if (c == null && now - lastResolve >= RESOLVE_EVERY) {
            lastResolve = now
            val plan = buildPlan() ?: return
            if (conflictBuried(plan, txid)) {
                s.phase = Phase.ABORTED; s.reason = "the transaction never appeared: a coin of the round was spent elsewhere"
                save(); env.event(Event.Aborted(s.poolId, s.reason)); finish()
            } else if (s.sigs.size == plan.coins.size &&
                runCatching { env.broadcast(PoolTx.assemble(plan, s.sigs.mapValues { Hashes.hexToBytes(it.value) }).rawHex) }.isSuccess &&
                s.reason.startsWith(NOT_SENT)) sent(txid) // the first time it went out: everyone is told now
        }
    }

    /** v6 member: the round is over for us here (nothing signed); the others are not told (the creator calls it). */
    private fun localEnd(reason: String) {
        if (done || signedAll()) return
        s.phase = Phase.ABORTED; s.reason = reason; save(); env.event(Event.Aborted(s.poolId, reason))
    }

    /** v6 member: our yes again, the same message (the creator counts a seat once per vote). */
    private suspend fun resendVote() {
        val id = s.voteId ?: return
        lastVoteSent = env.now()
        channel(J.obj("type" to "vote", "token_hash" to myTokenHash, "vote_id" to id, "accept" to true,
            "mac" to Protocol.mac(s.token ?: return, "vote", id, true)))
    }

    private fun randomHex(n: Int) = secureRandomBytes(n).toHex()

    companion object {
        /** Reason of a round this wallet left on purpose: its card goes away by itself. */
        const val LEFT_BY_CHOICE = "you left this round"
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

        // v6 (asynchronous rounds); tests make them shorter.
        /** Seconds before an unacknowledged vote or signature is sent again. */
        var RESEND_SECONDS = 30L
        /** Seconds between a member's requests for a snapshot while a stage looks stuck. */
        var SYNC_EVERY = 120L
        /** Seconds a hand-in waits for the creator's acknowledgment before trying again later. */
        var OUTPUT_ACK_WAIT = 20L
        /** Seconds between looks at the chain for a signed round past its time. */
        var RESOLVE_EVERY = 120L
        /** Seconds past the outputs' deadline a member waits for the creator to call the round off or vote again. */
        var MEMBER_GRACE = 600L
        /** New votes a creator opens by itself after one timed out with silent seats. */
        const val MAX_REVOTES = 5
        /**
         * Blocks deep the round's transaction is before the round counts as confirmed, and another spend of one
         * of its coins before the round is given up (and the coins it reserved are free). Every version since Android 1.5.1.
         */
        const val DEPTH = 6
        /** The reason a fully signed round shows while its first broadcast failed and it goes again. */
        const val NOT_SENT = "not sent yet, it goes again"
        /** v6: seconds between the reposts of a reserved round's signatures (and its creator's snapshot). */
        var REPOST_EVERY = 86_400L
        /** Seconds between the session's looks at its deadlines. */
        var TICK_SECONDS = 15L

        /** Why this wallet won't ask to close or say yes: too few people once its own seats count as one. */
        const val OWN_SEATS_ONE = "at least %d different people are needed: your own seats count as one"

        /**
         * People among the seats [tokenHashes]: all but one of this wallet's own seats ([own]) are left
         * out, so a wallet with two seats is one person. The roster itself can't tell people apart.
         */
        fun distinctPeople(tokenHashes: Collection<String>, own: Set<String>): Int {
            val mine = tokenHashes.count { it in own }
            return tokenHashes.size - maxOf(0, mine - 1)
        }

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
            /** Another seat of this phone in the pool ([State.seatId]), waiting until [joinAfter] to join. */
            seatId: String = "", joinAfter: Long = 0,
            /** A private pool's key from its password, in place of [password] (the creator taking another seat keeps only it). */
            passwordKey: ByteArray? = null,
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
                    Protocol.passwordProof(password?.let { Protocol.passwordKey(terms.id, it) } ?: passwordKey
                        ?: error("this pool needs its password"), joinPub) else null,
                creatorSecret = if (creator) creatorSecret else null, blindPriv = if (creator) blindPriv else null,
                seatId = seatId, joinAfter = joinAfter,
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
                    payments: List<Long> = emptyList(), now: Long = NostrEvent.nowSeconds(),
                    /**
                     * v6: an asynchronous pool with these windows (seconds; null for each = the longest) and its final
                     * time [asyncFinal] (null: room for two votes, two output stages and the signing after it closes).
                     */
                    async: Boolean = false, asyncVote: Long? = null, asyncOutputs: Long? = null, asyncSign: Long? = null,
                    asyncFinal: Long? = null): NewPool {
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
            require(!async || v3) { "an asynchronous pool is a v6 pool, with blind outputs" }
            if (v3) {
                val ck = NostrEvent.newSecret()
                val blind = BlindRsa.generate()
                val timeout = now + hours * 3600L
                val timing = if (!async) null else {
                    val l = Protocol.Async.longest(timeout)
                    val vote = asyncVote ?: l.vote; val outs = asyncOutputs ?: l.outputs; val sign = asyncSign ?: l.sign
                    Protocol.Async(vote, outs, sign, asyncFinal ?: minOf(timeout + 2 * vote + 2 * outs + sign, timeout + Protocol.ASYNC_TAIL_MAX))
                        .also { require(it.valid(timeout)) { "vote 10–60 min, outputs 5–30 min, signing 20–120 min, and the final time within a day after the pool's" } }
                }
                return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, timeout, "open", 1, now,
                    // One output each stays v3, which every v3 wallet can join; more need v4; asynchronous rounds v6.
                    minPeers, private, if (async) Protocol.V6 else if (payments.isNotEmpty()) Protocol.V5 else if (outputs > 1) Protocol.V4 else Protocol.V3,
                    script, maxInputs, NostrEvent.pubOf(ck), blind.public.encode(), outputs, payments, timing),
                    k.toHex(), ck.toHex(), blind.encodePrivate())
            }
            return NewPool(Protocol.Terms(id, NostrEvent.pubOf(k), amount, feeRate, maxPeers, now + hours * 3600L, "open", 1, now,
                minPeers, private, if (v1) Protocol.V1 else Protocol.VERSION, script, maxInputs), k.toHex())
        }
    }
}
