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
import kotlinx.coroutines.cancel
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
    data object Wallets : Screen
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
    data class Review(val draft: SendDraft, val concern: String?, val changeIndex: Int? = null) : SendPhase
    data object Signing : SendPhase
    data class Pending(val txid: String, val message: String) : SendPhase
    data class Sent(val txid: String) : SendPhase
    data class Failed(val message: String) : SendPhase
}

data class UiState(
    val screen: Screen = Screen.Welcome,
    val wallets: List<WalletInfo> = emptyList(),
    val walletId: String? = null,
    val newWalletName: String = "Wallet 1",
    val settingUp: Boolean = false,
    val hideAmounts: Boolean = false,
    val notes: Map<String, TransactionNote> = emptyMap(),
    val lastSyncedAt: Long? = null,
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
    val walletName: String get() = wallets.firstOrNull { it.id == walletId }?.name ?: "Wallet"
    val confirmed: Long get() = rows.sumOf { it.confirmed }
    val unconfirmed: Long get() = rows.sumOf { it.unconfirmed }
    val total: Long get() = confirmed + unconfirmed
    val scriptType: ScriptType get() = zpub?.let { runCatching { Bip32.parseExtendedPubKey(it).scriptType }.getOrNull() } ?: ScriptType.P2WPKH
}

/**
 * The app's state and actions — a trimmed port of the Android app's `ui/WalletViewModel.kt` to
 * multiple independent wallets (hot from words, or watch-only from a zpub), the BLAKE2b chain,
 * Electrum only.
 */
