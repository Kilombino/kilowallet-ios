package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.core.market.MarketData
import com.kilombino.kilowallet.core.market.MarketFeed
import com.kilombino.kilowallet.core.wallet.AddressRow
import com.kilombino.kilowallet.core.wallet.ReplacePlanner
import com.kilombino.kilowallet.core.wallet.ScanEvent
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.SendDraft
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.TxConf
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

enum class WalletKind { HOT, WATCH }

sealed interface Screen {
    data object Welcome : Screen
    data class CreateShow(val words: List<String>) : Screen
    data class CreateConfirm(val words: List<String>, val positions: List<Int>) : Screen
    data object Restore : Screen
    data object Watch : Screen
    data object Home : Screen
    data object Receive : Screen
    data object Send : Screen
    data object Settings : Screen
    /** Speed up ([cancel] false) or cancel an unconfirmed send of this wallet by replacing it (RBF). */
    data class Replace(val txid: String, val cancel: Boolean) : Screen
    data class ShowWords(val words: List<String>) : Screen
    /** KILOJOIN: open pools, your rounds, and how it works. */
    data object Pools : Screen
    /** Open a pool of your own (with [Screen.PoolPay]'s payment when it comes from SEND). */
    data object PoolCreate : Screen
    data class PoolJoin(val poolId: String) : Screen
    /** A payment from SEND, to be paid through a new pool: its recipients. */
    data object PoolPay : Screen
}

sealed interface ScanPhase {
    data object Idle : ScanPhase
    data class Scanning(val path: String, val chainIndex: Int, val gap: Int, val gapLimit: Int, val found: Int) : ScanPhase
    data object Done : ScanPhase
    data class Error(val message: String) : ScanPhase
}

sealed interface SendPhase {
    data object Editing : SendPhase
    data object Preparing : SendPhase
    data class Review(val draft: SendDraft, val concern: String?) : SendPhase
    data object Signing : SendPhase
    /** [replaced]/[cancelled]: it was a speed-up or a cancellation of an earlier send. */
    data class Sent(val txid: String, val replaced: Boolean = false, val cancelled: Boolean = false) : SendPhase
    data class Failed(val message: String) : SendPhase
}

data class UiState(
    val screen: Screen = Screen.Welcome,
    val kind: WalletKind? = null,
    val zpub: String? = null,
    val endpoint: NodeEndpoint = NodeEndpoint.DEFAULT,
    val pinned: String? = null,
    val scan: ScanPhase = ScanPhase.Idle,
    val refreshing: Boolean = false,
    val rows: List<AddressRow> = emptyList(),
    val txs: List<TxConf> = emptyList(),
    val height: Int = 0,
    val serverVersion: String? = null,
    /** The server showed a certificate other than the pinned one: its fingerprint, for the user to check. */
    val certChanged: String? = null,
    val send: SendPhase = SendPhase.Editing,
    val suggestedRate: Double? = null,
    val spendable: List<SpendableUtxo>? = null,
    val message: String? = null,
    /** The currency values are shown in ([Fiat.OFF]: none, and the price feed is never asked). */
    val fiat: String = "USD",
    val market: MarketData? = null,
) {
    /** One XBT in [fiat], or null (off, or no price yet). */
    val perCoin: Double? get() = if (fiat == Fiat.OFF) null else market?.xbtIn(fiat)

    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    val scriptType: ScriptType get() = zpub?.let { runCatching { Bip32.parseExtendedPubKey(it).scriptType }.getOrNull() } ?: ScriptType.P2WPKH
}

/**
 * The app's state and actions — a trimmed port of the Android app's `ui/WalletViewModel.kt` to
 * what iOS 0.1 does: one wallet (hot from words, or watch-only from a zpub), the BLAKE2b chain,
 * Electrum only.
 */
