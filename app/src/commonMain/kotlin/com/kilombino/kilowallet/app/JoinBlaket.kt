package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.CertificateChangedException
import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.ElectrumException
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip32Priv
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.joinblaket.ChainLookup
import com.kilombino.kilowallet.core.joinblaket.CommitmentStore
import com.kilombino.kilowallet.core.joinblaket.DirectoryClient
import com.kilombino.kilowallet.core.joinblaket.DirectoryException
import com.kilombino.kilowallet.core.joinblaket.ElectrumChainLookup
import com.kilombino.kilowallet.core.joinblaket.IoauthCheck
import com.kilombino.kilowallet.core.joinblaket.JbNetwork
import com.kilombino.kilowallet.core.joinblaket.JoinBlaketDirectory
import com.kilombino.kilowallet.core.joinblaket.JoinBlaketMarket
import com.kilombino.kilowallet.core.joinblaket.JsonCommitmentStore
import com.kilombino.kilowallet.core.joinblaket.NickIdentity
import com.kilombino.kilowallet.core.joinblaket.TakerCoin
import com.kilombino.kilowallet.core.joinblaket.TakerEnv
import com.kilombino.kilowallet.core.joinblaket.TakerPlan
import com.kilombino.kilowallet.core.joinblaket.TakerRequest
import com.kilombino.kilowallet.core.joinblaket.TakerResult
import com.kilombino.kilowallet.core.joinblaket.TakerSession
import com.kilombino.kilowallet.core.joinblaket.TakerStep
import com.kilombino.kilowallet.core.joinblaket.TakerText
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.tor.Tor
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * PAY THROUGH JOINBLAKET, the app's side (Kilowallet for Android 1.6.0's `JoinBlaketPay`, without
 * its foreground service): SEND hands over a payment ([start]), the round runs as a JoinMarket
 * taker ([TakerSession]) while the app is open, and [view] tells the JOINBLAKET screen where it is.
 * The user confirms the plan (makers' fees and mining fee) before anything about the coins leaves
 * the phone ([answer]).
 *
 * iOS keeps no app running in the background: leaving the app keeps the round going for the
 * short time iOS allows (a background task), as Kilojoin rounds do; the screen says so.
 *
 * One round at a time. While it runs its coins are kept out of other payments and pools
 * ([lockedOutpoints]) and its change address is held ([heldChangeScripts]); the coins' keys live
 * only in the request and are dropped when the round ends. Used PoDLE commitments are kept in the
 * app's settings (written to disk before each is shown) and travel in the .kab as Android's
 * `joinblaket_commitments`.
 */
class JoinBlaketModel(
    private val platform: AppPlatform,
    private val wallet: () -> UiState,
    /** The wallet's spendable native SegWit coins, read from its server. */
    private val gatherCoins: suspend () -> List<SpendableUtxo>,
    /** Check coins against the transactions that made them before any key is used for them. */
    private val verifyCoins: suspend (List<SpendableUtxo>) -> Unit,
    /** Outpoints Kilojoin holds (seated or in a round): never offered to a JoinBlaket round. */
    private val heldByPools: () -> Set<String>,
    /** Scripts Kilojoin counts on: never handed out as a JoinBlaket change address. */
    private val poolScripts: () -> Set<List<Byte>>,
    private val onSent: () -> Unit,
) {
    /** The round as the screen shows it. */
    data class View(
        val amount: Long,
        val recipient: String,
        val step: TakerStep? = null,
        /** Set while the plan waits for the user's answer. */
        val plan: TakerPlan? = null,
        val result: TakerResult? = null,
    ) {
        val finished: Boolean get() = result != null
        /** Signed and on its way: cancelling no longer stops it. */
        val pastReturn: Boolean get() = step == TakerStep.Broadcasting
    }

    /** The send screen's market: the last one read, or why it could not be. */
    data class MarketView(val market: JoinBlaketMarket.Market? = null, val failed: Boolean = false)

    private class Request(
        val recipient: String, val amount: Long, val feeRate: Double,
        val coins: List<TakerCoin>, val changeAddress: String, val changeScript: ByteArray,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _view = MutableStateFlow<View?>(null)
    val view: StateFlow<View?> = _view
    private val _market = MutableStateFlow(MarketView())
    val market: StateFlow<MarketView> = _market
    /** Why PAY THROUGH JOINBLAKET did not start (shown under its button), or null. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Unlocking or getting the coins ready: the button waits. */
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing

    private var request: Request? = null
    private var job: Job? = null
    private var answer: CompletableDeferred<Boolean>? = null
    private var background: Any? = null

    val running: Boolean get() = _view.value?.finished == false
    private val demo get() = platform.demoScreen != null

    // ------------------------------------------------------------------ the market

    /** Read the market once (the send screen asks every 30 s while it is open). */
    fun refreshMarket() {
        if (demo) return
        platform.httpGet(JoinBlaketMarket.URL_MARKET) { body ->
            val m = body?.let { runCatching { JoinBlaketMarket.parse(it) }.getOrNull() }
            scope.launch {
                _market.update { if (m != null) MarketView(m) else if (it.market == null) MarketView(failed = true) else it }
            }
        }
    }

    fun clearError() { _error.value = null }

    // ------------------------------------------------------------------ starting a round

    /** Confirmed native SegWit coins no Kilojoin seat or round holds: the ones a round may use. */
    private suspend fun eligibleCoins(): List<SpendableUtxo> {
        val held = heldByPools()
        return gatherCoins().filter { it.height > 0 && (it.type == null || it.type == ScriptType.P2WPKH) && "${it.txid}:${it.vout}" !in held }
    }

    /**
     * PAY THROUGH JOINBLAKET: check the coins (makers take a PoDLE only from a coin 5 deep with 20 %
     * of the amount: said here, with when one will be ready, instead of failing inside the round),
     * unlock, derive the coins' keys and a fresh change address, and start the round.
     * [done] is called with true once the round has started (the caller opens its screen).
     */
    fun start(recipient: String, amount: Long, feeRate: Double, done: (Boolean) -> Unit) {
        val s = wallet()
        val zpub = s.zpub
        if (s.kind != WalletKind.HOT || zpub == null) { _error.value = I18n.tr(NOT_SUPPORTED); done(false); return }
        if (running) { _error.value = I18n.tr(BUSY); done(false); return }
        _error.value = null
        _preparing.value = true
        scope.launch {
            val r = runCatching {
                val all = gatherCoins()
                val coins = eligibleCoins()
                val need = amount * 20 / 100
                val pendingBig = all.filter { it.height <= 0 && it.value >= need }
                if (coins.isEmpty()) {
                    error(if (pendingBig.isNotEmpty()) TakerText.fill(WAIT_UNCONFIRMED, 5, 50) else NO_COINS)
                }
                val tip = _market.value.market?.height ?: wallet().height.takeIf { it > 0 }
                if (tip != null) {
                    fun confs(c: SpendableUtxo) = if (c.height > 0) tip - c.height + 1 else 0
                    if (coins.none { confs(it) >= 5 && it.value >= need }) {
                        val big = coins.filter { it.value >= need }
                        error(if (big.isEmpty() && pendingBig.isEmpty()) TakerText.fill(NO_BIG_COIN, need)
                        else {
                            val wait = (big.map { 5 - confs(it) } + if (pendingBig.isNotEmpty()) listOf(5) else emptyList()).min().coerceAtLeast(1)
                            TakerText.fill(WAIT_CONFIRMATIONS, wait, wait * 10)
                        })
                    }
                }
                val words = unlock(I18n.tr(UNLOCK))
                verifyCoins(coins)
                withContext(Dispatchers.Default) { request(words, zpub, coins, recipient, amount, feeRate) }
            }
            _preparing.value = false
            r.onSuccess { req -> done(begin(req)) }
             .onFailure { e -> _error.value = I18n.tr(e.message ?: "could not unlock"); done(false) }
        }
    }

    private suspend fun unlock(reason: String): Pair<List<String>, String> {
        val (secret, err) = suspendCancellableCoroutine<Pair<String?, String?>> { c -> platform.seeds.load(reason) { s, e -> c.resume(s to e) } }
        if (secret == null) error(err ?: "Not unlocked.")
        return decodeSecret(secret)
    }

    /** The keys of [coins] (each checked against this wallet's key and its coin's address) and a fresh change address. */
    private fun request(words: Pair<List<String>, String>, zpub: String, coins: List<SpendableUtxo>, recipient: String,
                        amount: Long, feeRate: Double): Request {
        require(coins.isNotEmpty()) { NO_COINS }
        val acct = Bip32.parseExtendedPubKey(zpub)
        val seed = Bip39.toSeed(words.first, words.second)
        val master = Bip32Priv.fromSeed(seed)
        seed.fill(0)
        val taker = try {
            coins.map { u ->
                val node = Bip32Priv.derivePath(master, "m/84'/0'/0'/${u.chainIndex}/${u.index}")
                val pub = Bip32.derivePath(acct, u.chainIndex, u.index).pubkey()
                check(node.publicKey().contentEquals(pub)) { "These recovery words do not belong to this wallet. Nothing was signed." }
                val c = TakerCoin(u.txid, u.vout.toLong(), u.value, node.keyBytes(), u.height)
                node.wipe()
                // Each key must spend its coin's address: a mismatch would only fail later, at the makers.
                check(c.scriptPubKey.contentEquals(Address.scriptPubKey(pub, ScriptType.P2WPKH))) { "the wallet's keys do not match its addresses" }
                c
            }
        } finally { master.wipe() }
        val change = freshChange(acct)
        return Request(recipient.trim(), amount, feeRate, taker, JbNetwork.MAINNET.p2wpkhAddress(change), change)
    }

    /** A fresh change address of this wallet (native SegWit) that no Kilojoin seat holds. */
    private fun freshChange(acct: com.kilombino.kilowallet.core.crypto.Bip32.ExtendedPubKey): ByteArray {
        val s = wallet()
        val held = poolScripts()
        var i = s.rows.filter { it.chainIndex == 1 && it.type == null }.maxOfOrNull { it.index }?.plus(1) ?: 0
        while (true) {
            val sp = Address.scriptPubKey(Bip32.derivePath(acct, 1, i).pubkey(), ScriptType.P2WPKH)
            if (sp.toList() !in held) return sp
            i++
        }
    }

    private fun begin(r: Request): Boolean {
        if (running) return false
        request = r
        _view.value = View(r.amount, r.recipient)
        // The directory is an onion: no Tor in this build, no round.
        if (!Tor.available) { finish(TakerResult.Failed(I18n.tr(NO_TOR))); return true }
        background = platform.beginJoinBlaketWork("JoinBlaket payment")
        val j = scope.launch {
            val chain = AppChain()
            val result = try {
                withContext(ioDispatcher) { TakerSession(Env(chain)).run(TakerRequest(r.recipient, r.amount, r.feeRate, r.coins, r.changeAddress)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TakerResult.Failed(CHAIN, e.message)
            } finally {
                withContext(ioDispatcher) { chain.close() }
            }
            finish(result)
        }
        job = j
        return true
    }

    /** The user's answer to the plan. */
    fun answer(yes: Boolean) { answer?.complete(yes) }

    /** Stop the round, unless it is already signed and on its way. */
    fun cancel() {
        val v = _view.value ?: return
        if (v.finished || v.pastReturn) return
        answer?.complete(false)
        job?.cancel()
        finish(TakerResult.Cancelled(TakerText.CANCELLED))
    }

    /** Forget a finished round (its screen is closed). */
    fun dismiss() {
        if (_view.value?.finished == true) _view.value = null
    }

    /** Coins of the round going: kept out of other payments and pools until it ends. */
    fun lockedOutpoints(): Set<String> =
        if (running) request?.coins?.map { "${it.txid}:${it.vout}" }?.toSet().orEmpty() else emptySet()

    /** The change address of the round going (and of one just sent, until the wallet sees it used). */
    fun heldChangeScripts(): Set<List<Byte>> = setOfNotNull(request?.changeScript?.toList())

    private fun finish(result: TakerResult) {
        val v = _view.value ?: return
        if (v.finished) return
        _view.value = v.copy(plan = null, result = result)
        // The keys go now; the change address stays held until the wallet has seen it.
        request = request?.let { r ->
            r.coins.forEach { it.privateKey.fill(0) }
            if (result is TakerResult.Sent) Request(r.recipient, r.amount, r.feeRate, emptyList(), r.changeAddress, r.changeScript) else null
        }
        answer = null; job = null
        platform.endJoinBlaketWork(background); background = null
        if (result is TakerResult.Sent) onSent()
    }

    // ------------------------------------------------------------------ what the round needs from the app

    /** Used PoDLE commitments, in Android's (Python's) `commitments.json` format, in the app's settings. */
    val commitments = JsonCommitmentStore(
        load = { platform.prefs.get(COMMITMENTS) },
        save = { platform.prefs.put(COMMITMENTS, it); platform.prefs.flush() },
    )

    private inner class Env(private val appChain: AppChain) : TakerEnv {
        override suspend fun directory(identity: NickIdentity): DirectoryClient {
            val port = Tor.startAndWait(120_000)
                ?: throw DirectoryException("Tor did not come up: ${Tor.lastLine}")
            val t = platform.directoryTransport(port) ?: throw DirectoryException("no connection through Tor on this platform")
            return DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, identity, JoinBlaketDirectory.NETWORK.wire,
                JoinBlaketDirectory.ID, requireVerified = true, transport = t, viaTor = true)
        }
        override val chain: ChainLookup = appChain
        override fun broadcast(rawHex: String): String = appChain.broadcast(rawHex)
        override val commitments: CommitmentStore = this@JoinBlaketModel.commitments

        override suspend fun confirm(plan: TakerPlan): Boolean {
            val d = CompletableDeferred<Boolean>()
            answer = d
            _view.update { it?.copy(plan = plan) }
            return try { d.await() } finally { _view.update { it?.copy(plan = null) } }
        }

        override fun progress(step: TakerStep) { _view.update { it?.copy(step = step) } }
    }

    /**
     * Our chain view for the round: the wallet's Electrum server, every coin's block proven by SPV
     * (its merkle branch into a header with real proof of work, as the wallet checks incoming
     * payments). Opened on first use; a call that fails on a dropped connection opens it again once.
     * Blocking, one call at a time (the round makes them one after another).
     */
    private inner class AppChain : ChainLookup {
        private var electrum: ElectrumClient? = null
        private var lookup: ChainLookup? = null

        private fun open(): ElectrumClient {
            electrum?.let { return it }
            val s = wallet()
            val c = ElectrumClient(s.endpoint, s.pinned, platform.transports).also { it.connect() }
            electrum = c
            lookup = ElectrumChainLookup(c) { txid, h -> ElectrumChainLookup.spvProof(c, txid, h, Scanner.SPV_FLOOR_BITS) }
            return c
        }

        private fun <T> call(f: (ChainLookup, ElectrumClient) -> T): T {
            val c = open()
            return try { f(lookup!!, c) } catch (e: CertificateChangedException) { throw e } catch (e: ElectrumException) {
                close(); val c2 = open(); f(lookup!!, c2)
            }
        }

        override fun height(): Int = call { l, _ -> l.height() }
        override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? = call { l, _ -> l.utxo(txid, vout, scriptPubKey) }
        override fun blockTime(height: Int): Long = call { l, _ -> l.blockTime(height) }
        fun broadcast(rawHex: String): String = call { _, c -> c.broadcast(rawHex) }

        fun close() {
            runCatching { electrum?.close() }
            electrum = null; lookup = null
        }
    }

    // ------------------------------------------------------------------ screenshots (CI only)

    /** A made-up round for the screenshots: nothing is connected, unlocked or signed. */
    internal fun setDemo(v: View?, market: JoinBlaketMarket.Market? = null) {
        _view.value = v
        if (market != null) _market.value = MarketView(market)
    }

    /**
     * CI screenshot run, real network, no keys: Tor in the app, the JoinBlaket directory by its
     * onion with nick authentication, the orderbook as a watcher asks for it, the bonds valued on
     * the wallet's server with SPV. Returns what to show; prints counts only (never nicks or bonds).
     */
    internal suspend fun liveCheck(): String = withContext(ioDispatcher) {
        val port = Tor.startAndWait(300_000) ?: return@withContext "KW jb-live: Tor did not come up: ${Tor.lastLine}"
        println("KW jb-live: Tor SOCKS port $port")
        val t = platform.directoryTransport(port) ?: return@withContext "KW jb-live: no directory transport"
        "KW jb-live: " + com.kilombino.kilowallet.core.joinblaket.LiveCheck.orderbook(t, { AppChain() }, { (it as AppChain).close() },
            log = { println("KW jb-live: $it") })
    }

    companion object {
        private const val COMMITMENTS = "joinblaket.commitments"

        // iOS wording (App Store naming: the round is a JoinBlaket payment, never the protocol's own name).
        const val UNLOCK = "Unlock to let JoinBlaket use your coins"
        const val NO_COINS = "No confirmed bc1q coins to pay from."
        const val BUSY = "Another JoinBlaket payment is going: wait for it to end."
        const val NOT_SUPPORTED = "JoinBlaket needs a spending wallet with native SegWit (bc1q) addresses."
        const val NO_TOR = "Tor can't run on this phone, and the JoinBlaket directory is reached only through Tor."
        const val CHAIN = "Your BTC server could not be reached or its data did not verify. Nothing was sent."
        const val WAIT_CONFIRMATIONS =
            "None of these coins has 5 confirmations yet: makers only accept one that has. The first will in about {0} blocks (~{1} min)."
        const val WAIT_UNCONFIRMED =
            "Your coin is still unconfirmed: it needs {0} confirmations before it can open a JoinBlaket payment (~{1} min)."
        const val NO_BIG_COIN =
            "None of these coins can open this JoinBlaket payment: one needs at least {0} sats (20% of the amount)."

        /**
         * The shared code's reasons (Android's words) as the iOS screens say them: the ones that
         * name the transaction by the protocol's name get their iOS sentence, values kept.
         */
        private val IOS_REASONS: List<Pair<String, String>> by lazy {
            listOf(
                TakerText.NO_PODLE_COIN to "None of your coins can open this JoinBlaket payment: one needs at least {0} confirmations and at least {1} sats (20% of the amount).",
                TakerText.NO_FRESH_COMMITMENT to "Your coins that can open a JoinBlaket payment have each been used for three already. Move one to a fresh address of yours and try again.",
                TakerText.NOT_ENOUGH_COINS to "Not enough coins: this payment needs up to {0} sats and your coins add up to {1} sats.",
                TakerText.BAD_TX to "The JoinBlaket transaction did not add up, so it was not signed. Nothing was sent.",
                TakerText.INPUT_SPENT to "A coin of the JoinBlaket transaction was spent meanwhile. Nothing was sent.",
                TakerText.NOT_BROADCAST to "The JoinBlaket transaction was signed but could not be broadcast. It may still go out: wait before spending these coins.",
            )
        }

        /** [reason] (a [TakerText] sentence with its values filled) in the iOS wording, then in the app's language. */
        fun reasonText(reason: String): String {
            for ((android, ios) in IOS_REASONS) {
                val values = TakerText.values(android, reason) ?: continue
                var out = ios
                values.forEachIndexed { i, v -> out = out.replace("{$i}", v) }
                return I18n.tr(out)
            }
            return I18n.tr(reason)
        }
    }
}
