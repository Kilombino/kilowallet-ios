package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolAddresses
import com.kilombino.kilowallet.core.kilojoin.PoolPayment
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.PoolType
import com.kilombino.kilowallet.core.kilojoin.PooledCoins
import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.kilojoin.RelayClient
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/** Where a seat's equal outputs go: this wallet's fresh addresses, a watch-only key's, or (a payment) the recipients'. */
enum class PoolDest { HOT, WATCH, PAY }

/** A payment typed in SEND, to be paid through a new pool: each recipient's address and amount. */
data class PoolPayRequest(val recipients: List<Pair<String, Long>>, val feeRate: Double)

/** What the KILOJOIN screens show. [rev] moves on every change of a round (the states change in place). */
data class PoolsUi(
    val enabled: Boolean = false,
    val rounds: List<PoolSession.State> = emptyList(),
    val rev: Long = 0,
    val open: List<Protocol.Terms>? = null,
    val loading: Boolean = false,
    val relayError: String? = null,
    /** Something long is being done (keys, a pool's announcement): its description. */
    val busy: String? = null,
    val message: String? = null,
    /** The watch-only key equal outputs may go to (a zpub of native SegWit addresses), if set. */
    val watchKey: String? = null,
    val pay: PoolPayRequest? = null,
)

/**
 * The app's side of Kilojoin, after the Android app's `coinjoin/CoinjoinHub.kt`: keeps every round
 * this wallet is in (saved, so a round survives the app being closed), runs their sessions while
 * the app runs, lists the open pools on the relay, and joins or opens pools with the user's coins.
 *
 * iOS has no long-running background service: rounds run while the app is open, and keep going
 * for the short time iOS lets an app finish its work after it leaves the screen. Each round is
 * signed by the user, after the app has checked the transaction ([PoolSession.checkPlan]).
 */