class WalletModel(private val platform: AppPlatform) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = Scanner(platform.transports)
    private val prefs = platform.prefs
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var scanJob: Job? = null

    /** Kilojoin: the pools and this wallet's rounds in them. */
    val pools = PoolsModel(platform, { _state.value },
        gatherCoins = { val s = _state.value; scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned) },
        verifyCoins = { coins -> val s = _state.value; scanner.verifyCoins(s.zpub ?: error("No wallet."), coins, s.endpoint, s.pinned) },
        onRoundDone = { refresh() })

    private companion object {
        const val K_KIND = "wallet.kind"
        const val K_ZPUB = "wallet.zpub"
        const val K_HOST = "server.host"
        const val K_PORT = "server.port"
        const val K_FIAT = "fiat"
        const val K_MARKET = "market.json"
        const val K_MARKET_AT = "market.fetched"
        /** Screenshot mode's price: no network, the same figures every run. */
        const val DEMO_MARKET = """{"xbtUsd":360.0,"xbtEur":321.5,"fiatPerUsd":{"EUR":0.893,"GBP":0.748,"JPY":156.8}}"""
        fun pinKey(e: NodeEndpoint) = "pin.${e.host}:${e.port}"
    }

    init {
        val host = prefs.get(K_HOST)
        val port = prefs.get(K_PORT)?.toIntOrNull()
        val endpoint = if (host != null && port != null) NodeEndpoint(host, port, true) else NodeEndpoint.DEFAULT
        val kind = prefs.get(K_KIND)?.let { k -> WalletKind.entries.firstOrNull { it.name == k } }
        val zpub = prefs.get(K_ZPUB)
        _state.value = UiState(
            screen = if (kind != null && zpub != null) Screen.Home else Screen.Welcome,
            kind = if (zpub != null) kind else null, zpub = zpub, endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
            fiat = prefs.get(K_FIAT) ?: "USD",
            market = prefs.get(K_MARKET)?.let { j -> runCatching { MarketFeed.parse(j, prefs.get(K_MARKET_AT)?.toLongOrNull() ?: 0L) }.getOrNull() },
        )
        if (kind != null && zpub != null) scan()
        refreshPrice()
        if (platform.demoScreen == null) pools.attach()
    }

    // ------------------------------------------------------------------ price (Android 1.2.3: 36 currencies)

    private var priceTriedMs = 0L

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /**
     * The XBT price from mempool.kilombino.com, at most once per the feed's poll interval (5 min),
     * and after a failure not again for 10 minutes. Nothing is asked while prices are off. The
     * request carries nothing about the wallet: it is the same for everyone.
     */
    fun refreshPrice(force: Boolean = false) {
        val s = _state.value
        if (s.fiat == Fiat.OFF) return
        if (platform.demoScreen != null) {
            _state.update { it.copy(market = MarketFeed.parse(DEMO_MARKET, 0L)) }
            return
        }
        val now = nowMs()
        val m = s.market
        if (!force && m != null && now - m.fetchedMs < m.pollMinutes * 60_000L) return
        if (now - priceTriedMs < (if (force) 60_000L else 10 * 60_000L)) return
        priceTriedMs = now
        scope.launch {
            val body = suspendCancellableCoroutine<String?> { c -> platform.httpGet(MarketFeed.API) { c.resume(it) } } ?: return@launch
            val parsed = runCatching { MarketFeed.parse(body, now) }.getOrNull() ?: return@launch
            if (parsed.xbtUsd == null) return@launch
            prefs.put(K_MARKET, body); prefs.put(K_MARKET_AT, now.toString())
            _state.update { it.copy(market = parsed) }
        }
    }

    /** [code]: a [Fiat] currency, or [Fiat.OFF]. One choice for the whole app. */
    fun setFiat(code: String) {
        prefs.put(K_FIAT, code)
        _state.update { it.copy(fiat = code) }
        refreshPrice()
    }

    fun go(screen: Screen) {
        _state.update { it.copy(screen = screen, message = null) }
        if (screen == Screen.Send) openSend()
        if (screen == Screen.Pools && platform.demoScreen == null) pools.attach()
        if (screen is Screen.Replace) {
            _state.update { it.copy(send = SendPhase.Editing) }
            val s = _state.value
            if (platform.demoScreen == null) scope.launch {
                val rate = scanner.suggestedFeeRate(s.endpoint, s.pinned)
                _state.update { it.copy(suggestedRate = rate) }
            }
        }
    }

    fun back() {
        val s = _state.value
        go(when {
            s.kind == null -> Screen.Welcome
            s.screen == Screen.PoolCreate || s.screen is Screen.PoolJoin -> Screen.Pools
            s.screen == Screen.PoolPay -> Screen.Send
            else -> Screen.Home
        })
    }

    /** Screenshot mode (a throwaway demo wallet, nothing sent anywhere). */
    val isDemo: Boolean get() = platform.demoScreen != null
    val unlockName: String get() = platform.unlockName

    fun dismissMessage() = _state.update { it.copy(message = null) }
    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    // ------------------------------------------------------------------ setting up a wallet

    fun startCreate(words: Int) = go(Screen.CreateShow(Bip39.generate(words)))

    fun wroteDown(words: List<String>) {
        val positions = words.indices.shuffled().take(3).sorted()
        go(Screen.CreateConfirm(words, positions))
    }

    /** The user typed back the words at [positions]: store the seed and open the wallet. */
    fun confirmCreate(words: List<String>, answers: Map<Int, String>): Boolean {
        val ok = answers.all { (i, w) -> words[i] == w.trim().lowercase() }
        if (!ok) { say("That is not the word written at that position. Check your paper and try again."); return false }
        storeHot(words, "")
        return true
    }

    fun restore(words: List<String>, passphrase: String) {
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (clean.size != 12 && clean.size != 24) { say("Enter 12 or 24 words."); return }
        clean.firstOrNull { !Bip39.isWord(it) }?.let { say("\"$it\" is not a recovery word."); return }
        if (!Bip39.isValid(clean)) { say("These words do not form a valid backup (the checksum does not match). Check their order."); return }
        storeHot(clean, passphrase)
    }

    private fun storeHot(words: List<String>, passphrase: String) {
        scope.launch {
            val zpub = withContext(Dispatchers.Default) { Signer.accountZpub(words, passphrase) }
            val err = suspendCancellableCoroutine { c -> platform.seeds.save(encodeSecret(words, passphrase)) { c.resume(it) } }
            if (err != null) { say("Could not store the words: $err"); return@launch }
            openWallet(WalletKind.HOT, zpub)
        }
    }

    /**
     * Watch [xpub]. A plain xpub doesn't say which addresses it makes, so [type] is the one picked
     * on import (Android 1.2.4), and the key is kept under that type's prefix (ypub/zpub; the
     * same key). The import screen already warned when it is not an account key.
     */
    fun watch(xpub: String, type: ScriptType? = null) {
        val raw = xpub.filterNot { it.isWhitespace() }
        val parsed = runCatching { Bip32.parseExtendedPubKey(raw) }.getOrElse { say(it.message ?: "Not a valid key."); return }
        val key = if (parsed.scriptType == ScriptType.P2PKH && type != null && type != ScriptType.P2PKH)
            Bip32.withScriptType(raw, type) else raw
        openWallet(WalletKind.WATCH, key)
    }

    private fun openWallet(kind: WalletKind, zpub: String) {
        prefs.put(K_KIND, kind.name)
        prefs.put(K_ZPUB, zpub)
        _state.update {
            it.copy(kind = kind, zpub = zpub, rows = emptyList(), txs = emptyList(), screen = Screen.Home, send = SendPhase.Editing)
        }
        scan()
        pools.attach()
    }

    fun deleteWallet() {
        scanJob?.cancel()
        pools.detach()
        platform.seeds.delete()
        prefs.put(K_KIND, null); prefs.put(K_ZPUB, null)
        _state.update { UiState(endpoint = it.endpoint, pinned = it.pinned) }
    }

    /** Show the recovery words after Face ID / Touch ID / passcode. */
    fun revealWords() {
        platform.seeds.load(I18n.tr("Show your recovery words")) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            go(Screen.ShowWords(decodeSecret(secret).first))
        }
    }

    // ------------------------------------------------------------------ scanning

    fun scan() {
        val s = _state.value
        val zpub = s.zpub ?: return
        scanJob?.cancel()
        scanJob = scope.launch {
            _state.update { it.copy(scan = ScanPhase.Scanning("", 0, 0, 20, 0), certChanged = null) }
            var found = 0
            scanner.scan(zpub, s.endpoint, s.pinned).collect { ev ->
                when (ev) {
                    is ScanEvent.Connecting -> Unit
                    is ScanEvent.Connected -> _state.update { it.copy(height = ev.height, serverVersion = ev.server) }
                    is ScanEvent.Deriving -> _state.update {
                        val p = it.scan as? ScanPhase.Scanning
                        it.copy(scan = ScanPhase.Scanning(ev.path, ev.chainIndex, p?.gap ?: 0, p?.gapLimit ?: 20, found))
                    }
                    is ScanEvent.Found -> { found++; _state.update { st -> st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = 0, found = found) ?: st.scan) } }
                    is ScanEvent.GapProgress -> _state.update { st ->
                        st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = ev.consecutiveEmpty, gapLimit = ev.gapLimit) ?: st.scan)
                    }
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        if (!sameWallet(s)) return@collect
                        _state.update { it.copy(scan = ScanPhase.Done, rows = ev.rows, txs = ev.txs, height = ev.height) }
                        if (platform.demoScreen?.substringBefore('@') == "home") platform.markReady("home")
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(scan = ScanPhase.Error(ev.message)) }
                    is ScanEvent.CertificateChanged -> _state.update { it.copy(scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint) }
                }
            }
            if (platform.demoScreen?.substringBefore('@') == "home" && _state.value.scan is ScanPhase.Error) platform.markReady("home")
        }
    }

    /**
     * Pull-to-refresh and the minute timer: the same gap-limit walk, but quiet (the balance stays on
     * screen) — so a payment to the next unused address is found too, not only to known ones.
     */
    fun refresh() {
        refreshPrice()
        val s = _state.value
        if (s.scan is ScanPhase.Scanning || s.refreshing) return
        if (s.rows.isEmpty() && s.scan !is ScanPhase.Done) { scan(); return }
        val zpub = s.zpub ?: return
        scanJob = scope.launch {
            _state.update { it.copy(refreshing = true) }
            scanner.scan(zpub, s.endpoint, s.pinned).collect { ev ->
                when (ev) {
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        // Filed under the wallet and server it started for (Android 1.2.6): one
                        // removed or replaced meanwhile must not get the other's figures.
                        if (!sameWallet(s)) { _state.update { it.copy(refreshing = false) }; return@collect }
                        _state.update { it.copy(rows = ev.rows, txs = ev.txs, height = ev.height, refreshing = false, scan = ScanPhase.Done) }
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(refreshing = false, message = ev.message) }
                    is ScanEvent.CertificateChanged -> _state.update {
                        it.copy(refreshing = false, scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint)
                    }
                    else -> Unit
                }
            }
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Whether the wallet and server on screen are still those of [started]. */
    private fun sameWallet(started: UiState): Boolean =
        _state.value.zpub == started.zpub && _state.value.endpoint == started.endpoint

    private fun pinIfFirst(endpoint: NodeEndpoint, fp: String?) {
        if (fp == null || prefs.get(pinKey(endpoint)) != null) return
        prefs.put(pinKey(endpoint), fp)
        _state.update { it.copy(pinned = fp) }
    }

    /** Leave the old pin in place (nothing is sent to the server until the user decides). */
    fun dismissCertificate() = _state.update { it.copy(certChanged = null) }

    /** The user checked the new fingerprint and trusts it. */
    fun trustNewCertificate() {
        val s = _state.value
        val fp = s.certChanged ?: return
        prefs.put(pinKey(s.endpoint), fp)
        _state.update { it.copy(pinned = fp, certChanged = null) }
        scan()
    }

    fun setServer(host: String, port: Int?) {
        val e = if (host.isBlank() || port == null) NodeEndpoint.DEFAULT
                else NodeEndpoint(host.trim(), port, !(host.trim() == NodeEndpoint.DEFAULT.host && port == NodeEndpoint.DEFAULT.port))
        prefs.put(K_HOST, if (e.isCustom) e.host else null)
        prefs.put(K_PORT, if (e.isCustom) e.port.toString() else null)
        _state.update { it.copy(endpoint = e, pinned = prefs.get(pinKey(e)), rows = emptyList(), txs = emptyList()) }
        if (_state.value.zpub != null) scan()
    }

    // ------------------------------------------------------------------ receive

    /** The next unused receive index: one past the highest used (Android: `nextReceiveIndex`). */
    fun nextReceiveIndex(): Int = _state.value.rows.filter { it.chainIndex == 0 && it.isUsed }.maxOfOrNull { it.index }?.plus(1) ?: 0

    /**
     * Receive indexes that hold nothing and never did (Android: `unusedReceiveIndexes`): the ones
     * skipped below the last used address (handed out and never paid) and [ahead] past the next
     * unused one, in order. iOS has one wallet and no coinjoin, so no seat is left out.
     */
    fun unusedReceiveIndexes(ahead: Int = 20): List<Int> {
        val used = _state.value.rows.filter { it.chainIndex == 0 && it.isUsed }.map { it.index }.toSet()
        // An address a pool's round counts on (its equal output) is not handed out here.
        val seats = pools.reservedScripts()
        val zpub = _state.value.zpub
        return (0 until nextReceiveIndex() + ahead).filter { i ->
            i !in used && (seats.isEmpty() || zpub == null || runCatching {
                val p = Bip32.parseExtendedPubKey(zpub)
                Address.scriptPubKey(Bip32.derivePath(p, 0, i).pubkey(), p.scriptType).toList() !in seats
            }.getOrDefault(true))
        }
    }

    /** The receive address at [index] (default: the next unused) and its derivation path. */
    fun receiveAddress(index: Int = unusedReceiveIndexes().firstOrNull { it >= nextReceiveIndex() } ?: nextReceiveIndex()): Pair<String, String>? {
        val zpub = _state.value.zpub ?: return null
        return runCatching {
            val parsed = Bip32.parseExtendedPubKey(zpub)
            val pub = Bip32.derivePath(parsed, 0, index).pubkey()
            Address.encode(pub, parsed.scriptType) to "m/${Scanner.purposeFor(parsed.scriptType)}'/0'/0'/0/$index"
        }.getOrNull()
    }

    // ------------------------------------------------------------------ send

    private fun openSend() {
        val s = _state.value
        _state.update { it.copy(send = SendPhase.Editing, spendable = null) }
        scope.launch {
            val rate = scanner.suggestedFeeRate(s.endpoint, s.pinned)
            _state.update { it.copy(suggestedRate = rate) }
        }
        scope.launch {
            runCatching { scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned) }
                // Coins of a pool round being built are held for it; a pool still waiting lets them go.
                .onSuccess { u -> val locked = pools.lockedOutpoints(); _state.update { it.copy(spendable = u.filter { c -> "${c.txid}:${c.vout}" !in locked }) } }
        }
    }

    fun maxSendable(rate: Double): Long = SendPlanner.maxSendable(rate, _state.value.spendable ?: emptyList())

    /** Choose coins and the fee and show the review; nothing secret is touched yet. */
    fun prepareSend(to: String, amountSats: Long, rate: Double, sendAll: Boolean) {
        val s = _state.value
        val zpub = s.zpub ?: return
        _state.update { it.copy(send = SendPhase.Preparing) }
        scope.launch {
            runCatching {
                val locked = pools.lockedOutpoints()
                val utxos = (s.spendable ?: scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned))
                    .filter { "${it.txid}:${it.vout}" !in locked }
                val draft = withContext(Dispatchers.Default) {
                    SendPlanner.plan(to, amountSats, rate, utxos, freshChange(s), sendAll)
                }
                scanner.verifyCoins(zpub, draft.inputs, s.endpoint, s.pinned)
                draft
            }.onSuccess { d -> _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the send.")) } }
        }
    }

    /** Unlock the words, sign with the unified sighash (0x21) and broadcast. */
    fun confirmSend() {
        val s = _state.value
        val draft = (s.send as? SendPhase.Review)?.draft ?: return
        val zpub = s.zpub ?: return
        val reason = when {
            draft.cancels -> I18n.tr("Unlock to sign the cancellation")
            draft.replaces != null -> I18n.tr("Unlock to sign the speed-up")
            else -> I18n.tr("Sign and send ${com.kilombino.kilowallet.core.wallet.formatSats(draft.amount)} sats")
        }
        platform.seeds.load(reason) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: nothing was sent."); return@load }
            _state.update { it.copy(send = SendPhase.Signing) }
            scope.launch {
                runCatching {
                    val (words, pass) = decodeSecret(secret)
                    val signed = withContext(Dispatchers.Default) { Signer.sign(draft, words, pass, zpub) }
                    scanner.broadcast(signed.rawHex, s.endpoint, s.pinned)
                }.onSuccess { txid ->
                    // A coin seated in a pool still waiting for people was spent: that seat is given up.
                    pools.leaveFor(draft.inputs.map { "${it.txid}:${it.vout}" })
                    _state.update { it.copy(send = SendPhase.Sent(txid, replaced = draft.replaces != null, cancelled = draft.cancels)) }
                    refresh()
                }.onFailure { e ->
                    _state.update { it.copy(send = SendPhase.Failed(e.message ?: "The broadcast failed.")) }
                }
            }
        }
    }

    fun editSend() = _state.update { it.copy(send = SendPhase.Editing) }

    // ------------------------------------------------------------------ speed up / cancel (RBF)

    /** The next unused change address's script (one past the highest change address seen, none a pool's seat holds). */
    private fun freshChange(s: UiState): ByteArray {
        val zpub = s.zpub ?: error("No wallet.")
        val held = pools.reservedScripts()
        var index = s.rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
        while (SendPlanner.changeScript(zpub, index).toList() in held) index++
        return SendPlanner.changeScript(zpub, index)
    }

    /** Open a pool for the payment typed in SEND (1 to 5 recipients). */
    fun payThroughPool(recipients: List<Pair<String, Long>>, rate: Double) {
        pools.startPayment(PoolPayRequest(recipients, rate))
        go(Screen.PoolPay)
    }

    /**
     * Draft a replacement of unconfirmed send [txid] (Android: `prepareBump` / `prepareCancel`):
     * the same coins, at [rate] or the least the network takes for a replacement; a speed-up keeps
     * the payment and takes the extra fee from the change, a cancellation sends everything back
     * to a fresh address of this wallet. The review and the signing are a normal send's.
     */
    fun prepareReplace(txid: String, cancel: Boolean, rate: Double) {
        val s = _state.value
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT) return
        _state.update { it.copy(send = SendPhase.Preparing) }
        scope.launch {
            runCatching {
                val (tx, coins) = scanner.replacementCoins(txid, s.rows, s.endpoint, s.pinned)
                withContext(Dispatchers.Default) {
                    if (cancel) ReplacePlanner.cancel(txid, tx, coins, freshChange(s), rate)
                    else {
                        val changeHashes = s.rows.filter { it.chainIndex == 1 }.map { it.scriptHash }.toSet()
                        ReplacePlanner.bump(txid, tx, coins, { spk ->
                            com.kilombino.kilowallet.core.crypto.Address.electrumScriptHash(spk) in changeHashes
                        }, rate)
                    }
                }.also { scanner.verifyCoins(zpub, it.inputs, s.endpoint, s.pinned) }
            }.onSuccess { d -> _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e ->
                 val fallback = if (cancel) "Could not prepare the cancellation." else "Could not prepare the replacement."
                 _state.update { it.copy(send = SendPhase.Failed(e.message ?: fallback)) }
             }
        }
    }

    // ------------------------------------------------------------------ demo (CI screenshots only)

    /** Throwaway state for the screenshot run in CI: the well-known "abandon … about" test wallet. */
    fun setUpDemo(screen: String) {
        val words = List(11) { "abandon" } + "about"
        when (screen) {
            "welcome" -> platform.markReady(screen)
            "create" -> { go(Screen.CreateShow(Bip39.generate(12))); platform.markReady(screen) }
            "restore" -> { go(Screen.Restore); platform.markReady(screen) }
            "watch" -> { go(Screen.Watch); platform.markReady(screen) }
            else -> scope.launch {
                val zpub = withContext(Dispatchers.Default) { Signer.accountZpub(words, "") }
                _state.update { it.copy(kind = WalletKind.HOT, zpub = zpub, screen = Screen.Home) }
                when (screen) {
                    "home" -> scan()
                    "receive" -> { go(Screen.Receive); platform.markReady(screen) }
                    "settings" -> { go(Screen.Settings); platform.markReady(screen) }
                    "replace" -> {
                        // A made-up pending send, only so the cancel screen has something to show.
                        go(Screen.Replace("4f".repeat(32), cancel = true)); platform.markReady(screen)
                    }
                    "send" -> { go(Screen.Send); platform.markReady(screen) }
                    "pools", "pool-round", "pool-intro" -> {
                        withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }
                        if (screen == "pool-intro") pools.setEnabled(false)
                        go(Screen.Pools); platform.markReady(screen)
                    }
                    "pool-create" -> { withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }; go(Screen.PoolCreate); platform.markReady(screen) }
                    "pool-join" -> {
                        withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }
                        pools.state.value.open?.firstOrNull()?.let { go(Screen.PoolJoin(it.id)) }; platform.markReady(screen)
                    }
                    "pool-pay" -> { withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }; go(Screen.PoolPay); platform.markReady(screen) }
                    "review" -> {
                        // A made-up coin, only so the review screen has something to show.
                        val coin = SpendableUtxo("4f".repeat(32), 0, 250_000, 0, 0, 970_000)
                        val draft = SendPlanner.plan("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 120_000, 2.0,
                            listOf(coin), SendPlanner.changeScript(zpub, 0))
                        _state.update { it.copy(screen = Screen.Send, send = SendPhase.Review(draft, SendPlanner.feeConcern(draft))) }
                        platform.markReady(screen)
                    }
                }
            }
        }
    }
}

