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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class WalletKind { HOT, WATCH }

/** The main screen's tabs, as on Android: BTC · LIGHTNING · KILOJOIN (Android: COINJOIN) · ARK. */
enum class Tab { BTC, LIGHTNING, POOLS, ARK }

sealed interface Screen {
    data object Welcome : Screen
    data class CreateShow(val words: List<String>) : Screen
    data class CreateConfirm(val words: List<String>, val positions: List<Int>) : Screen
    data object Restore : Screen
    /** Make the words from physical dice (SeedSigner's way): 50 rolls for 12 words, 99 for 24. */
    data object Dice : Screen
    /** A backup file (.kab) picked to restore from: ask its password. */
    data class RestoreFile(val bytes: ByteArray) : Screen
    /** Write a backup file (.kab) of this wallet: a password, then where to save it. */
    data object Backup : Screen
    data object Watch : Screen
    data object Home : Screen
    data object Receive : Screen
    data object Send : Screen
    data object Settings : Screen
    /** Speed up ([cancel] false) or cancel an unconfirmed send of this wallet by replacing it (RBF). */
    data class Replace(val txid: String, val cancel: Boolean) : Screen
    /** Speed up an incoming transaction that is stuck by spending our output of it (child pays for parent). */
    data class Cpfp(val txid: String) : Screen
    data class ShowWords(val words: List<String>) : Screen
    /** KILOJOIN: open pools, your rounds, and how it works. */
    data object Pools : Screen
    /** Open a pool of your own (with [Screen.PoolPay]'s payment when it comes from SEND). */
    data object PoolCreate : Screen
    data class PoolJoin(val poolId: String) : Screen
    /** A payment from SEND, to be paid through a new pool: its recipients. */
    data object PoolPay : Screen
    /** The end of Tor's log, to read and copy (Android: SHOW TOR'S LOG). */
    data object TorLog : Screen
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
    /**
     * Watch-only: the transaction waits for a signer elsewhere (SeedSigner, Sparrow…). [exported] is
     * the PSBT handed out, kept to check that what comes back is the very same transaction.
     */
    class Psbt(val draft: SendDraft, val exported: ByteArray, val coins: List<com.kilombino.kilowallet.core.crypto.Psbt.Coin>) : SendPhase
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
    /** Which tab the main screen shows. */
    val tab: Tab = Tab.BTC,
    /** Simple mode (Android's "simple"): the balance and RECEIVE / SEND, nothing else. */
    val simple: Boolean = false,
    /** Balances and amounts masked (Android's street mode eye). */
    val hidden: Boolean = false,
    /** Street mode: masked every time the app opens. */
    val streetMode: Boolean = false,
    /** Locked with ⏻: nothing is shown until Face ID / Touch ID / the passcode opens it again. */
    val locked: Boolean = false,
    /** Seconds between refreshes while the app is open; 0: by hand only. */
    val refreshSeconds: Int = 300,
    /** When the next refresh is due (epoch ms), for the countdown on the balance. */
    val nextRefreshAt: Long = 0L,
    /** Empty addresses in a row checked before a scan stops. */
    val gapLimit: Int = 20,
    /** The hot wallet's account (its zpub), when there is one (Android: both wallets side by side). */
    val hotZpub: String? = null,
    /** The watch-only key, when there is one, and its type when the key's prefix does not say it (Taproot). */
    val watchKey: String? = null,
    val watchType: ScriptType? = null,
    /** Hot wallet: its Taproot account (BIP-86), derived with the words; scanned and spent once set. */
    val taprootXpub: String? = null,
    /** Hot wallet: new receive and change addresses are Taproot (bc1p) instead of native SegWit. */
    val receiveTaproot: Boolean = false,
) {
    /** One XBT in [fiat], or null (off, or no price yet). */
    val perCoin: Double? get() = if (fiat == Fiat.OFF) null else market?.xbtIn(fiat)

    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    val scriptType: ScriptType get() = (if (kind == WalletKind.WATCH) watchType else null)
        ?: zpub?.let { runCatching { Bip32.parseExtendedPubKey(it).scriptType }.getOrNull() } ?: ScriptType.P2WPKH
    /** The type a scan reads the active key as (only a Taproot watch-only key needs it said). */
    val keyType: ScriptType? get() = if (kind == WalletKind.WATCH) watchType else null
    /** The Taproot account scanned and spent with the hot wallet's (null for the watch-only one). */
    val activeTaproot: String? get() = if (kind == WalletKind.HOT) taprootXpub else null
    /** Both wallets exist, so the switch moves between them. */
    val hasBoth: Boolean get() = hotZpub != null && watchKey != null
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
        // Native SegWit coins (type null) and the Taproot account's: a pool takes coins of its own type only.
        gatherCoins = { val s = _state.value; scanner.gatherUtxos(s.rows.filter { it.isUsed && (it.type == null || it.type == ScriptType.P2TR) }, s.endpoint, s.pinned) },
        verifyCoins = { coins -> val s = _state.value; scanner.verifyCoins(s.zpub ?: error("No wallet."), coins, s.endpoint, s.pinned, taprootXpub = s.taprootXpub) },
        onRoundDone = { refresh() })

    /** The wallet's mined rewards still locked (shown under the balance), or null before they were looked up. */
    private val _immature = MutableStateFlow<Scanner.Immature?>(null)
    val immature: StateFlow<Scanner.Immature?> = _immature
    private var immatureAsked: String? = null

    /**
     * Looks up the mined rewards that can't be spent yet (what SEND leaves out), again only when the
     * wallet, its balance or the tip changed (Android 1.4.0 `refreshImmature`).
     */
    fun refreshImmature() {
        val s = _state.value
        val rows = s.rows.filter { it.isUsed }
        if (rows.isEmpty() || s.height <= 0) return
        val key = "${s.zpub}|${s.activeTaproot}|${s.height}|${s.total}|${rows.size}"
        if (immatureAsked == key) return
        immatureAsked = key
        if (platform.demoScreen?.substringBefore('@') == "immature") {
            // Screenshot mode: two made-up rewards mined from the long-maturity block, so the note shows.
            val a = SpendableUtxo("c0".repeat(32), 0, 312_500_000, 0, 3, s.height - 1200)
            val b = SpendableUtxo("c1".repeat(32), 0, 312_480_000, 0, 5, s.height - 40)
            _immature.value = Scanner.Immature(s.height, listOf(a, b).map { Scanner.ImmatureCoin(it, Scanner.maturesAt(it.height)) }, 540.0)
            return
        }
        scope.launch {
            runCatching { scanner.immature(rows, s.endpoint, s.pinned) }
                .onSuccess { im -> if (sameWallet(s)) _immature.value = im }
                .onFailure { immatureAsked = null }
        }
    }

    /**
     * The channels a channel.backup holds, read with the node's keys from the recovery words (one
     * unlock): what restoring it will close. A backup of other words is refused ([Scb.WrongSeed]).
     */
    fun readChannelBackup(file: ByteArray, done: (Result<List<com.kilombino.kilowallet.core.ln.Scb.Channel>>) -> Unit) {
        platform.seeds.load(I18n.tr("Read the channel backup")) { secret, error ->
            if (secret == null) { done(Result.failure(IllegalStateException(error ?: "Not unlocked."))); return@load }
            scope.launch {
                done(runCatching {
                    withContext(Dispatchers.Default) {
                        val (words, pass) = decodeSecret(secret)
                        val seed = Bip39.toSeed(words, pass)
                        val root = com.kilombino.kilowallet.core.ln.LnSeed.root(seed)
                        seed.fill(0)
                        try { com.kilombino.kilowallet.core.ln.Scb.decode(file, root) } finally { root.wipe() }
                    }
                })
            }
        }
    }

    /** Lightning: the node on this phone (LND linked in), ported from Android's ln/Lightning.kt. */
    val lightning = LightningModel(platform)
    val ark = ArkModel(platform)

    private companion object {
        // Before 1.0 one wallet at a time (kind + key); read once and moved to the keys below.
        const val K_KIND = "wallet.kind"
        const val K_ZPUB = "wallet.zpub"
        const val K_HOT = "wallet.hot.zpub"
        const val K_WATCH = "wallet.watch.key"
        const val K_WATCH_TYPE = "wallet.watch.type"
        const val K_TPUB = "wallet.hot.tpub"
        const val K_RECV_TR = "wallet.hot.taproot"
        const val K_ACTIVE = "wallet.active"
        const val K_HOST = "server.host"
        const val K_PORT = "server.port"
        const val K_FIAT = "fiat"
        const val K_SIMPLE = "ui.simple"
        const val K_STREET = "ui.street"
        const val K_REFRESH = "refresh.seconds"
        const val K_GAP = "scan.gap"
        const val K_TOR_ONLY = "tor.only"
        const val K_TOR_ELECTRUM = "tor.electrum"
        const val K_MARKET = "market.json"
        const val K_MARKET_AT = "market.fetched"
        /** Screenshot mode's price: no network, the same figures every run. */
        const val DEMO_MARKET = """{"xbtUsd":360.0,"xbtEur":321.5,"fiatPerUsd":{"EUR":0.893,"GBP":0.748,"JPY":156.8}}"""
        fun pinKey(e: NodeEndpoint) = "pin.${e.host}:${e.port}"
    }

    init {
        // Tor's two settings (Android: "Only over Tor" on by default, Electrum through Tor off).
        com.kilombino.kilowallet.core.tor.Tor.only = prefs.get(K_TOR_ONLY) != "0"
        com.kilombino.kilowallet.core.tor.Tor.electrum = prefs.get(K_TOR_ELECTRUM) == "1"
        // Back from the background: Tor's port may be gone (iOS suspends the app); it comes back
        // before the node and the pools need it again.
        platform.onForeground { scope.launch { runCatching { com.kilombino.kilowallet.core.tor.Tor.onForeground() } } }
        val host = prefs.get(K_HOST)
        val port = prefs.get(K_PORT)?.toIntOrNull()
        val endpoint = if (host != null && port != null) NodeEndpoint(host, port, true) else NodeEndpoint.DEFAULT
        // The one wallet of an older version becomes the hot or the watch-only one.
        prefs.get(K_ZPUB)?.let { old ->
            if (prefs.get(K_KIND) == WalletKind.WATCH.name) prefs.put(K_WATCH, old) else prefs.put(K_HOT, old)
            prefs.put(K_ACTIVE, prefs.get(K_KIND)); prefs.put(K_KIND, null); prefs.put(K_ZPUB, null)
        }
        val hot = prefs.get(K_HOT)
        val watchKey = prefs.get(K_WATCH)
        val watchType = prefs.get(K_WATCH_TYPE)?.let { t -> ScriptType.entries.firstOrNull { it.name == t } }
        val kind = when {
            prefs.get(K_ACTIVE) == WalletKind.WATCH.name && watchKey != null -> WalletKind.WATCH
            hot != null -> WalletKind.HOT
            watchKey != null -> WalletKind.WATCH
            else -> null
        }
        val zpub = if (kind == WalletKind.HOT) hot else if (kind == WalletKind.WATCH) watchKey else null
        _state.value = UiState(
            screen = if (kind != null && zpub != null) Screen.Home else Screen.Welcome,
            kind = kind, zpub = zpub, endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
            hotZpub = hot, watchKey = watchKey, watchType = watchType,
            taprootXpub = prefs.get(K_TPUB), receiveTaproot = prefs.get(K_RECV_TR) == "1",
            fiat = prefs.get(K_FIAT) ?: "USD",
            simple = prefs.get(K_SIMPLE) == "1",
            streetMode = prefs.get(K_STREET) == "1", hidden = prefs.get(K_STREET) == "1",
            refreshSeconds = prefs.get(K_REFRESH)?.toIntOrNull() ?: 300,
            gapLimit = prefs.get(K_GAP)?.toIntOrNull()?.coerceIn(5, 200) ?: 20,
            market = prefs.get(K_MARKET)?.let { j -> runCatching { MarketFeed.parse(j, prefs.get(K_MARKET_AT)?.toLongOrNull() ?: 0L) }.getOrNull() },
        )
        if (kind != null && zpub != null) scan()
        refreshPrice()
        if (platform.demoScreen == null) pools.attach()
        if (platform.demoScreen == null && kind == WalletKind.HOT) lightning.resume()
        if (platform.demoScreen == null && kind == WalletKind.HOT) ark.resume()
    }

    // ------------------------------------------------------------------ Lightning

    /**
     * Turns Lightning on: the words are unlocked once (Face ID), the node's wallet is made from
     * them and they never reach it otherwise.
     */
    fun enableLightning() {
        if (!lightning.available) { say("This build has no Lightning engine."); return }
        platform.seeds.load(I18n.tr("Turn on Lightning")) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: Lightning stays off."); return@load }
            scope.launch {
                runCatching {
                    val (words, pass) = decodeSecret(secret)
                    val seed = withContext(Dispatchers.Default) { Bip39.toSeed(words, pass) }
                    lightning.chainTip = _state.value.height
                    withContext(Dispatchers.Default) { lightning.enable(seed) }
                }.onFailure { say(it.message ?: "Lightning did not start.") }
            }
        }
    }

    /**
     * Unlocks the hot wallet's words once (Face ID / passcode) and hands them to [use] with their
     * passphrase, for Ark: its own words are made from them, and restoring compares against them.
     */
    fun withWords(reason: String, use: (List<String>, String) -> Unit) {
        platform.seeds.load(I18n.tr(reason)) { secret, error ->
            if (secret == null) { ark.say("Error: " + (error ?: "not unlocked")); return@load }
            runCatching { decodeSecret(secret) }.onSuccess { (w, p) -> use(w, p) }.onFailure { ark.say("Error: " + (it.message ?: "$it")) }
        }
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
        // KILOJOIN is a tab of the main screen, as on Android.
        if (screen == Screen.Pools) {
            _state.update { it.copy(screen = Screen.Home, tab = Tab.POOLS, message = null) }
            if (platform.demoScreen == null) pools.attach()
            return
        }
        _state.update { it.copy(screen = screen, message = null) }
        if (screen == Screen.Send) openSend()
        if (screen == Screen.Pools && platform.demoScreen == null) pools.attach()
        if (screen is Screen.Replace || screen is Screen.Cpfp) {
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
            s.screen == Screen.TorLog -> Screen.Settings
            else -> Screen.Home
        })
    }

    // ------------------------------------------------------------------ main screen (Android 1.4.4)

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab, screen = Screen.Home) }
        if (tab == Tab.POOLS && platform.demoScreen == null) pools.attach()
    }

    fun setSimple(on: Boolean) {
        prefs.put(K_SIMPLE, if (on) "1" else null)
        _state.update { it.copy(simple = on, tab = Tab.BTC, screen = Screen.Home) }
    }

    fun toggleHidden() = _state.update { it.copy(hidden = !it.hidden) }

    fun setStreetMode(on: Boolean) {
        prefs.put(K_STREET, if (on) "1" else null)
        _state.update { it.copy(streetMode = on, hidden = on || it.hidden) }
    }

    fun setRefreshSeconds(seconds: Int) {
        prefs.put(K_REFRESH, seconds.toString())
        _state.update { it.copy(refreshSeconds = seconds, nextRefreshAt = if (seconds > 0) nowMs() + seconds * 1000L else 0L) }
    }

    /** The next refresh is due [seconds] from now (the main screen calls it after each one). */
    fun scheduleRefresh() {
        val sec = _state.value.refreshSeconds
        _state.update { it.copy(nextRefreshAt = if (sec > 0) nowMs() + sec * 1000L else 0L) }
    }

    fun setGapLimit(gap: Int) {
        val g = gap.coerceIn(5, 200)
        prefs.put(K_GAP, g.toString())
        _state.update { it.copy(gapLimit = g) }
    }

    /** ⏻: the wallet is locked on screen until it is unlocked again (iOS apps do not quit themselves). */
    fun lock() = _state.update { it.copy(locked = true, hidden = it.streetMode || it.hidden) }

    fun unlock() {
        if (_state.value.kind != WalletKind.HOT || platform.demoScreen != null) { _state.update { it.copy(locked = false) }; return }
        platform.seeds.load(I18n.tr("Unlock Kilowallet")) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            _state.update { it.copy(locked = false) }
        }
    }

    /** Screenshot mode (a throwaway demo wallet, nothing sent anywhere). */
    val isDemo: Boolean get() = platform.demoScreen != null
    /** The screenshot run's screen, without its language suffix; null in a real run. */
    val demoName: String? get() = platform.demoScreen?.substringBefore('@')
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

    private fun storeHot(words: List<String>, passphrase: String, then: () -> Unit = {}) {
        scope.launch {
            // The BIP-84 account, and the BIP-86 (Taproot) one so Taproot can be turned on without the words.
            val (zpub, tpub) = withContext(Dispatchers.Default) { Signer.accountZpub(words, passphrase) to Signer.accountTpub(words, passphrase) }
            val err = suspendCancellableCoroutine { c -> platform.seeds.save(encodeSecret(words, passphrase)) { c.resume(it) } }
            if (err != null) { say("Could not store the words: $err"); return@launch }
            prefs.put(K_TPUB, tpub)
            _state.update { it.copy(taprootXpub = tpub) }
            openWallet(WalletKind.HOT, zpub)
            then()
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
        // A Taproot account is a plain xpub: its type is kept next to it (Android Store, 1.2.4).
        val taproot = parsed.scriptType == ScriptType.P2PKH && type == ScriptType.P2TR
        val key = if (parsed.scriptType == ScriptType.P2PKH && type != null && type != ScriptType.P2PKH && !taproot)
            Bip32.withScriptType(raw, type) else raw
        prefs.put(K_WATCH_TYPE, if (taproot) ScriptType.P2TR.name else null)
        _state.update { it.copy(watchType = if (taproot) ScriptType.P2TR else null) }
        openWallet(WalletKind.WATCH, key)
    }

    private fun openWallet(kind: WalletKind, zpub: String) {
        prefs.put(if (kind == WalletKind.HOT) K_HOT else K_WATCH, zpub)
        prefs.put(K_ACTIVE, kind.name)
        _state.update {
            it.copy(kind = kind, zpub = zpub, rows = emptyList(), txs = emptyList(), screen = Screen.Home, send = SendPhase.Editing,
                hotZpub = if (kind == WalletKind.HOT) zpub else it.hotZpub, watchKey = if (kind == WalletKind.WATCH) zpub else it.watchKey)
        }
        scan()
        pools.attach()
    }

    /**
     * 🔥 HOT | 👁 WATCH: show the other wallet. Both live side by side (Android 1.4.4): switching
     * changes what is on screen, nothing is deleted.
     */
    fun switchWallet(kind: WalletKind) {
        val s = _state.value
        val key = (if (kind == WalletKind.HOT) s.hotZpub else s.watchKey) ?: return
        if (s.kind == kind) return
        scanJob?.cancel()
        prefs.put(K_ACTIVE, kind.name)
        _state.update { it.copy(kind = kind, zpub = key, rows = emptyList(), txs = emptyList(), scan = ScanPhase.Idle,
            screen = Screen.Home, tab = if (kind == WalletKind.WATCH && it.tab != Tab.BTC) Tab.BTC else it.tab, send = SendPhase.Editing) }
        scan()
        pools.attach()
        if (kind == WalletKind.HOT && platform.demoScreen == null) lightning.resume()
    }

    /** Remove the wallet on screen (its words too, for the hot one); the other one, if any, stays and is shown. */
    fun deleteWallet() {
        val s = _state.value
        scanJob?.cancel()
        pools.detach()
        if (s.kind == WalletKind.HOT) {
            platform.seeds.delete()
            prefs.put(K_HOT, null); prefs.put(K_TPUB, null); prefs.put(K_RECV_TR, null)
            // Ark's words are made from these: its wallet goes too (the coins stay with the server).
            if (ark.available) ark.turnOff()
        } else {
            prefs.put(K_WATCH, null); prefs.put(K_WATCH_TYPE, null)
        }
        val left = if (s.kind == WalletKind.HOT) s.watchKey?.let { WalletKind.WATCH } else s.hotZpub?.let { WalletKind.HOT }
        prefs.put(K_ACTIVE, left?.name)
        _state.update { UiState(endpoint = it.endpoint, pinned = it.pinned, fiat = it.fiat, market = it.market,
            refreshSeconds = it.refreshSeconds, gapLimit = it.gapLimit, streetMode = it.streetMode,
            hotZpub = if (s.kind == WalletKind.HOT) null else it.hotZpub,
            watchKey = if (s.kind == WalletKind.WATCH) null else it.watchKey,
            watchType = if (s.kind == WalletKind.WATCH) null else it.watchType,
            taprootXpub = if (s.kind == WalletKind.HOT) null else it.taprootXpub,
            receiveTaproot = if (s.kind == WalletKind.HOT) false else it.receiveTaproot) }
        if (left != null) {
            val key = if (left == WalletKind.HOT) _state.value.hotZpub else _state.value.watchKey
            _state.update { it.copy(kind = left, zpub = key, screen = Screen.Home) }
            scan(); pools.attach()
        }
    }

    // ------------------------------------------------------------------ Taproot (Android 1.2.4)

    /**
     * Hot wallet: hand out Taproot (bc1p, BIP-86) or native SegWit (bc1q, BIP-84) addresses for new
     * payments and change. Both accounts are scanned and spent from once Taproot exists, so switching
     * back and forth never hides a coin. A wallet made before 1.0 has no BIP-86 account yet: turning
     * Taproot on then needs the words once.
     */
    fun setReceiveTaproot(on: Boolean) {
        val s = _state.value
        if (s.kind != WalletKind.HOT) return
        fun apply() {
            prefs.put(K_RECV_TR, if (on) "1" else null)
            _state.update { it.copy(receiveTaproot = on) }
            refresh()
        }
        if (!on || s.taprootXpub != null || platform.demoScreen != null) { apply(); return }
        platform.seeds.load(I18n.tr("Turn on Taproot addresses")) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: Taproot stays off."); return@load }
            scope.launch {
                val (words, pass) = decodeSecret(secret)
                val tpub = withContext(Dispatchers.Default) { Signer.accountTpub(words, pass) }
                prefs.put(K_TPUB, tpub)
                _state.update { it.copy(taprootXpub = tpub, rows = emptyList()) }
                apply()
                scan()
            }
        }
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
            _state.update { it.copy(scan = ScanPhase.Scanning("", 0, 0, s.gapLimit, 0), certChanged = null) }
            var found = 0
            scanner.scan(zpub, s.endpoint, s.pinned, s.gapLimit, s.keyType, s.activeTaproot).collect { ev ->
                when (ev) {
                    is ScanEvent.Connecting -> Unit
                    is ScanEvent.Connected -> { lightning.chainTip = ev.height; _state.update { it.copy(height = ev.height, serverVersion = ev.server) } }
                    is ScanEvent.Deriving -> _state.update {
                        val p = it.scan as? ScanPhase.Scanning
                        it.copy(scan = ScanPhase.Scanning(ev.path, ev.chainIndex, p?.gap ?: 0, p?.gapLimit ?: s.gapLimit, found))
                    }
                    is ScanEvent.Found -> { found++; _state.update { st -> st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = 0, found = found) ?: st.scan) } }
                    is ScanEvent.GapProgress -> _state.update { st ->
                        st.copy(scan = (st.scan as? ScanPhase.Scanning)?.copy(gap = ev.consecutiveEmpty, gapLimit = ev.gapLimit) ?: st.scan)
                    }
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        if (!sameWallet(s)) return@collect
                        _state.update { it.copy(scan = ScanPhase.Done, rows = ev.rows, txs = ev.txs, height = ev.height) }
                        s.kind?.let { BalanceWatch.remember(prefs, it.name, ev.rows) }
                        platform.demoScreen?.substringBefore('@')?.takeIf { it == "home" || it == "simple" || it == "immature" }?.let { platform.markReady(it) }
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(scan = ScanPhase.Error(ev.message)) }
                    is ScanEvent.CertificateChanged -> _state.update { it.copy(scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint) }
                }
            }
            if (_state.value.scan is ScanPhase.Error) platform.demoScreen?.substringBefore('@')?.takeIf { it == "home" || it == "simple" || it == "immature" }?.let { platform.markReady(it) }
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
            scanner.scan(zpub, s.endpoint, s.pinned, s.gapLimit, s.keyType, s.activeTaproot).collect { ev ->
                when (ev) {
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        // Filed under the wallet and server it started for (Android 1.2.6): one
                        // removed or replaced meanwhile must not get the other's figures.
                        if (!sameWallet(s)) { _state.update { it.copy(refreshing = false) }; return@collect }
                        _state.update { it.copy(rows = ev.rows, txs = ev.txs, height = ev.height, refreshing = false, scan = ScanPhase.Done) }
                        s.kind?.let { BalanceWatch.remember(prefs, it.name, ev.rows) }
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
        _state.value.zpub == started.zpub && _state.value.endpoint == started.endpoint && _state.value.activeTaproot == started.activeTaproot

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

    /**
     * "Tell me when the balance changes" with the app closed: the addresses are kept after each
     * scan, and iOS's background refresh checks them (BalanceWatch). Asks for permission to notify.
     */
    fun setWatchNotify(on: Boolean) {
        prefs.put(BalanceWatch.KEY_ON, if (on) "1" else null)
        if (on) _state.value.kind?.let { BalanceWatch.remember(prefs, it.name, _state.value.rows) }
        else { BalanceWatch.forget(prefs, "HOT"); BalanceWatch.forget(prefs, "WATCH") }
        platform.scheduleBalanceWatch(on)
    }
    fun watchNotify(): Boolean = BalanceWatch.enabled(prefs)

    /** "Only over Tor": what goes over Tor never goes without it (Lightning waits for Tor). */
    fun setTorOnly(on: Boolean) {
        com.kilombino.kilowallet.core.tor.Tor.only = on
        prefs.put(K_TOR_ONLY, if (on) null else "0")
    }

    /** "Connect to the server through Tor": every Electrum server through Tor; scans again that way. */
    fun setElectrumThroughTor(on: Boolean) {
        com.kilombino.kilowallet.core.tor.Tor.electrum = on
        prefs.put(K_TOR_ELECTRUM, if (on) "1" else null)
        if (_state.value.zpub != null) scan()
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

    /** The account new addresses come from: the Taproot one when the hot wallet hands out bc1p. */
    private fun receivesTaproot(s: UiState = _state.value) = s.kind == WalletKind.HOT && s.receiveTaproot && s.taprootXpub != null

    /** Rows of the receiving account (the BIP-86 one when Taproot is on, else the wallet's own). */
    private fun receiveRows(s: UiState = _state.value) = s.rows.filter { (it.type == ScriptType.P2TR) == receivesTaproot(s) }

    /** The receiving account's key, read as its type. */
    private fun receiveAccount(s: UiState = _state.value): Bip32.ExtendedPubKey? = if (receivesTaproot(s))
        Bip32.parseExtendedPubKey(s.taprootXpub!!).withType(ScriptType.P2TR)
        else s.zpub?.let { z -> Bip32.parseExtendedPubKey(z).let { p -> s.keyType?.let { p.withType(it) } ?: p } }

    /** The next unused receive index: one past the highest used (Android: `nextReceiveIndex`). */
    fun nextReceiveIndex(): Int = receiveRows().filter { it.chainIndex == 0 && it.isUsed }.maxOfOrNull { it.index }?.plus(1) ?: 0

    /**
     * Receive indexes that hold nothing and never did (Android: `unusedReceiveIndexes`): the ones
     * skipped below the last used address (handed out and never paid) and [ahead] past the next
     * unused one, in order. iOS has one wallet and no coinjoin, so no seat is left out.
     */
    fun unusedReceiveIndexes(ahead: Int = 20): List<Int> {
        val used = receiveRows().filter { it.chainIndex == 0 && it.isUsed }.map { it.index }.toSet()
        // An address a pool's round counts on (its equal output) is not handed out here.
        val seats = pools.reservedScripts()
        val acct = receiveAccount()
        return (0 until nextReceiveIndex() + ahead).filter { i ->
            i !in used && (seats.isEmpty() || acct == null || runCatching {
                Address.scriptPubKey(Bip32.derivePath(acct, 0, i).pubkey(), acct.scriptType).toList() !in seats
            }.getOrDefault(true))
        }
    }

    /** The receive address at [index] (default: the next unused) and its derivation path. */
    fun receiveAddress(index: Int = unusedReceiveIndexes().firstOrNull { it >= nextReceiveIndex() } ?: nextReceiveIndex()): Pair<String, String>? {
        return runCatching {
            val parsed = receiveAccount() ?: return null
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
                // A watch-only wallet's coins are of its own type, sized as such (Taproot 57.75 vB, nested 91).
                val watchType = if (s.kind == WalletKind.WATCH && s.scriptType != ScriptType.P2WPKH) s.scriptType else null
                val utxos = (s.spendable ?: scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned))
                    .filter { "${it.txid}:${it.vout}" !in locked }
                    .map { if (watchType != null && it.type == null) it.copy(type = watchType) else it }
                val draft = withContext(Dispatchers.Default) {
                    SendPlanner.plan(to, amountSats, rate, utxos, freshChange(s), sendAll)
                }
                scanner.verifyCoins(zpub, draft.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot)
                draft
            }.onSuccess { d -> _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the send.")) } }
        }
    }

    // ------------------------------------------------------------------ watch-only: sign elsewhere (PSBT)

    /** The account key's child number (bytes 9–12 of the serialised key), for the origin a signer looks for. */
    private fun childNumber(key: String): Long {
        val raw = com.kilombino.kilowallet.core.crypto.Base58.decodeChecked(key.trim())
        return (9..12).fold(0L) { a, i -> (a shl 8) or (raw[i].toLong() and 0xff) }
    }

    /**
     * Hand the reviewed send to a signer as a PSBT (Android 1.1, watch-only): every input asks for the
     * unified sighash 0x21; the change output is marked as ours so the signer can tell it apart.
     */
    fun exportPsbt() {
        val s = _state.value
        val draft = (s.send as? SendPhase.Review)?.draft ?: return
        val key = s.zpub ?: return
        runCatching {
            val acct = Bip32.parseExtendedPubKey(key).let { p -> s.keyType?.let { p.withType(it) } ?: p }
            val type = acct.scriptType
            require(type != ScriptType.P2PKH) { "Signing elsewhere works for bc1q, nested SegWit (3…) and Taproot (bc1p) wallets." }
            val origin = com.kilombino.kilowallet.core.crypto.Psbt.Origin.guess(type, acct.depth, childNumber(key))
            val coins = draft.inputs.map { u ->
                com.kilombino.kilowallet.core.crypto.Psbt.Coin(u.txid, u.vout, u.value, type,
                    Bip32.derivePath(acct, u.chainIndex, u.index).pubkey(), origin?.child(u.chainIndex, u.index))
            }
            // Our change: the output paying one of this wallet's change addresses, found by deriving them.
            val firstChange = s.rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
            val change = draft.outputs.withIndex().mapNotNull { (n, o) ->
                (maxOf(0, firstChange - 5) until firstChange + 25).firstOrNull { i ->
                    Address.scriptPubKey(Bip32.derivePath(acct, 1, i).pubkey(), type).contentEquals(o.scriptPubKey)
                }?.let { i -> n to com.kilombino.kilowallet.core.crypto.Psbt.Change(Bip32.derivePath(acct, 1, i).pubkey(), type, origin?.child(1, i)) }
            }.toMap()
            SendPhase.Psbt(draft, com.kilombino.kilowallet.core.crypto.Psbt.create(coins, draft.outputs, change), coins)
        }.onSuccess { p -> _state.update { it.copy(send = p) } }
         .onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not make the PSBT.")) } }
    }

    /**
     * What the signer returned (a PSBT as base64, hex or bytes): checked to be the very transaction
     * handed out, every signature verified against our own copy of the coins and required to be a
     * unified (0x21) one, then broadcast.
     */
    fun finishPsbt(returned: ByteArray) {
        val s = _state.value
        val p = s.send as? SendPhase.Psbt ?: return
        _state.update { it.copy(send = SendPhase.Signing) }
        scope.launch {
            runCatching {
                val tx = withContext(Dispatchers.Default) { com.kilombino.kilowallet.core.crypto.Psbt.finish(p.exported, returned, p.coins, p.draft.outputs) }
                scanner.broadcast(tx.rawHex, s.endpoint, s.pinned)
            }.onSuccess { txid ->
                _state.update { it.copy(send = SendPhase.Sent(txid)) }
                refresh()
            }.onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "The signed PSBT could not be used.")) } }
        }
    }

    /** [finishPsbt] from text: base64, hex, or a single-part ur:crypto-psbt. */
    fun finishPsbtText(text: String) {
        runCatching {
            val t = text.trim()
            if (t.lowercase().startsWith("ur:")) com.kilombino.kilowallet.core.crypto.Ur.Decoder().also { d ->
                require(d.receive(t)) { "That is one part of an animated QR: scan it with the camera." }
            }.psbt() else com.kilombino.kilowallet.core.crypto.Psbt.decodeText(t)
        }.onSuccess { finishPsbt(it) }.onFailure { say(it.message ?: "That is not a PSBT.") }
    }

    /** Read the signer's animated QR with the camera, part by part, then [finishPsbt]. */
    fun scanSignedPsbt() {
        val decoder = com.kilombino.kilowallet.core.crypto.Ur.Decoder()
        var single: String? = null
        platform.scanQrParts(onPart = { text ->
            if (!text.lowercase().startsWith("ur:")) { single = text; true }
            else runCatching { decoder.receive(text) }.getOrDefault(false)
        }, onEnd = {
            val one = single
            when {
                one != null -> finishPsbtText(one)
                runCatching { decoder.psbt() }.isSuccess -> finishPsbt(decoder.psbt())
            }
        })
    }

    /** Unlock the words, sign with the unified sighash (0x21) and broadcast. */
    fun confirmSend() {
        val s = _state.value
        val draft = (s.send as? SendPhase.Review)?.draft ?: return
        val zpub = s.zpub ?: return
        val reason = when {
            draft.cancels -> I18n.tr("Unlock to sign the cancellation")
            draft.cpfpParent != null -> I18n.tr("Unlock to sign the speed-up")
            draft.replaces != null -> I18n.tr("Unlock to sign the speed-up")
            else -> I18n.tr("Sign and send ${com.kilombino.kilowallet.core.wallet.formatSats(draft.amount)} sats")
        }
        platform.seeds.load(reason) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: nothing was sent."); return@load }
            _state.update { it.copy(send = SendPhase.Signing) }
            scope.launch {
                runCatching {
                    val (words, pass) = decodeSecret(secret)
                    val signed = withContext(Dispatchers.Default) { Signer.sign(draft, words, pass, zpub, s.activeTaproot) }
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
        val tr = receivesTaproot(s)
        val key = (if (tr) s.taprootXpub else s.zpub) ?: error("No wallet.")
        val type = if (tr) ScriptType.P2TR else s.keyType
        val held = pools.reservedScripts()
        var index = receiveRows(s).filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
        while (SendPlanner.changeScript(key, index, type).toList() in held) index++
        return SendPlanner.changeScript(key, index, type)
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
                }.also { scanner.verifyCoins(zpub, it.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot) }
            }.onSuccess { d -> _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e ->
                 val fallback = if (cancel) "Could not prepare the cancellation." else "Could not prepare the replacement."
                 _state.update { it.copy(send = SendPhase.Failed(e.message ?: fallback)) }
             }
        }
    }

    // ------------------------------------------------------------------ speed up an incoming payment (CPFP)

    /**
     * Pull a stuck unconfirmed transaction that paid this wallet along (child pays for parent,
     * Android `prepareCpfp`): our outputs of it go back to a fresh address of ours with a fee big
     * enough that parent and child together pay [rate]. The review and the signing are a send's.
     */
    fun prepareCpfp(txid: String, rate: Double) {
        val s = _state.value
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT) return
        _state.update { it.copy(send = SendPhase.Preparing) }
        scope.launch {
            runCatching {
                val (fee, vsize, ours) = scanner.cpfpParent(txid, s.rows, s.endpoint, s.pinned)
                val d = withContext(Dispatchers.Default) { com.kilombino.kilowallet.core.wallet.CpfpPlanner.plan(txid, fee, vsize, ours, freshChange(s), rate) }
                scanner.verifyCoins(zpub, d.inputs, s.endpoint, s.pinned, s.keyType, s.activeTaproot)
                d
            }.onSuccess { d -> _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e -> _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the speed-up.")) } }
        }
    }

    // ------------------------------------------------------------------ dice (Android 1.2: SeedSigner's way)

    fun startDice() = go(Screen.Dice)

    /** The words for [rolls] (50 → 12 words, 99 → 24), shown to be written down like any new wallet's. */
    fun diceWords(rolls: String) {
        runCatching {
            val bits = if (rolls.length == 50) 128 else 256
            Bip39.fromEntropy(Bip39.entropyFromDiceRolls(rolls, bits))
        }.onSuccess { go(Screen.CreateShow(it)) }.onFailure { say(it.message ?: "Those rolls can't make a seed.") }
    }

    // ------------------------------------------------------------------ backup file (.kab, Android 0.20)

    /**
     * The settings that travel in the backup file, in the Android app's typed format and under its
     * key names, so a file restores the same way on either phone. Never a server: a file one did not
     * make must not point the wallet at someone else's (Android `AppBackup.isServerKey`).
     */
    private fun settingsJson(): kotlinx.serialization.json.JsonObject {
        val s = _state.value
        fun typed(t: String, v: kotlinx.serialization.json.JsonPrimitive) = kotlinx.serialization.json.buildJsonObject { put("t", kotlinx.serialization.json.JsonPrimitive(t)); put("v", v) }
        return kotlinx.serialization.json.buildJsonObject {
            put("store", kotlinx.serialization.json.buildJsonObject {
                put("fiat", typed("s", kotlinx.serialization.json.JsonPrimitive(s.fiat)))
                put("refresh_open_s", typed("i", kotlinx.serialization.json.JsonPrimitive(s.refreshSeconds)))
                put("coinjoin_enabled", typed("b", kotlinx.serialization.json.JsonPrimitive(pools.state.value.enabled)))
                put("ios_gap_limit", typed("i", kotlinx.serialization.json.JsonPrimitive(s.gapLimit)))
                put("ios_street_mode", typed("b", kotlinx.serialization.json.JsonPrimitive(s.streetMode)))
            })
            // Lightning's birthday and channel backup, under Android's key (AppBackup "lightning").
            lightning.exportBackup()?.let { put("lightning", it) }
        }
    }

    private fun applySettings(app: kotlinx.serialization.json.JsonObject?) {
        val store = app?.get("store") as? kotlinx.serialization.json.JsonObject ?: return
        fun v(k: String) = ((store[k] as? kotlinx.serialization.json.JsonObject)?.get("v") as? kotlinx.serialization.json.JsonPrimitive)
        v("fiat")?.content?.let { if (it == Fiat.OFF || Fiat.ALL.any { f -> f.code == it }) setFiat(it) }
        v("refresh_open_s")?.content?.toIntOrNull()?.let { if (it in listOf(0, 30, 60, 300)) setRefreshSeconds(it) }
        v("ios_gap_limit")?.content?.toIntOrNull()?.let { setGapLimit(it) }
        v("ios_street_mode")?.content?.toBooleanStrictOrNull()?.let { setStreetMode(it) }
        v("coinjoin_enabled")?.content?.toBooleanStrictOrNull()?.let { pools.setEnabled(it) }
        (app["lightning"] as? kotlinx.serialization.json.JsonObject)?.let { runCatching { lightning.stageRestore(it) } }
    }

    /** Make the backup file (words, passphrase and settings, sealed with [password]) and ask where to save it. */
    fun exportBackup(password: String) {
        if (password.length < com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD) { say("Use a password of at least ${com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD} characters."); return }
        platform.seeds.load(I18n.tr("Unlock to write the backup file")) { secret, error ->
            if (secret == null) { say(error ?: "Not unlocked: no file was written."); return@load }
            scope.launch {
                val (words, pass) = decodeSecret(secret)
                // Ark's wallet goes in when it runs on the wallet's words or on words derived from them
                // (Android's fullSnapshot); an Ark of other words keeps its own backup.
                val arkFiles = ark.backupFiles(words, pass)
                val file = withContext(Dispatchers.Default) {
                    com.kilombino.kilowallet.core.backup.Kab.encode(
                        com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, pass, nowMs(), _state.value.txs.size, settingsJson(),
                            config = arkFiles?.first?.config, db = arkFiles?.first?.db, dbWal = arkFiles?.first?.dbWal,
                            arkDerived = arkFiles?.second == true), password)
                }
                val day = kotlin.time.Clock.System.now().toString().take(10).replace("-", "")
                platform.saveFile("kilowallet-$day.kab", file) { err ->
                    when (err) {
                        null -> { say("Backup file saved. Keep it and its password apart: together they open the wallet."); go(Screen.Settings) }
                        "cancelled" -> Unit
                        else -> say("Could not save the file: $err")
                    }
                }
            }
        }
    }

    /** Pick a backup file to restore a wallet from. */
    fun pickBackupFile() {
        platform.openFile { bytes ->
            if (bytes == null) return@openFile
            if (!com.kilombino.kilowallet.core.backup.Kab.isBackup(bytes)) { say("That is not a Kilowallet backup file (.kab)."); return@openFile }
            go(Screen.RestoreFile(bytes))
        }
    }

    /** Open [bytes] with [password]: the words become the hot wallet, and the settings come back. */
    fun restoreBackup(bytes: ByteArray, password: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { com.kilombino.kilowallet.core.backup.Kab.decode(bytes, password) } }
                .onSuccess { snap ->
                    if (!Bip39.isValid(snap.words)) { say("The words in this file are not a valid backup."); return@onSuccess }
                    storeHot(snap.words, snap.passphrase) {
                        applySettings(snap.app)
                        if (snap.hasArk) ark.restoreFromBackup(snap)
                        else if (lightning.hasStagedChannels()) say("Wallet restored. The file has a channel backup: turning Lightning on hands it to the node, which asks each peer to close.")
                    }
                }
                .onFailure { e ->
                    say(if (e is com.kilombino.kilowallet.core.backup.Kab.WrongPassword) "Wrong password." else e.message ?: "Could not read the file.")
                }
        }
    }

    // ------------------------------------------------------------------ demo (CI screenshots only)

    /**
     * Screenshot run, real node: Lightning over Tor end to end. Waits for the watchtower to be added
     * (through Tor) and for a peer, then reaches Kilombino's node by its onion (after dropping the
     * clearnet link), and logs what LND says. Nothing moves money.
     */
    private suspend fun lightningOverTorCheck() {
        val ln = com.kilombino.kilowallet.core.ln.Lnd
        val k = com.kilombino.kilowallet.core.ln.Kilombino
        println("KW ln-live: node running, Tor in LND: ${lightning.torOn}, SOCKS ${com.kilombino.kilowallet.core.tor.Tor.port}")
        if (!lightning.torOn) return
        withTimeoutOrNull(240_000) {
            while (runCatching { ln.towers() }.getOrDefault(emptyList()).isEmpty()) { runCatching { lightning.refresh() }; delay(5_000) }
        }
        val towers = runCatching { ln.towers().toString() }.getOrElse { it.message ?: it.toString() }
        println("KW ln-live: towers $towers")
        runCatching { ln.disconnect(k.NODE) }
        delay(2_000)
        val r = runCatching { ln.connect(k.NODE, k.ONION) }
        val how = r.exceptionOrNull()?.message ?: "OK"
        val peers = runCatching { ln.peerAddresses().toString() }.getOrElse { it.message ?: it.toString() }
        println("KW ln-live: connect Kilombino by onion: $how; peers $peers")
        // RestoreChannelBackups by RPC (fixed in Lightning Fork 21.2: up to 21.1 it took every backup
        // for another chain's). The node's own backup has no channel, so this only shows the call goes through.
        val scb = runCatching { ln.channelBackup() }
        val restored = scb.mapCatching { ln.restoreChannelBackups(it); "OK" }.getOrElse { it.message ?: it.toString() }
        val scbSize = scb.getOrNull()?.size ?: 0
        println("KW ln-live: own channel backup $scbSize bytes; RestoreChannelBackups by RPC: $restored")
        runCatching { lightning.refresh() }
    }

    /**
     * Screenshot run, real Ark engine: the whole-wallet backup file end to end on iPhone. Ark's
     * files are taken with the engine stopped, sealed in a .kab, opened again, put back and the
     * engine started on them; the CI log says what came back.
     */
    private suspend fun arkBackupRoundTrip(words: List<String>) {
        val kab = com.kilombino.kilowallet.core.backup.Kab
        val r = runCatching {
            val (files, derived) = ark.backupFiles(words, "") ?: error("no Ark files to back up")
            val file = withContext(Dispatchers.Default) {
                kab.encode(com.kilombino.kilowallet.core.backup.Kab.Snapshot(words, "", nowMs(), 0, settingsJson(), config = files.config, db = files.db, dbWal = files.dbWal, arkDerived = derived), "demo password")
            }
            val back = withContext(Dispatchers.Default) { kab.decode(file, "demo password") }
            check(back.hasArk && back.db!!.contentEquals(files.db)) { "the Ark database did not come back" }
            ark.restoreFromBackup(back)
            delay(3_000)
            val v = withTimeoutOrNull(120_000) { ark.view.first { it == ArkModel.View.Ready || it is ArkModel.View.Failed } }
            "file ${file.size} bytes, db ${files.db.size} bytes, wal ${files.dbWal?.size ?: 0}, derived $derived; after restore: $v"
        }
        val how = r.getOrElse { "FAILED: " + (it.message ?: it.toString()) }
        println("KW ark-live: backup round trip: $how")
    }

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
                    "immature" -> scan()
                    "receive" -> { go(Screen.Receive); platform.markReady(screen) }
                    "settings" -> { go(Screen.Settings); platform.markReady(screen) }
                    "lightning" -> { selectTab(Tab.LIGHTNING); platform.markReady(screen) }
                    "ln-bridge" -> {
                        // A made-up SHA-256 invoice of 1,000 sats and a price, only so the bridge's dialog shows.
                        lightning.chainTip = 979_937
                        lightning.demoBridge = com.kilombino.kilowallet.core.ln.Lnd.PayReq("02cd566293ad8c8fd55bf5c5be38df5fc0af2b445333b10cbbd8b8a7d4c7ed7fe3",
                            "a0".repeat(32), 1000, "coffee", 86400, 1_791_978_000L, blake2b = false) to
                            com.kilombino.kilowallet.core.ln.Bridge.Estimate(1000, 118_493, 0.008622, 0.0216, 0)
                        lightning.setDemo(demoLightning("ln-running"))
                        selectTab(Tab.LIGHTNING); platform.markReady(screen)
                    }
                    "ln-move" -> { selectTab(Tab.LIGHTNING); platform.markReady(screen) }
                    "settings-notify" -> { prefs.put(BalanceWatch.KEY_ON, "1"); go(Screen.Settings); platform.markReady(screen) }
                    "ln-starting", "ln-running", "ln-failed" -> {
                        lightning.chainTip = 979_937
                        lightning.setDemo(demoLightning(screen))
                        selectTab(Tab.LIGHTNING); platform.markReady(screen)
                    }
                    "ln-live" -> {
                        // The real engine, a throwaway wallet ("abandon … about") and the password in
                        // memory: LND starts, makes its wallet and syncs against the chain's peers.
                        selectTab(Tab.LIGHTNING)
                        lightning.chainTip = 979_937
                        launch {
                            val st = lightning.status.first { it is LightningModel.Status.Running || it is LightningModel.Status.Failed }
                            if (st is LightningModel.Status.Running) lightningOverTorCheck()
                            platform.markReady(screen)
                        }
                        runCatching {
                            val seed = withContext(Dispatchers.Default) { Bip39.toSeed(words, "") }
                            withContext(Dispatchers.Default) { lightning.enable(seed) }
                        }
                    }
                    "tor-live", "tor-log" -> {
                        // The real Tor (Arti in the app): it starts and bootstraps, and with "Connect to
                        // the server through Tor" the wallet's Electrum server is reached through it.
                        launch {
                            val p = com.kilombino.kilowallet.core.tor.Tor.startAndWait(240_000)
                            val torSays = com.kilombino.kilowallet.core.tor.Tor.lastLine
                            println("KW $screen: Tor SOCKS port $p (null: not up), last line: $torSays")
                            if (screen == "tor-live" && p != null) {
                                setElectrumThroughTor(true)
                                val end = withTimeoutOrNull(180_000) { _state.first { it.scan is ScanPhase.Done || it.scan is ScanPhase.Error }.scan }
                                val height = _state.value.height
                                println("KW tor-live: Electrum through Tor: $end (null: no answer in 3 min); height $height")
                                // The pools' relay through Tor: its onion first (RelayRoute).
                                val pools = runCatching { withContext(Dispatchers.Default) { pools.countPoolsNow() } }
                                val route = (platform.sockets as? RelayRoute)?.last
                                val got = pools.getOrElse { "failed: " + (it.message ?: it.toString()) }
                                println("KW tor-live: pool list through Tor via $route: $got")
                            }
                            go(if (screen == "tor-log") Screen.TorLog else Screen.Settings)
                            delay(2_500); platform.markReady(screen)
                        }
                    }
                    "ark" -> { selectTab(Tab.ARK); platform.markReady(screen) }
                    "ark-ready" -> {
                        ark.setDemo(ArkModel.View.Ready, demoArk())
                        selectTab(Tab.ARK); platform.markReady(screen)
                    }
                    "ark-live" -> {
                        // The real engine and the real server, a throwaway Ark wallet made from the
                        // "abandon … about" words: it starts, creates its wallet and hands out an
                        // address. No funds move.
                        selectTab(Tab.ARK)
                        launch {
                            val v = ark.view.first { it == ArkModel.View.Ready || it is ArkModel.View.Failed }
                            if (v == ArkModel.View.Ready) arkBackupRoundTrip(words)
                            delay(4_000); platform.markReady(screen)
                        }
                        ark.activate(words, "")
                    }
                    "simple" -> { _state.update { it.copy(simple = true) }; scan() }
                    "locked" -> { _state.update { it.copy(locked = true) }; platform.markReady(screen) }
                    "replace" -> {
                        // A made-up pending send, only so the cancel screen has something to show.
                        go(Screen.Replace("4f".repeat(32), cancel = true)); platform.markReady(screen)
                    }
                    "send" -> { go(Screen.Send); platform.markReady(screen) }
                    "dice" -> { go(Screen.Dice); platform.markReady(screen) }
                    "psbt" -> {
                        // The same made-up coin as "review", from the demo wallet watched: the PSBT to sign elsewhere.
                        val coin = SpendableUtxo("4f".repeat(32), 0, 250_000, 0, 0, 970_000)
                        val draft = SendPlanner.plan("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 120_000, 2.0,
                            listOf(coin), SendPlanner.changeScript(zpub, 0))
                        _state.update { it.copy(kind = WalletKind.WATCH, watchKey = zpub, screen = Screen.Send,
                            send = SendPhase.Review(draft, null)) }
                        exportPsbt(); platform.markReady(screen)
                    }
                    "backup" -> { go(Screen.Backup); platform.markReady(screen) }
                    "cpfp" -> {
                        // A made-up incoming payment, only so the speed-up screen has something to show.
                        go(Screen.Cpfp("6b".repeat(32))); platform.markReady(screen)
                    }
                    "receive-taproot", "settings-taproot" -> {
                        val tpub = withContext(Dispatchers.Default) { Signer.accountTpub(words, "") }
                        _state.update { it.copy(taprootXpub = tpub, receiveTaproot = true) }
                        go(if (screen == "receive-taproot") Screen.Receive else Screen.Settings); platform.markReady(screen)
                    }
                    "pools", "pool-round", "pool-intro", "pool-history", "pool-v6" -> {
                        withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }
                        if (screen == "pool-intro") pools.setEnabled(false)
                        go(Screen.Pools); platform.markReady(screen)
                    }
                    "pool-create", "pool-create-v6" -> { withContext(Dispatchers.Default) { pools.setUpDemo(screen, words) }; go(Screen.PoolCreate); platform.markReady(screen) }
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