class WalletModel(private val platform: AppPlatform, private val cryptoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    networkDispatcher: kotlinx.coroutines.CoroutineDispatcher = com.kilombino.kilowallet.core.platform.ioDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = Scanner(platform.transports, dispatcher = networkDispatcher)
    private val prefs = platform.prefs
    private val addresses = AddressBook(prefs)
    private val transactionNotes = TransactionNotes(prefs)
    private var exporting = false
    private var receiveSession: Pair<String, Int>? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    fun close() { cancelAction(); scope.cancel() }

    private var scanJob: Job? = null
    private var sendJob: Job? = null
    private var actionEpoch = 0L
    private var walletEpoch = 0L
    private var setupBusy = false
    private var catalog: WalletCatalog? = null

    private fun cancelAction() { actionEpoch++; sendJob?.cancel(); sendJob = null }
    private fun isCurrent(epoch: Long, snapshot: UiState) =
        epoch == actionEpoch && state.value.walletId == snapshot.walletId && state.value.zpub == snapshot.zpub && state.value.endpoint == snapshot.endpoint
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
        const val K_HIDE = "privacy.hideAmounts"
        const val K_HOST = "server.host"
        const val K_PORT = "server.port"
        fun pinKey(e: NodeEndpoint) = "pin.${e.host}:${e.port}"
    }

    init {
        val host = prefs.get(K_HOST)
        val port = prefs.get(K_PORT)?.toIntOrNull()
        val endpoint = if (host != null && port != null) NodeEndpoint(host, port, true) else NodeEndpoint.DEFAULT
        try {
            val saved = WalletCatalog(prefs)
            saved.value.pending?.let {
                if (platform.seeds.hasSeed(it.id)) saved.finish(select = false) else saved.abort()
            }
            catalog = saved
            val active = saved.value.wallets.firstOrNull { it.id == saved.value.active }
            _state.value = UiState(screen = if (active == null) Screen.Welcome else Screen.Home,
                wallets = saved.value.wallets, walletId = active?.id, kind = active?.kind, zpub = active?.zpub,
                endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
                newWalletName = WalletCatalog.nextName(saved.value.wallets), hideAmounts = prefs.get(K_HIDE) == "true")
            loadNotes()
            if (active != null) scan()
        } catch (e: Exception) {
            _state.value = UiState(endpoint = endpoint, pinned = prefs.get(pinKey(endpoint)),
                message = "Could not open the wallet list. Existing data was not changed. ${e.message ?: ""}")
        }
    }

    private fun showCatalog(scanSelected: Boolean = true) {
        val saved = catalog?.value ?: return
        cancelAction(); scanJob?.cancel(); walletEpoch++; receiveSession = null
        val active = saved.wallets.firstOrNull { it.id == saved.active }
        val previous = state.value
        _state.value = UiState(screen = if (active == null) Screen.Welcome else Screen.Home,
            wallets = saved.wallets, walletId = active?.id, kind = active?.kind, zpub = active?.zpub,
            endpoint = previous.endpoint, pinned = prefs.get(pinKey(previous.endpoint)),
            newWalletName = WalletCatalog.nextName(saved.wallets), hideAmounts = prefs.get(K_HIDE) == "true")
        loadNotes()
        if (scanSelected && active != null) scan()
    }

    private fun loadNotes() {
        val zpub = state.value.zpub ?: return
        runCatching { transactionNotes.read(zpub) }
            .onSuccess { notes -> _state.update { it.copy(notes = notes) } }
            .onFailure { say("Could not read local notes. Existing notes were not changed.") }
    }

    fun toggleHideAmounts() {
        val hide = !state.value.hideAmounts
        runCatching { prefs.put(K_HIDE, hide.toString()) }
            .onSuccess { _state.update { it.copy(hideAmounts = hide) } }
            .onFailure { say("Could not save the privacy setting.") }
    }

    fun saveTransactionNote(walletId: String, txid: String, label: String, note: String): Boolean {
        val s = state.value
        if (s.walletId != walletId || s.txs.none { it.txid == txid }) return false
        val zpub = s.zpub ?: return false
        return runCatching {
            val notes = transactionNotes.save(zpub, txid, label, note)
            _state.update { it.copy(notes = notes) }
            true
        }.getOrElse { say(it.message ?: "Could not save the note."); false }
    }

    fun exportHistory() {
        val s = state.value
        if (exporting || s.send is SendPhase.Signing) return
        val syncedAt = s.lastSyncedAt ?: run { say("Refresh this wallet successfully before exporting its history."); return }
        val zpub = s.zpub ?: return
        // Read storage again so unreadable notes cannot silently disappear from an export.
        val csv = runCatching { transactionCsv(s.walletName, s.txs, transactionNotes.read(zpub), syncedAt) }
            .getOrElse { say("Could not read local notes for export."); return }
        exporting = true
        try {
            platform.shareCsv("kilowallet-history.csv", csv) { error ->
                exporting = false
                if (error != null && state.value.walletId == s.walletId) say(error)
            }
        } catch (e: Exception) { exporting = false; say("Could not open the share sheet.") }
    }

    fun selectWallet(id: String) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        runCatching { requireNotNull(catalog).select(id); showCatalog() }
            .onFailure { say(it.message ?: "Could not select wallet.") }
    }

    fun renameWallet(id: String, name: String) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        runCatching {
            requireNotNull(catalog).rename(id, name)
            _state.update { it.copy(wallets = catalog!!.value.wallets) }
        }.onFailure { say(it.message ?: "Could not rename wallet.") }
    }

    fun addWallet() {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        if (catalog == null) { say("Wallet storage is unavailable. Restart the app before adding a wallet."); return }
        if (state.value.wallets.size >= WalletCatalog.LIMIT) { say("This device supports up to 20 wallets."); return }
        go(Screen.Welcome)
        _state.update { it.copy(newWalletName = WalletCatalog.nextName(it.wallets)) }
    }

    fun setNewWalletName(name: String) {
        if (!setupBusy) _state.update { it.copy(newWalletName = name.take(40)) }
    }

    fun go(screen: Screen) {
        if (setupBusy || _state.value.send is SendPhase.Signing) return
        cancelAction()
        if (screen in listOf(Screen.Wallets, Screen.Welcome, Screen.Restore, Screen.Watch) || screen is Screen.CreateShow || screen is Screen.CreateConfirm) {
            scanJob?.cancel(); walletEpoch++
            _state.update { it.copy(scan = ScanPhase.Idle, refreshing = false, certChanged = null) }
        }
        if (screen != Screen.Receive || state.value.screen != Screen.Receive) receiveSession = null
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
        if (clean.size !in setOf(12, 15, 18, 21, 24)) { say("Enter 12, 15, 18, 21 or 24 words."); return }
        clean.firstOrNull { !Bip39.isWord(it) }?.let { say("\"$it\" is not a recovery word."); return }
        if (!Bip39.isValid(clean)) { say("These words do not form a valid backup (the checksum does not match). Check their order."); return }
        storeHot(clean, passphrase)
    }

    private fun storeHot(words: List<String>, passphrase: String) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val saved = catalog ?: run { say("Wallet storage is unavailable. Restart the app."); return }
        val name = runCatching { WalletCatalog.cleanName(state.value.newWalletName) }
            .getOrElse { say(it.message ?: "Invalid wallet name."); return }
        setupBusy = true
        _state.update { it.copy(settingUp = true) }
        val epoch = walletEpoch
        scope.launch {
            var seedStored = false
            var staged = false
            try {
                val zpub = withContext(cryptoDispatcher) { Signer.accountZpub(words, passphrase) }
                if (epoch != walletEpoch) return@launch
                val candidate = saved.candidate(name, WalletKind.HOT, zpub)
                // Journal the public identity before saving words. A restart can finish the
                // commit without copying, replacing or revealing any existing seed.
                saved.begin(candidate); staged = true
                val err = suspendCancellableCoroutine { c ->
                    platform.seeds.save(candidate.id, encodeSecret(words, passphrase)) { if (c.isActive) c.resume(it) }
                }
                if (err != null) { saved.abort(); staged = false; say("Could not store the words: $err"); return@launch }
                seedStored = true
                saved.finish(select = epoch == walletEpoch); staged = false
                showCatalog(scanSelected = epoch == walletEpoch)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (staged && !seedStored) runCatching { saved.abort() }
                say(if (seedStored) "Words were saved safely. Restart the app to finish adding this wallet."
                    else e.message ?: "Could not create the wallet")
            } finally { setupBusy = false; _state.update { it.copy(settingUp = false) } }
        }
    }

    fun watch(xpub: String) {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val saved = catalog ?: run { say("Wallet storage is unavailable. Restart the app."); return }
        val key = xpub.trim()
        val parsed = runCatching { Bip32.parseExtendedPubKey(key) }.getOrElse { say(it.message ?: "Not a valid key."); return }
        runCatching {
            saved.addWatch(saved.candidate(state.value.newWalletName, WalletKind.WATCH, key))
            showCatalog()
            if (parsed.depth != 3) say("Note: this key is not at account depth (m/purpose'/0'/0').")
        }.onFailure { say(it.message ?: "Could not add watch-only wallet.") }
    }

    fun deleteWallet() {
        if (setupBusy || state.value.send is SendPhase.Signing) return
        val s = state.value
        val id = s.walletId ?: WalletCatalog.LEGACY_ID
        if (s.zpub?.let { pending(it) } != null) { say("Resolve the pending payment before removing this wallet."); return }
        cancelAction(); scanJob?.cancel(); walletEpoch++
        // Watch-only removal must never touch a hot wallet's Keychain item.
        if (s.kind == WalletKind.HOT) platform.seeds.delete(id)?.let { say("Could not remove the wallet: $it"); return }
        runCatching {
            requireNotNull(catalog).remove(id)
            val cleared = runCatching { s.zpub?.let(transactionNotes::clear) }
            showCatalog()
            if (cleared.isFailure) say("Wallet removed, but its local notes could not be cleared.")
        }
            .onFailure { say("Could not finish removing the wallet: ${it.message ?: "storage error"}") }
    }

    /** Show the recovery words after Face ID / Touch ID / passcode. */
    fun revealWords() {
        if (state.value.kind != WalletKind.HOT) return
        cancelAction()
        val epoch = actionEpoch
        val snapshot = state.value
        platform.seeds.load(snapshot.walletId ?: WalletCatalog.LEGACY_ID, "Show recovery words for ${snapshot.walletName}") { secret, error ->
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
            val progress = runCatching { addresses.read(zpub) }.getOrElse {
                _state.update { st -> st.copy(scan = ScanPhase.Error(it.message ?: "Invalid address progress"), refreshing = false) }
                return@launch
            }
            scanner.scan(zpub, s.endpoint, s.pinned, knownReceiveCount = progress.receive,
                knownChangeCount = progress.change).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
                if (ev is ScanEvent.Found) {
                    runCatching { addresses.observe(zpub, listOf(ev.row)) }.exceptionOrNull()?.let { error ->
                        _state.update { it.copy(scan = ScanPhase.Error(error.message ?: "Could not save address progress"), refreshing = false) }
                        throw CancellationException("Address progress could not be saved")
                    }
                }
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
                        _state.update { it.copy(scan = ScanPhase.Done, rows = ev.rows, txs = ev.txs, height = ev.height, lastSyncedAt = platform.nowMillis()) }
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
            val progress = runCatching { addresses.read(zpub) }.getOrElse {
                _state.update { st -> st.copy(scan = ScanPhase.Error(it.message ?: "Invalid address progress"), refreshing = false) }
                return@launch
            }
            scanner.scan(zpub, s.endpoint, s.pinned, knownReceiveCount = progress.receive,
                knownChangeCount = progress.change).collect { ev ->
                if (scanEpoch != walletEpoch) return@collect
                if (ev is ScanEvent.Found) {
                    runCatching { addresses.observe(zpub, listOf(ev.row)) }.exceptionOrNull()?.let { error ->
                        _state.update { it.copy(scan = ScanPhase.Error(error.message ?: "Could not save address progress"), refreshing = false) }
                        throw CancellationException("Address progress could not be saved")
                    }
                }
                when (ev) {
                    is ScanEvent.Done -> {
                        pinIfFirst(s.endpoint, ev.fingerprint)
                        _state.update { it.copy(rows = ev.rows, txs = ev.txs, height = ev.height, refreshing = false, scan = ScanPhase.Done, lastSyncedAt = platform.nowMillis()) }
                    }
                    is ScanEvent.Failed -> _state.update { it.copy(refreshing = false, scan = ScanPhase.Error(ev.message), message = ev.message) }
                    is ScanEvent.CertificateChanged -> _state.update {
                        it.copy(refreshing = false, scan = ScanPhase.Error(ev.message), certChanged = ev.fingerprint)
                    }
                    else -> Unit
                }
            }
            if (scanEpoch == walletEpoch) _state.update { it.copy(refreshing = false) }
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
        _state.update { it.copy(endpoint = e, pinned = prefs.get(pinKey(e)), rows = emptyList(), txs = emptyList(), lastSyncedAt = null) }
        if (_state.value.zpub != null) scan()
    }

    // ------------------------------------------------------------------ receive

    /** One address per Receive visit, stable across recomposition and reserved before display. */
    fun receiveAddress(): Pair<String, String>? {
        val s = _state.value
        val zpub = s.zpub ?: return null
        return runCatching {
            addresses.observe(zpub, s.rows)
            val cached = receiveSession?.takeIf { it.first == zpub }
            val progress = addresses.read(zpub)
            val reuseLatest = cached == null && progress.receive - progress.usedReceive >= AddressBook.GAP
            val index = cached?.second ?: if (reuseLatest) progress.receive - 1 else progress.receive
            val parsed = Bip32.parseExtendedPubKey(zpub)
            val pub = Bip32.derivePath(parsed, 0, index).pubkey()
            val address = Address.encode(pub, parsed.scriptType)
            if (cached == null) {
                if (reuseLatest) say("Showing your latest unused address. 20 unused addresses are already reserved; refresh after a payment to create another.")
                else addresses.reserve(zpub, 0, index)
                receiveSession = zpub to index
            }
            address to "m/${Scanner.purposeFor(parsed.scriptType)}'/0'/0'/0/$index"
        }.onFailure { say(it.message ?: "Could not reserve a receive address.") }.getOrNull()
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
                addresses.observe(zpub, s.rows)
                val changeIndex = addresses.next(zpub, 1)
                val draft = withContext(cryptoDispatcher) {
                    SendPlanner.plan(to, amountSats, rate, utxos, SendPlanner.changeScript(zpub, changeIndex), sendAll)
                }
                scanner.verifyCoins(zpub, draft.inputs, s.endpoint, s.pinned)
                if (draft.change > 0) addresses.checkAvailable(zpub, 1, changeIndex)
                draft to changeIndex.takeIf { draft.change > 0 }
            }.onSuccess { (d, index) -> if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Review(d, SendPlanner.feeConcern(d), index)) } }
             .onFailure { e -> if (e is CancellationException) throw e; if (isCurrent(epoch, s)) _state.update { it.copy(send = SendPhase.Failed(e.message ?: "Could not prepare the send.")) } }
        }
    }

    /** Unlock the words, sign with the unified sighash (0x21) and broadcast. */
    fun confirmSend() {
        val s = _state.value
        val review = s.send as? SendPhase.Review ?: return
        val draft = review.draft
        val zpub = s.zpub ?: return
        if (s.kind != WalletKind.HOT || pending(zpub) != null) return
        cancelAction()
        val epoch = actionEpoch
        // Set busy BEFORE authentication: rapid taps cannot queue multiple unlocks or broadcasts.
        _state.update { it.copy(send = SendPhase.Signing) }
        platform.seeds.load(s.walletId ?: WalletCatalog.LEGACY_ID, "Sign and send ${com.kilombino.kilowallet.core.wallet.formatSats(draft.amount)} sats from ${s.walletName}") { secret, error ->
            if (!isCurrent(epoch, s)) return@load
            if (secret == null) {
                _state.update { it.copy(send = review) }
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
                    review.changeIndex?.let { addresses.reserve(zpub, 1, it) }
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
                _state.update { it.copy(kind = WalletKind.HOT, zpub = zpub, walletId = WalletCatalog.LEGACY_ID,
                    wallets = listOf(WalletInfo(WalletCatalog.LEGACY_ID, "Everyday", WalletKind.HOT, zpub)), screen = Screen.Home) }
                when (screen) {
                    "wallets" -> {
                        val savings = withContext(cryptoDispatcher) { Signer.accountZpub(words, "demo-savings") }
                        val watch = withContext(cryptoDispatcher) { Signer.accountZpub(words, "demo-watch") }
                        _state.update { it.copy(wallets = it.wallets + listOf(
                            WalletInfo(WalletCatalog.idFor(savings), "Savings", WalletKind.HOT, savings),
                            WalletInfo(WalletCatalog.idFor(watch), "Watch-only", WalletKind.WATCH, watch))) }
                        go(Screen.Wallets); platform.markReady(screen)
                    }
                    "history", "private", "note", "export" -> {
                        val txid = "ab".repeat(32)
                        val note = TransactionNote("Mining payout", "October test payout")
                        val notes = transactionNotes.save(zpub, txid, note.label, note.note)
                        _state.update { it.copy(
                            rows = listOf(AddressRow(0, 0, "demo", "demo", "demo", confirmed = 250_000)),
                            txs = listOf(TxConf(txid, 12, false, 250_000, height = 970_000),
                                TxConf("cd".repeat(32), 0, true, -25_000)),
                            height = 970_011, scan = ScanPhase.Done, lastSyncedAt = platform.nowMillis(),
                            notes = notes, hideAmounts = screen == "private") }
                        platform.markReady(screen)
                    }
                    "home" -> scan()
                    "receive", "request" -> { go(Screen.Receive); platform.markReady(screen) }
                    "settings" -> { go(Screen.Settings); platform.markReady(screen) }
                    "send" -> { go(Screen.Send); platform.markReady(screen) }
                    "review" -> {
                        // A made-up coin, only so the review screen has something to show.
                        val coin = SpendableUtxo("4f".repeat(32), 0, 250_000, 0, 0, 970_000)
                        val draft = SendPlanner.plan("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 120_000, 2.0,
                            listOf(coin), SendPlanner.changeScript(zpub, 0))
                        _state.update { it.copy(screen = Screen.Send, send = SendPhase.Review(draft, SendPlanner.feeConcern(draft), 0)) }
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