/** Words and passphrase in one Keychain item: "word word …\npassphrase". */
internal fun encodeSecret(words: List<String>, passphrase: String) = words.joinToString(" ") + "\n" + passphrase
internal fun decodeSecret(s: String): Pair<List<String>, String> {
    val nl = s.indexOf('\n')
    val w = if (nl < 0) s else s.substring(0, nl)
    return w.split(' ').filter { it.isNotEmpty() } to (if (nl < 0) "" else s.substring(nl + 1))
}

/** A `bitcoin:` URI (BIP-21) or a bare address: (address, amount in sats or null). */
fun parsePaymentText(text: String): Pair<String, Long?> {
    val t = text.trim()
    if (!t.lowercase().startsWith("bitcoin:")) return t to null
    val rest = t.substring(8)
    val addr = rest.substringBefore('?')
    val amount = rest.substringAfter('?', "").split('&').map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0].lowercase() == "amount" }?.get(1)
        ?.let { btcToSats(it) }
    return addr to amount
}

/** "0.0012" BTC → 120000 sats, exactly (no floating point); null when malformed. */
fun btcToSats(s: String): Long? {
    val t = s.trim()
    if (t.isEmpty() || t.count { it == '.' } > 1 || !t.all { it.isDigit() || it == '.' }) return null
    val whole = t.substringBefore('.').ifEmpty { "0" }
    val frac = t.substringAfter('.', "")
    if (frac.length > 8 || whole.length > 8) return null
    return whole.toLong() * 100_000_000L + frac.padEnd(8, '0').toLong()
}
