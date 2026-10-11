package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.wallet.AddressRow
import com.kilombino.kilowallet.core.wallet.ScanEvent
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.SendDraft
import com.kilombino.kilowallet.core.wallet.SendPlanner
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.TxConf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.kilombino.kilowallet.core.crypto.TxParse
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
    data class ShowWords(val words: List<String>) : Screen
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
    data class Pending(val txid: String, val message: String) : SendPhase
    data class Sent(val txid: String) : SendPhase
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
) {
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
class WalletModel(private val platform: AppPlatform, private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = Scanner(platform.transports)
    private val prefs = platform.prefs
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private var actionEpoch = 0L
    private var walletEpoch = 0L
    private var setupBusy = false

    private fun cancelAction() { actionEpoch++; sendJob?.cancel(); sendJob = null }
    private fun isCurrent(epoch: Long, snapshot: UiState) =
        epoch == actionEpoch && state.value.zpub == snapshot.zpub && state.value.endpoint == snapshot.endpoint
    private fun pendingKey(zpub: String) = "pending.$zpub"
    private fun pending(zpub: String): SendPhase.Pending? = prefs.get(pendingKey(zpub))?.let { raw ->
        runCatching { SendPhase.Pending(TxParse.txid(TxParse.parse(raw)), "Delivery is unconfirmed. Check or resend this same transaction before making a new payment.") }
            .getOrElse { SendPhase.Pending("unavailable", "The saved pending transaction is unreadable. Do not create another payment until its status is resolved.") }
    }

    /** Clear secret screens and invalidate outstanding unlock callbacks when backgrounded. */
    fun onBackground() {
        cancelAction()
        scanJob?.cancel()
        walletEpoch++
        _state.update { it.copy(screen = if (it.kind == null) Screen.Welcome else Screen.Home,
            send = SendPhase.Editing, scan = ScanPhase.Idle, refreshing = false, message = null) }
    }


    private companion object {
        const val K_KIND = "wallet.kind"
        const val K_ZPUB = "wallet.zpub"
        const val K_HOST = "server.host"
        const val K_PORT = "server.port"
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
        )
        if (kind != null && zpub != null) scan()
    }

    fun go(screen: Screen) {
        if (setupBusy || _state.value.send is SendPhase.Signing) return
        cancelAction()
        _state.update { it.copy(screen = screen, message = null) }
        if (screen == Screen.Send) openSend()
    }

    fun back() {
        val s = _state.value
        go(if (s.kind == null) Screen.Welcome else Screen.Home)
    }

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
        val confirmation = _state.value.screen as? Screen.CreateConfirm ?: return false
        val ok = words == confirmation.words && answers.keys == confirmation.positions.toSet() &&
            answers.all { (i, w) -> words.getOrNull(i) == w.trim().lowercase() }
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
        if (setupBusy || state.value.kind != null) return
        setupBusy = true
        scope.launch {
            try {
                val zpub = withContext(cryptoDispatcher) { Signer.accountZpub(words, passphrase) }
                val err = suspendCancellableCoroutine { c -> platform.seeds.save(encodeSecret(words, passphrase)) { if (c.isActive) c.resume(it) } }
                if (err != null) { say("Could not store the words: $err"); return@launch }
                openWallet(WalletKind.HOT, zpub)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { say(e.message ?: "Could not create the wallet") }
            finally { setupBusy = false }
        }
    }

    fun watch(xpub: String) {
        if (setupBusy || state.value.kind != null) return
        val key = xpub.trim()
        val parsed = runCatching { Bip32.parseExtendedPubKey(key) }.getOrElse { say(it.message ?: "Not a valid key."); return }
        if (parsed.depth != 3) say("Note: this key is not at account depth (m/purpose'/0'/0').")
        openWallet(WalletKind.WATCH, key)
    }

    private fun openWallet(kind: WalletKind, zpub: String) {
        cancelAction(); scanJob?.cancel(); walletEpoch++
        prefs.put(K_KIND, kind.name)
        prefs.put(K_ZPUB, zpub)
        _state.update {
            it.copy(kind = kind, zpub = zpub, rows = emptyList(), txs = emptyList(), screen = Screen.Home, send = SendPhase.Editing)
        }
        scan()
    }

    fun deleteWallet() {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        cancelAction(); scanJob?.cancel(); walletEpoch++
        platform.seeds.delete()?.let { say("Could not remove the wallet: $it"); return }
        prefs.put(K_KIND, null); prefs.put(K_ZPUB, null)
        _state.update { UiState(endpoint = it.endpoint, pinned = it.pinned) }
    }

    /** Show the recovery words after Face ID / Touch ID / passcode. */
    fun revealWords() {
        if (state.value.kind != WalletKind.HOT) return
        cancelAction()
        val epoch = actionEpoch
        val snapshot = state.value
        platform.seeds.load("Show your recovery words") { secret, error ->
            if (!isCurrent(epoch, snapshot)) return@load
            if (secret == null) { say(error ?: "Not unlocked."); return@load }
            go(Screen.ShowWords(decodeSecret(secret).first))
        }
    }

    // ------------------------------------------------------------------ scanning

    fun scan() {
        val s = _state.value
        val zpub = s.zpub ?: return
        scanJob?.cancel()
        val scanEpoch = walletEpoch
        scanJob = scope.launch {
            _state.update { it.copy(scan = ScanPhase.Scanning("", 0, 0, 20, 0), certChanged = null) }
            var found = 0
            scanner.scan(zpub, s.endpoint, s.pinned).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
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
                        _state.update { it.copy(scan = ScanPhase.Done, rows = ev.rows, txs = ev.txs, height = ev.height) }
                        if (platform.demoScreen == "home") platform.markReady("home")
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(scan = ScanPhase.Error(ev.message)) }
                    is ScanEvent.CertificateChanged -> _state.update { it.copy(scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint) }
                }
            }
            if (platform.demoScreen == "home" && _state.value.scan is ScanPhase.Error) platform.markReady("home")
        }
    }

    /**
     * Pull-to-refresh and the minute timer: the same gap-limit walk, but quiet (the balance stays on
     * screen) — so a payment to the next unused address is found too, not only to known ones.
     */
    fun refresh() {
        val s = _state.value
        if (s.scan is ScanPhase.Scanning || s.refreshing) return
        if (s.rows.isEmpty() && s.scan !is ScanPhase.Done) { scan(); return }
        val zpub = s.zpub ?: return
        val scanEpoch = walletEpoch
        scanJob = scope.launch {
            _state.update { it.copy(refreshing = true) }
            scanner.scan(zpub, s.endpoint, s.pinned).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
                when (ev) {
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
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
        if (setupBusy || state.value.send is SendPhase.Signing) return
        cancelAction(); scanJob?.cancel(); walletEpoch++
        val e = if (host.isBlank() || port == null) NodeEndpoint.DEFAULT
                else NodeEndpoint(host.trim(), port, !(host.trim() == NodeEndpoint.DEFAULT.host && port == NodeEndpoint.DEFAULT.port))
        prefs.put(K_HOST, if (e.isCustom) e.host else null)
        prefs.put(K_PORT, if (e.isCustom) e.port.toString() else null)
        _state.update { it.copy(endpoint = e, pinned = prefs.get(pinKey(e)), rows = emptyList(), txs = emptyList()) }
        if (_state.value.zpub != null) scan()
    }

    // ------------------------------------------------------------------ receive

    /** The next unused receive address: one past the highest used (Android: `nextReceiveIndex`). */
    fun receiveAddress(): Pair<String, String>? {
        val s = _state.value
        val zpub = s.zpub ?: return null
        val scannedIndex = s.rows.filter { it.chainIndex == 0 }.maxOfOrNull { it.index }?.plus(1) ?: 0
        val key = "receiveIndex.$zpub"
        val index = maxOf(scannedIndex, prefs.get(key)?.toIntOrNull() ?: 0)
        prefs.put(key, index.toString())
        return runCatching {
            val parsed = Bip32.parseExtendedPubKey(zpub)
            val pub = Bip32.derivePath(parsed, 0, index).pubkey()
            Address.encode(pub, parsed.scriptType) to "m/${Scanner.purposeFor(parsed.scriptType)}'/0'/0'/0/$index"
        }.getOrNull()
    }

    // ------------------------------------------------------------------ send

    private fun openSend() {
        val s = _state.value
        s.zpub?.let { z -> pending(z)?.let { p -> _state.update { it.copy(send = p) }; return } }
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Editing, spendable = null) }
        scope.launch {
            val rate = scanner.suggestedFeeRate(s.endpoint, s.pinned)
            if (isCurrent(epoch, s)) _state.update { it.copy(suggestedRate = rate) }
        }
        scope.launch {
            runCatching { scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned) }
                .onSuccess { u -> if (isCurrent(epoch, s)) _state.update { it.copy(spendable = u) } }
        }
    }

    fun maxSendable(rate: Double): Long = runCatching { SendPlanner.maxSendable(rate, _state.value.spendable ?: emptyList()) }.getOrDefault(0)

    /** Choose coins and the fee and show the review; nothing secret is touched yet. */
    fun prepareSend(to: String, amountSats: Long, rate: Double, sendAll: Boolean) {
        val s = _state.value
        val zpub = s.zpub ?: return
        if (s.send !is SendPhase.Editing || s.kind != WalletKind.HOT || pending(zpub) != null) return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Preparing) }
        sendJob = scope.launch {
            runCatching {
                val utxos = s.spendable ?: scanner.gatherUtxos(s.rows.filter { it.isUsed }, s.endpoint, s.pinned)
                val changeIndex = s.rows.filter { it.chainIndex == 1 }.maxOfOrNull { it.index }?.plus(1) ?: 0
                val draft = withContext(cryptoDispatcher) {
                    SendPlanner.plan(to, amountSats, rate, utxos, SendPlanner.changeScript(zpub, changeIndex), sendAll)
                }
                scanner.verifyCoins(zpub, draft.inputs, s.endpoint, s.pinned)
                draft
            }.onSuccess { d -> if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d))) } }
             .onFailure { e -> if (e is CancellationException) throw e; if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the send.")) } }
        }
    }

    /** Unlock the words, sign with the unified sighash (0x21) and broadcast. */
    fun confirmSend() {
        val s = _state.value
        val draft = (s.send as? SendPhase.Review)?.draft ?: return
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT || pending(zpub) != null) return
        cancelAction()
        val epoch = actionEpoch
        // Set busy BEFORE authentication: rapid taps cannot queue multiple unlocks or broadcasts.
        _state.update { it.copy(send = SendPhase.Signing) }
        platform.seeds.load("Sign and send ${com.kilombino.kilowallet.core.wallet.formatSats(draft.amount)} sats") { secret, error ->
            if (!isCurrent(epoch, s)) return@load
            if (secret == null) {
                _state.update { it.copy(send = SendPhase.Review(draft, SendPlanner.feeConcern(draft))) }
                say(error ?: "Not unlocked: nothing was sent.")
                return@load
            }
            sendJob = scope.launch {
                try {
                    val (words, pass) = decodeSecret(secret)
                    val signed = withContext(cryptoDispatcher) { Signer.sign(draft, words, pass, zpub) }
                    coroutineContext.ensureActive()
                    if (!isCurrent(epoch, s)) return@launch
                    // Keep the exact signed transaction before the first send. A lost receipt
                    // must not invite a second, different payment.
                    prefs.put(pendingKey(zpub), signed.rawHex)
                    val txid = scanner.broadcast(signed.rawHex, s.endpoint, s.pinned)
                    prefs.put(pendingKey(zpub), null)
                    if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Sent(txid)) }
                    refresh()
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    if (isCurrent(epoch, s)) _state.update { it.copy(send = pending(zpub) ?: SendPhase.Failed(e.message ?: "Could not sign the transaction.")) }
                }
            }
        }
    }

    /** Retry only the already signed transaction, preserving its transaction ID and outputs. */
    fun retryPendingSend() {
        val s = state.value
        if (s.send !is SendPhase.Pending) return
        val zpub = s.zpub ?: return
        val raw = prefs.get(pendingKey(zpub)) ?: return
        cancelAction()
        val epoch = actionEpoch
        _state.update { it.copy(send = SendPhase.Signing) }
        sendJob = scope.launch {
            try {
                val id = scanner.resolveBroadcast(raw, s.endpoint, s.pinned)
                prefs.put(pendingKey(zpub), null)
                if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Sent(id)) }
                refresh()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { if (isCurrent(epoch, s)) _state.update { it.copy(send = pending(zpub)!!) } }
        }
    }

    fun editSend() {
        if (state.value.send is SendPhase.Signing) return
        cancelAction()
        _state.update { it.copy(send = SendPhase.Editing) }
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
                val zpub = withContext(cryptoDispatcher) { Signer.accountZpub(words, "") }
                _state.update { it.copy(kind = WalletKind.HOT, zpub = zpub, screen = Screen.Home) }
                when (screen) {
                    "home" -> scan()
                    "receive" -> { go(Screen.Receive); platform.markReady(screen) }
                    "settings" -> { go(Screen.Settings); platform.markReady(screen) }
                    "send" -> { go(Screen.Send); platform.markReady(screen) }
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

/** A payment URI or bare address. Throws for incompatible/ambiguous requests. */
fun parsePaymentText(text: String) = com.kilombino.kilowallet.core.wallet.PaymentRequest.parse(text)
fun btcToSats(s: String) = com.kilombino.kilowallet.core.wallet.PaymentRequest.toSats(s)