/** Screenshot demos of the LIGHTNING tab: made-up figures, never a real node. */
/** Made-up Ark figures for the "ark-ready" screenshot. */
private fun demoArk(): ArkModel.Figures = ArkModel.Figures(
    balance = com.kilombino.kilowallet.core.ark.Ark.Balance(
        spendable = 184_250, pendingBoard = 40_000, pendingRound = 0, needsRefresh = 0, pendingExit = 0,
        onchainConfirmed = 12_500, onchainPending = 0, lightningPending = 0,
    ),
    expiry = 3_870,
    activity = listOf(
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-1", "pending", "move into Ark", 40_000, "2026-11-01T10:12:00", 2_000,
            onchainTxid = "6b".repeat(32), confirmations = 1),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-2", "successful", "Lightning payment", -12_480, "2026-10-31T18:40:00", 4_120),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-3", "successful", "Ark received", 25_000, "2026-10-30T09:05:00"),
        com.kilombino.kilowallet.core.ark.Ark.Movement("demo-4", "successful", "renewal", -1_056, "2026-10-28T21:00:00", 1_056),
    ),
)

private fun demoLightning(screen: String): LightningModel.Status = when (screen) {
    "ln-starting" -> LightningModel.Status.Starting("catching up with the chain", 812_450)
    "ln-failed" -> LightningModel.Status.Failed("The node stopped: unable to connect to any peer")
    else -> {
        val k = com.kilombino.kilowallet.core.ln.Kilombino.NODE
        LightningModel.Status.Running(
            com.kilombino.kilowallet.core.ln.Lnd.Info("02" + "7a".repeat(32), "Kilowallet-demo", 979_937, true, true, 1, 1, 0, 1_793_480_000L),
            com.kilombino.kilowallet.core.ln.Lnd.Balances(48_200, 0, 412_350, 0),
            listOf(com.kilombino.kilowallet.core.ln.Lnd.Channel(true, k, "4f".repeat(32) + ":0", 1_000_000, 412_350, 584_650, "Kilombino", 1L)),
            emptyList(), 979_937,
            invoices = listOf(com.kilombino.kilowallet.core.ln.Lnd.Invoice("coffee", 2_100, 2_100, 1_793_470_000L, 1_793_470_060L, 1)),
            payments = listOf(com.kilombino.kilowallet.core.ln.Lnd.Payment(15_000, 3, 1_793_475_000L, 2, "ab".repeat(32))),
        )
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