class PoolsModel(
    private val platform: AppPlatform,
    private val wallet: () -> UiState,
    /** The wallet's spendable coins, read from its server. */
    private val gatherCoins: suspend () -> List<SpendableUtxo>,
    /** Check coins against the transactions that made them before anything is signed for them. */
    private val verifyCoins: suspend (List<SpendableUtxo>) -> Unit,
    private val onRoundDone: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = platform.prefs
    private val _state = MutableStateFlow(PoolsUi())
    val state: StateFlow<PoolsUi> = _state

    /** Replaced whole on every change, so the sessions' threads always read a complete map. */
    private var sessions: Map<String, PoolSession> = emptyMap()
    private var loadedFor: String? = null

    private fun walletKey(zpub: String) = Hashes.sha256(zpub.encodeToByteArray()).toHex().take(12)
    private fun kSessions(zpub: String) = "kilojoin.sessions.${walletKey(zpub)}"
    private fun kWatch(zpub: String) = "kilojoin.watch.${walletKey(zpub)}"
    private fun kPooled(zpub: String) = "kilojoin.pooled.${walletKey(zpub)}"
    private fun kDest(poolId: String) = "kilojoin.dest.$poolId"

    private val demo get() = platform.demoScreen != null

    // ------------------------------------------------------------------ loading and running

    /** Load this wallet's saved rounds (once per wallet) and start the ones still going. */
    fun attach() {
        val s = wallet()
        val zpub = s.zpub
        if (s.kind != WalletKind.HOT || zpub == null) { detach(); return }
        _state.update { it.copy(enabled = prefs.get(K_ENABLED) == "1" || demo, watchKey = prefs.get(kWatch(zpub))) }
        if (loadedFor == zpub) { startAll(); return }
        detach()
        loadedFor = zpub
        val raw = prefs.get(kSessions(zpub))
        val weekAgo = NostrEvent.nowSeconds() - 7 * 86400
        val loaded = LinkedHashMap<String, PoolSession>()
        raw?.let { r ->
            runCatching { Json.parseToJsonElement(r).jsonArray }.getOrNull()?.forEach { e ->
                val st = runCatching { PoolSession.State.parse(e.jsonObject) }.getOrNull() ?: return@forEach
                if (st.done && st.created < weekAgo) return@forEach
                loaded[st.poolId] = PoolSession(env(st), st, platform.sockets)
            }
        }
        sessions = loaded
        publish()
        startAll()
    }

    /** Another wallet (or none) is open now: stop this one's rounds. Nothing is lost: they are saved. */
    fun detach() {
        sessions.values.forEach { runCatching { it.dispose() } }
        sessions = emptyMap(); loadedFor = null
        _state.update { PoolsUi(enabled = it.enabled) }
    }

    private fun startAll() {
        if (demo) return
        sessions.values.filter { !it.done || it.state.phase == PoolSession.Phase.BROADCAST }.forEach { runCatching { it.start() } }
    }

    /** The app came back to the screen: rounds whose relay connection iOS cut reconnect on their next tick. */
    fun resume() = startAll()

    private fun persist() {
        val zpub = loadedFor ?: return
        prefs.put(kSessions(zpub), JsonArray(sessions.values.map { it.state.toJson() }).toString())
        keepPooledScripts(zpub)
    }

    private fun publish() {
        _state.update { it.copy(rounds = sessions.values.map { s -> s.state }.sortedByDescending { s -> s.created }, rev = it.rev + 1) }
    }

    /** The equal outputs of rounds that went out, kept for good: a coin from a pool is marked as such. */
    private fun keepPooledScripts(zpub: String) {
        val kept = prefs.get(kPooled(zpub))?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val live = sessions.values.map { it.state }
            .filter { it.phase == PoolSession.Phase.BROADCAST || it.phase == PoolSession.Phase.CONFIRMED }
            .filter { prefs.get(kDest(it.poolId))?.startsWith("PAY") != true }
            .flatMap { st -> st.mixScripts.map { it.toHex() } }
        val all = kept + live
        if (all.size != kept.size) prefs.put(kPooled(zpub), all.joinToString(","))
    }

    /** Whether [u] came out of a round of this wallet, or its transaction has a round's shape (asked once). */
    fun isPooled(u: SpendableUtxo): Boolean {
        val zpub = loadedFor ?: return false
        val script = coinScript(zpub, u).toHex()
        if (prefs.get(kPooled(zpub))?.split(',')?.contains(script) == true) return true
        return prefs.get("kilojoin.tx.${u.txid}:${u.vout}") == "1"
    }

    /** Asks the server about coins not yet known as from a pool or not (once per coin). */
    suspend fun learnPooled(coins: List<SpendableUtxo>) {
        val ask = coins.filter { !isPooled(it) && prefs.get("kilojoin.tx.${it.txid}:${it.vout}") == null }
        if (ask.isEmpty()) return
        withContext(ioDispatcher) {
            runCatching {
                electrum { c -> for (u in ask) runCatching { prefs.put("kilojoin.tx.${u.txid}:${u.vout}", if (PooledCoins.looksPooled(c.transaction(u.txid), u.vout)) "1" else "0") } }
            }
        }
    }

    // ------------------------------------------------------------------ the environment of a round

    private fun <T> electrum(f: (ElectrumClient) -> T): T {
        val s = wallet()
        val c = ElectrumClient(s.endpoint, s.pinned, platform.transports)
        try { c.connect(); return f(c) } finally { c.close() }
    }

    private fun env(st: PoolSession.State): PoolSession.Env = object : PoolSession.Env {
        override fun coinUnspent(coin: PoolTx.Coin): Boolean = electrum { c ->
            c.listUnspent(Address.electrumScriptHash(coin.script)).any { it.txid == coin.txid && it.vout == coin.vout && it.value == coin.value }
        }
        override fun broadcast(rawHex: String): String = electrum { it.broadcast(rawHex) }
        override fun confirmations(txid: String): Int? = electrum { c ->
            // Our first output's address sees the transaction (the recipient's, for a payment's creator).
            val h = c.history(Address.electrumScriptHash(st.mixScript)).firstOrNull { it.txid == txid } ?: return@electrum null
            if (h.height <= 0) 0 else c.blockHeight() - h.height + 1
        }
        override fun save(state: PoolSession.State) {
            // Left on purpose: nothing to keep, and the pool shows in the open list again.
            if (state.phase == PoolSession.Phase.ABORTED && state.reason == PoolSession.LEFT_BY_CHOICE) {
                val gone = sessions[state.poolId]
                if (gone != null) {
                    sessions = sessions - state.poolId
                    scope.launch { runCatching { gone.dispose() } }
                }
            }
            persist()
        }
        override fun event(e: PoolSession.Event) {
            scope.launch {
                publish()
                if (e is PoolSession.Event.Broadcast || e is PoolSession.Event.Confirmed || e is PoolSession.Event.Aborted) onRoundDone()
            }
        }
    }

    // ------------------------------------------------------------------ the open pools

    fun setEnabled(on: Boolean) {
        prefs.put(K_ENABLED, if (on) "1" else null)
        _state.update { it.copy(enabled = on) }
        if (on) refreshPools()
    }

    /** Open pools on the relay right now: this chain's, native SegWit, not test pools, not expired, newest first. */
    fun refreshPools() {
        if (demo || _state.value.loading) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            val r = runCatching { withContext(ioDispatcher) { fetchPools() } }
            _state.update { s ->
                s.copy(loading = false, open = r.getOrNull() ?: s.open,
                    relayError = r.exceptionOrNull()?.let { I18n.tr("Could not reach the relay: ") + (it.message ?: "") })
            }
        }
    }

    // Only the pools heard from lately: an open pool is re-announced every 10 minutes and listed for 35, so
    // twice that is every pool there is. Three days' worth (hundreds of announcements, each signature
    // checked here) only made the list slow.
    private suspend fun fetchPools(sinceSeconds: Long = 2 * Protocol.STALE_AFTER): List<Protocol.Terms> {
        val found = HashMap<String, Protocol.Terms>()
        val lock = kotlinx.coroutines.sync.Mutex()
        val r = RelayClient(Protocol.RELAY, platform.sockets, { _, ev ->
            Protocol.Terms.parse(ev)?.let { t ->
                lock.lock()
                try {
                    val old = found[t.id]
                    if (old == null || old.createdAt < t.createdAt) found[t.id] = t
                } finally { lock.unlock() }
            }
        })
        try {
            r.connect()
            r.subscribe("pools", listOf(com.kilombino.kilowallet.core.kilojoin.poolsFilter(NostrEvent.nowSeconds() - sinceSeconds)))
        } finally { r.close() }
        val now = NostrEvent.nowSeconds()
        lock.lock()
        val all = try { found.values.toList() } finally { lock.unlock() }
        return all.filter {
            it.state == "open" && it.expiresAt > now && it.peers < it.maxPeers && now - it.createdAt < Protocol.STALE_AFTER &&
                it.script == PoolType.P2WPKH && !Protocol.isTest(it)
        }.sortedByDescending { it.createdAt }
    }

    // ------------------------------------------------------------------ coins and addresses

    /** Outpoints of rounds being built (outputs handed in, signatures asked): a normal send must leave them alone. */
    fun lockedOutpoints(): Set<String> = sessions.values.filter { !it.done && it.state.phase in setOf(PoolSession.Phase.CLOSING, PoolSession.Phase.SIGNING) }
        .flatMap { s -> s.state.seat.coins.map { it.outpoint } }.toSet()

    /** Coins seated in a pool that has not started its round, outpoint → pool: spendable, at the cost of the seat. */
    fun waitingOutpoints(): Map<String, String> = sessions.values
        .filter { !it.done && it.state.phase in setOf(PoolSession.Phase.JOINING, PoolSession.Phase.OPEN, PoolSession.Phase.VOTING) }
        .flatMap { s -> s.state.seat.coins.map { it.outpoint to s.state.poolId } }.toMap()

    /** A normal send spent these coins: give up their seats (the others are told). */
    fun leaveFor(outpoints: Collection<String>) {
        val w = waitingOutpoints()
        val ids = outpoints.mapNotNull { w[it] }.toSet()
        for (id in ids) sessions[id]?.let { s -> scope.launch { runCatching { s.leave() } } }
    }

    /** Scripts every seat still counts on (equal outputs and change), so no payment hands them out. */
    fun reservedScripts(): Set<List<Byte>> = sessions.values.map { it.state }.filter { st ->
        val ended = st.phase == PoolSession.Phase.ABORTED || st.phase == PoolSession.Phase.REJECTED
        !(ended && !st.postedOutput)
    }.flatMap { st -> st.mixScripts + listOfNotNull(st.seat.changeScript) }.map { it.toList() }.toSet()

    private fun coinScript(zpub: String, u: SpendableUtxo): ByteArray =
        Address.scriptPubKey(Bip32.derivePath(Bip32.parseExtendedPubKey(zpub), u.chainIndex, u.index).pubkey(), ScriptType.P2WPKH)

    /** Confirmed native SegWit coins not seated in another round, biggest first. */
    suspend fun poolCoins(): List<SpendableUtxo> {
        val seated = waitingOutpoints().keys + lockedOutpoints()
        val coins = gatherCoins().filter { it.height > 0 && "${it.txid}:${it.vout}" !in seated }.sortedByDescending { it.value }
        runCatching { learnPooled(coins) }
        return coins
    }

    /** Fresh receive indexes of this wallet for [n] equal outputs: past the last used one, none a seat holds. */
    private fun freshReceive(zpub: String, n: Int): List<ByteArray> {
        val s = wallet()
        val acct = Bip32.parseExtendedPubKey(zpub)
        val held = reservedScripts()
        var i = s.rows.filter { it.chainIndex == 0 && it.isUsed }.maxOfOrNull { it.index }?.plus(1) ?: 0
        val out = mutableListOf<ByteArray>()
        while (out.size < n) {
            val sp = Address.scriptPubKey(Bip32.derivePath(acct, 0, i).pubkey(), ScriptType.P2WPKH)
            if (sp.toList() !in held) out += sp
            i++
        }
        return out
    }

    /** A fresh change address of this wallet that no seat holds. */
    private fun freshChange(zpub: String): ByteArray {
        val s = wallet()
        val acct = Bip32.parseExtendedPubKey(zpub)
        val held = reservedScripts()
        var i = s.rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
        while (true) {
            val sp = Address.scriptPubKey(Bip32.derivePath(acct, 1, i).pubkey(), ScriptType.P2WPKH)
            if (sp.toList() !in held) return sp
            i++
        }
    }

    /** [n] unused receive addresses of the watch-only key, asked to the server (network). */
    private fun watchScripts(n: Int): List<ByteArray> {
        val key = _state.value.watchKey ?: error("There is no watch-only key set for equal outputs.")
        val acct = Bip32.parseExtendedPubKey(key)
        require(acct.scriptType == ScriptType.P2WPKH) { "The watch-only key must be of native SegWit (bc1q) addresses." }
        val derive = { i: Int -> Address.scriptPubKey(Bip32.derivePath(acct, 0, i).pubkey(), ScriptType.P2WPKH) }
        val held = reservedScripts()
        return electrum { c ->
            PoolAddresses.pickUnusedIndexes(n, 0, held, derive) { sp -> c.history(Address.electrumScriptHash(sp)).isNotEmpty() }
        }.map(derive)
    }

    /** Set (or clear, with null) the watch-only key equal outputs may go to. Returns an error message, or null. */
    fun setWatchKey(key: String?): String? {
        val zpub = loadedFor ?: wallet().zpub ?: return "No wallet."
        if (key == null) { prefs.put(kWatch(zpub), null); _state.update { it.copy(watchKey = null) }; return null }
        val k = key.filterNot { it.isWhitespace() }
        val parsed = runCatching { Bip32.parseExtendedPubKey(k) }.getOrElse { return it.message ?: "Not a valid key." }
        if (parsed.scriptType != ScriptType.P2WPKH) return "The watch-only key must be a zpub (native SegWit, bc1q): a pool's outputs are all of one type."
        if (k == zpub) return "That is this wallet's own key: its equal outputs come here anyway."
        prefs.put(kWatch(zpub), k)
        _state.update { it.copy(watchKey = k) }
        return null
    }

    /** Where [poolId]'s equal outputs go, as noted on this phone when it began. */
    fun destOf(poolId: String): PoolDest = prefs.get(kDest(poolId))?.substringBefore('|')?.let { n -> PoolDest.entries.firstOrNull { it.name == n } } ?: PoolDest.HOT

    /** The addresses of [st]'s own outputs, for its card. */
    fun outputAddresses(st: PoolSession.State): List<String> = st.mixScripts.map { Address.fromScriptPubKey(it) ?: it.toHex() }

    // ------------------------------------------------------------------ keys

    private suspend fun unlock(reason: String): Pair<List<String>, String> {
        val (secret, err) = suspendCancellableCoroutine<Pair<String?, String?>> { c -> platform.seeds.load(reason) { s, e -> c.resume(s to e) } }
        if (secret == null) error(err ?: "Not unlocked.")
        return decodeSecret(secret)
    }

    /** The private keys of [paths] from the words, each checked against this wallet's public key. */
    private fun keysFor(words: Pair<List<String>, String>, zpub: String, paths: List<String>, pubs: List<ByteArray>): List<ByteArray> {
        val seed = Bip39.toSeed(words.first, words.second)
        val master = Bip32Priv.fromSeed(seed)
        seed.fill(0)
        try {
            return paths.mapIndexed { i, p ->
                val node = Bip32Priv.derivePath(master, p)
                check(node.publicKey().contentEquals(pubs[i])) { "These recovery words do not belong to this wallet. Nothing was signed." }
                node.keyBytes().also { node.wipe() }
            }
        } finally { master.wipe() }
    }

    private fun pathOf(u: SpendableUtxo) = "m/84'/0'/0'/${u.chainIndex}/${u.index}"

    private fun poolCoin(zpub: String, u: SpendableUtxo): PoolTx.Coin =
        PoolTx.Coin(u.txid, u.vout, u.value, Bip32.derivePath(Bip32.parseExtendedPubKey(zpub), u.chainIndex, u.index).pubkey())

    private fun add(st: PoolSession.State, dest: PoolDest, note: String = "") {
        val s = PoolSession(env(st), st, platform.sockets)
        sessions = sessions + (st.poolId to s)
        prefs.put(kDest(st.poolId), dest.name + "|" + note)
        persist(); publish()
        if (!demo) s.start()
    }

    private fun run(busy: String, reason: String, done: (Boolean) -> Unit = {}, work: suspend (Pair<List<String>, String>) -> Unit) {
        scope.launch {
            val r = runCatching {
                val words = unlock(reason)
                _state.update { it.copy(busy = busy) }
                work(words)
            }
            _state.update { it.copy(busy = null, message = r.exceptionOrNull()?.let { e -> e.message ?: "failed" }) }
            done(r.isSuccess)
        }
    }

    /** Join [t] with [coins]: one unlock proves we own them; nothing is signed for the round yet. */
    fun join(t: Protocol.Terms, coins: List<SpendableUtxo>, dest: PoolDest, password: String?, done: (Boolean) -> Unit) {
        val zpub = wallet().zpub ?: return
        run(I18n.tr("Proving you own the coins and asking to join…"), I18n.tr("Unlock to join the pool with your coins"), done) { words ->
            require(t.script == PoolType.P2WPKH) { "This app joins native SegWit (bc1q) pools only." }
            verifyCoins(coins)
            val mix = withContext(ioDispatcher) { if (dest == PoolDest.WATCH) watchScripts(t.outputs) else freshReceive(zpub, t.outputs) }
            val pcs = coins.map { poolCoin(zpub, it) }
            val keys = withContext(Dispatchers.Default) { keysFor(words, zpub, coins.map(::pathOf), pcs.map { it.pubkey }) }
            val st = withContext(Dispatchers.Default) {
                PoolSession.newState(t, false, null, pcs, keys, coins.map(::pathOf), mix, freshChange(zpub), password?.ifEmpty { null })
            }
            keys.forEach { it.fill(0) }
            add(st, dest, if (dest == PoolDest.WATCH) (_state.value.watchKey?.take(12) ?: "") else "")
        }
    }

    /**
     * Open a pool with [coins] as the first seat: [amount] per equal output, [outputs] each. With
     * [pay], a payment: its recipients get the outputs (equal amounts, v4) or their own amounts (v5).
     */
    fun create(amount: Long, rate: Double, minPeers: Int, maxPeers: Int, hours: Int, maxInputs: Int, outputs: Int, password: String?,
               coins: List<SpendableUtxo>, dest: PoolDest, pay: PoolPayRequest?, done: (Boolean) -> Unit) {
        val zpub = wallet().zpub ?: return
        run(I18n.tr("Making the pool's keys and announcing it…"), I18n.tr("Unlock to put your coins in the pool"), done) { words ->
            verifyCoins(coins)
            val check = pay?.let { PoolPayment.check(it.recipients) }
            require(check == null || check.any) { check?.reason ?: "" }
            val payments = if (check != null && !check.ok) check.amounts else emptyList()
            val mix = when {
                check != null -> check.scripts
                dest == PoolDest.WATCH -> withContext(ioDispatcher) { watchScripts(outputs) }
                else -> freshReceive(zpub, outputs)
            }
            val pcs = coins.map { poolCoin(zpub, it) }
            val keys = withContext(Dispatchers.Default) { keysFor(words, zpub, coins.map(::pathOf), pcs.map { it.pubkey }) }
            val st = withContext(Dispatchers.Default) {
                val np = PoolSession.newPool(amount, rate, maxPeers, hours, minPeers, !password.isNullOrEmpty(), PoolType.P2WPKH,
                    maxInputs, v3 = true, outputs = outputs, payments = payments)
                PoolSession.newState(np.terms, true, np.secret, pcs, keys, coins.map(::pathOf), mix, freshChange(zpub),
                    password?.ifEmpty { null }, np.creatorSecret, np.blindPriv)
            }
            keys.forEach { it.fill(0) }
            add(st, if (check != null) PoolDest.PAY else dest, when {
                check != null -> pay!!.recipients.joinToString(",") { it.first }
                dest == PoolDest.WATCH -> _state.value.watchKey?.take(12) ?: ""
                else -> ""
            })
            if (pay != null) _state.update { it.copy(pay = null) }
        }
    }

    fun session(poolId: String): PoolSession? = sessions[poolId]

    /** The user looked at what was checked and signs: one unlock for the keys of our coins in the round. */
    fun sign(poolId: String) {
        val s = sessions[poolId] ?: return
        val zpub = wallet().zpub ?: return
        run(I18n.tr("Signing your inputs…"), I18n.tr("Unlock to sign your coins in the round")) { words ->
            val st = s.state
            val keys = withContext(Dispatchers.Default) { keysFor(words, zpub, st.coinPaths, st.seat.coins.map { it.pubkey }) }
            try { s.sign(keys) } finally { keys.forEach { it.fill(0) } }
        }
    }

    private fun act(poolId: String, f: suspend (PoolSession) -> Unit) {
        val s = sessions[poolId] ?: return
        scope.launch {
            val r = runCatching { withContext(ioDispatcher) { f(s) } }
            r.exceptionOrNull()?.let { e -> _state.update { it.copy(message = e.message ?: "failed") } }
            publish()
        }
    }

    fun requestClose(poolId: String) = act(poolId) { it.requestClose() }
    fun vote(poolId: String, yes: Boolean) = act(poolId) { it.vote(yes) }
    fun leave(poolId: String) = act(poolId) { it.leave() }

    /** Forget a finished round. */
    fun remove(poolId: String) {
        sessions[poolId]?.dispose()
        sessions = sessions - poolId
        persist(); publish()
    }

    fun removeFinished() = _state.value.rounds.filter { it.done }.forEach { remove(it.poolId) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** A payment from SEND, waiting for its pool to be opened. */
    fun startPayment(p: PoolPayRequest?) = _state.update { it.copy(pay = p) }

    // ------------------------------------------------------------------ demo (CI screenshots only)

    /** Throwaway pools and rounds for the screenshot run: nothing goes to the relay or the server. */
    internal fun setUpDemo(screen: String, words: List<String>) {
        val zpub = wallet().zpub ?: return
        loadedFor = zpub
        _state.update { it.copy(enabled = true) }
        val now = NostrEvent.nowSeconds()
        // One pool's keys for all of them (made-up pools: only their terms are shown).
        val base = PoolSession.newPool(50_000, 2.0, 5, 6, now = now - 1800)
        fun terms(amount: Long, outputs: Int, peers: Int, rate: Double, payments: List<Long> = emptyList(), private: Boolean = false) =
            base.terms.copy(id = Hashes.sha256("demo$amount$outputs".encodeToByteArray()).toHex().take(16), amount = amount, outputs = outputs,
                peers = peers, feeRate = rate, payments = payments, private = private, createdAt = now - 600,
                version = if (payments.isNotEmpty()) Protocol.V5 else if (outputs > 1) Protocol.V4 else Protocol.V3)
        val open = listOf(terms(100_000, 1, 2, 2.0), terms(50_000, 2, 3, 1.0), terms(25_000, 2, 1, 3.0, listOf(20_000, 30_000)),
            terms(1_000_000, 1, 1, 1.0, private = true))
        _state.update { it.copy(open = open) }
        if (screen == "pool-round" || screen == "pools") {
            val seed = Bip39.toSeed(words, "")
            val master = Bip32Priv.fromSeed(seed)
            fun k(c: Int, i: Int) = Bip32Priv.derivePath(master, "m/84'/0'/0'/$c/$i").keyBytes()
            val np = base
            val ours = PoolTx.Coin("4f".repeat(32), 0, 61_234, com.kilombino.kilowallet.core.crypto.Ec.publicKey(k(0, 0)))
            val st = PoolSession.newState(np.terms, false, null, listOf(ours), listOf(k(0, 0)), listOf("m/84'/0'/0'/0/0"),
                listOf(Address.scriptPubKey(com.kilombino.kilowallet.core.crypto.Ec.publicKey(k(0, 3)), ScriptType.P2WPKH)),
                Address.scriptPubKey(com.kilombino.kilowallet.core.crypto.Ec.publicKey(k(1, 2)), ScriptType.P2WPKH))
            st.token = "demo"; st.poolSecret = np.secret
            val me = st.seat.copy(tokenHash = Protocol.tokenHash("demo"))
            val other = Protocol.Seat(Protocol.tokenHash("x"), listOf(PoolTx.Coin("6a".repeat(32), 1, 50_000 + PoolTx.feeShare(2.0, false),
                com.kilombino.kilowallet.core.crypto.Ec.publicKey(Hashes.sha256("demo-other".encodeToByteArray())))), null, 0)
            val third = Protocol.Seat(Protocol.tokenHash("y"), listOf(PoolTx.Coin("7b".repeat(32), 2, 80_000,
                com.kilombino.kilowallet.core.crypto.Ec.publicKey(Hashes.sha256("demo-third".encodeToByteArray())))),
                Address.scriptPubKey(com.kilombino.kilowallet.core.crypto.Ec.publicKey(Hashes.sha256("demo-third-change".encodeToByteArray())), ScriptType.P2WPKH),
                PoolTx.change(80_000, 50_000, 2.0)!!)
            st.seats = mutableListOf(me, other, third); st.round = mutableListOf(me, other, third)
            if (screen == "pool-round") {
                st.outputs.add(st.mixScript.toHex())
                st.outputs.add(Address.scriptPubKey(com.kilombino.kilowallet.core.crypto.Ec.publicKey(Hashes.sha256("demo-o1".encodeToByteArray())), ScriptType.P2WPKH).toHex())
                st.outputs.add(Address.scriptPubKey(com.kilombino.kilowallet.core.crypto.Ec.publicKey(Hashes.sha256("demo-o2".encodeToByteArray())), ScriptType.P2WPKH).toHex())
                st.phase = PoolSession.Phase.SIGNING; st.phaseDeadline = now + 17 * 60
            } else st.phase = PoolSession.Phase.OPEN
            master.wipe()
            sessions = mapOf(st.poolId to PoolSession(env(st), st, platform.sockets))
            publish()
        }
        if (screen == "pool-pay") _state.update { it.copy(pay = PoolPayRequest(listOf(
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to 30_000L,
            "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g" to 45_000L), 2.0)) }
    }

    private companion object {
        const val K_ENABLED = "kilojoin.enabled"
    }
}
