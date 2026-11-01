package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.ark.Ark
import com.kilombino.kilowallet.core.ark.ArkDerive
import com.kilombino.kilowallet.app.ui.nowMillis
import com.kilombino.kilowallet.core.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ARK tab's side of the engine, ported from the Android app's `ui/ArkScreen.kt` and
 * `ui/ArkWords.kt`: turning Ark on (its own words, made from the wallet's), starting the engine,
 * the figures the screen draws, and the actions that move funds.
 *
 * iPhone differences: the engine runs while the app is open (and the short background time iOS
 * gives a payment or a renewal), so coins are renewed when the app opens and a local notification
 * asks to open it before the next coin expires; iOS may also give a background run to renew.
 */
class ArkModel(private val platform: AppPlatform) {
    private val ark = platform.ark

    /** False when this build has no Ark engine (desktop, screenshot demos). */
    val available: Boolean get() = ark != null
    /** ACTIVATE is offered: with the engine, or in the screenshot demos (which only show the screen). */
    val canActivate: Boolean get() = available || platform.demoScreen != null

    init {
        ark?.let { Ark.engine = it.engine; Ark.host = it.host }
    }

    sealed class View {
        /** Ark is off: the introduction and the warnings. */
        object Intro : View()
        object Starting : View()
        data class Failed(val message: String) : View()
        /** On, but no Ark wallet in the engine (words lost from the store, or a failed creation). */
        object NoWallet : View()
        object Ready : View()
    }

    data class Figures(
        val balance: Ark.Balance? = null,
        val expiry: Int? = null,
        val activity: List<Ark.Movement> = emptyList(),
    ) {
        /** Confirmations so far of a move into Ark still waiting for its third. */
        val boardConfs: Int? get() = activity.firstOrNull { it.kind == "move into Ark" && it.confirmations != null }?.confirmations
    }

    private val _view = MutableStateFlow<View>(View.Intro)
    val view: StateFlow<View> = _view.asStateFlow()
    private val _figures = MutableStateFlow(Figures())
    val figures: StateFlow<Figures> = _figures.asStateFlow()
    /** What is being done now, shown under the panels; null when idle. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    /** The last action's result ("Error: …" in red). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var demo = false

    fun enabled(): Boolean = available && Ark.enabled() && Ark.hasWords()

    /** Ark's words are made from the wallet's (true in the screenshot demos, which have no engine). */
    fun wordsDerived(): Boolean = !available || Ark.wordsDerived()

    /** The tab was opened: start the engine if Ark is on. */
    fun open() {
        if (demo) return
        if (!enabled()) { _view.value = View.Intro; return }
        if (_view.value == View.Ready || _view.value == View.Starting) return
        scope.launch { start() }
    }

    private suspend fun start() {
        _view.value = View.Starting
        _view.value = try {
            Ark.ensureStarted()
            if (Ark.hasWallet()) { Ark.syncOnchain(); View.Ready } else View.NoWallet
        } catch (e: Exception) { View.Failed(e.message ?: e.toString()) }
        if (_view.value == View.Ready) reload()
    }

    /** Fetches the figures again (the screen calls this every 30 s while it is shown). */
    suspend fun reload() = withContext(ioDispatcher) {
        if (demo || _view.value != View.Ready) return@withContext
        val b = runCatching { Ark.balanceChecked() }.getOrNull()
        val expiry = Ark.blocksToNearestExpiry()
        val activity = runCatching { Ark.activity() }.getOrDefault(emptyList())
        _figures.value = Figures(b, expiry, activity)
        remindBeforeExpiry(expiry)
    }

    fun syncNow() { if (!demo && _view.value == View.Ready) scope.launch { Ark.syncOnchain(); reload() } }

    /** Runs an action off the screen, with its label while it works and its result after. */
    fun run(label: String, block: suspend () -> String?) {
        scope.launch {
            _busy.value = label; _message.value = null
            val token = ark?.beginArkWork(label)
            _message.value = try { block() } catch (e: Exception) { "Error: " + (e.message ?: e.toString()) }
            ark?.endArkWork(token)
            _busy.value = null
            runCatching { reload() }
        }
    }

    fun say(text: String?) { _message.value = text }

    /** As [run], after Face ID / the passcode: anything that moves funds asks first, as BTC sends do. */
    fun secured(what: String, label: String, block: suspend () -> String?) {
        platform.seeds.load(what) { secret, error ->
            if (secret == null) _message.value = "Error: " + (error ?: "not unlocked")
            else run(label, block)
        }
    }

    // ---------------------------------------------------------------- turning Ark on

    /**
     * Turns Ark on with its own words, made from the BTC wallet's ([secret] is the wallet's words
     * and passphrase as the keychain keeps them): one set of words written down backs up both,
     * and what Ark keeps without Face ID only ever opens Ark.
     */
    fun activate(words: List<String>, passphrase: String) {
        create(ArkDerive.words(words, passphrase), "", derived = true, restoring = false)
    }

    /**
     * Restores an Ark wallet from its words: the server's recovery mailbox hands back its coins.
     * [derivedFrom] is set when the user typed the BTC wallet's words and chose an Ark made with
     * Kilowallet 1.1.4 or later, whose own words are made from them.
     */
    fun restore(words: List<String>, passphrase: String, derivedFrom: Boolean) {
        if (derivedFrom) create(ArkDerive.words(words, passphrase), "", derived = true, restoring = true)
        else create(words, passphrase, derived = false, restoring = true)
    }

    private fun create(words: List<String>, passphrase: String, derived: Boolean, restoring: Boolean) {
        scope.launch {
            _busy.value = if (restoring) "Restoring your Ark wallet… (this can take a minute)" else "Creating your Ark wallet…"
            _message.value = null
            try {
                withContext(ioDispatcher) {
                    // A previous attempt may have left a half-made wallet: start from an empty folder.
                    Ark.wipe()
                    Ark.ensureStarted()
                    Ark.createWallet(words, passphrase)
                }
                Ark.setWordsDerived(derived)
                Ark.setEnabled(true)
                ark?.scheduleBackgroundRenewal()
                _view.value = View.Ready
                if (restoring) _message.value = "Ark wallet restored: the server handed back the coins it holds for these words."
                reload()
            } catch (e: Exception) {
                _message.value = "Error: " + (e.message ?: e.toString())
                runCatching { Ark.stop() }
            }
            _busy.value = null
        }
    }

    /** Ark off on this phone: the engine stops and its folder and words go; the coins stay with the server. */
    /**
     * The Ark files for the backup file, and whether Ark's words are derived from the wallet's
     * ([words]/[passphrase]); null when there is no Ark here or it runs on other words (Android's
     * fullSnapshot keeps the file to the wallet's words then).
     */
    suspend fun backupFiles(words: List<String>, passphrase: String): Pair<Ark.Files, Boolean>? = withContext(ioDispatcher) {
        if (!available || !Ark.hasWords()) return@withContext null
        val arkWords = Ark.loadWords() ?: return@withContext null
        val derived = Ark.passphrase().isEmpty() && arkWords == ArkDerive.words(words, passphrase)
        val same = arkWords == words && Ark.passphrase() == passphrase
        if (!derived && !same) return@withContext null
        runCatching { Ark.snapshotFiles() }.getOrNull()?.let { it to derived }
    }

    /** Puts back the Ark wallet of a backup file and starts it (Android's Ark.restore). */
    fun restoreFromBackup(snap: com.kilombino.kilowallet.core.backup.Kab.Snapshot) {
        if (!available) { say("Wallet restored. The file also holds an Ark wallet, which this build can't open: keep the file."); return }
        scope.launch {
            val r = runCatching {
                val words = if (snap.arkDerived) ArkDerive.words(snap.words, snap.passphrase) else snap.words
                val pass = if (snap.arkDerived) "" else snap.passphrase
                Ark.restoreFiles(Ark.Files(snap.config!!, snap.db!!, snap.dbWal), words, pass, snap.arkDerived)
            }
            say(if (r.isSuccess) "Wallet and Ark restored from the backup file." else "Wallet restored; Ark did not: ${r.exceptionOrNull()?.message}")
            if (r.isSuccess) start()
        }
    }

    fun turnOff() {
        scope.launch {
            runCatching { Ark.wipe() }
            ark?.remind(null, "", "")
            _figures.value = Figures()
            _view.value = View.Intro
        }
    }

    // ---------------------------------------------------------------- the app opens

    /**
     * When the app opens with Ark on: start the engine so it renews coins close to expiry (it does
     * that by itself while running), renew now if one expires within a week, and set the reminder.
     */
    fun resume() {
        if (demo || !enabled()) return
        scope.launch {
            runCatching {
                Ark.ensureStarted()
                if (!Ark.hasWallet()) return@launch
                val left = Ark.blocksToNearestExpiry()
                if (left != null && left < RENEW_WITHIN_BLOCKS) {
                    val token = ark?.beginArkWork("Ark renewal")
                    runCatching { Ark.refreshAll() }
                    ark?.endArkWork(token)
                }
                remindBeforeExpiry(Ark.blocksToNearestExpiry())
                ark?.scheduleBackgroundRenewal()
            }
        }
    }

    /** A notification three days before the next coin expires (10 minutes a block), or none. */
    private fun remindBeforeExpiry(blocks: Int?) {
        val p = ark ?: return
        if (blocks == null) { p.remind(null, "", ""); return }
        val at = nowMillis() / 1000 + blocks * 600L - REMIND_BEFORE_S
        p.remind(at.coerceAtLeast(nowMillis() / 1000 + 60), "Open Kilowallet to renew your Ark coins",
            "An Ark coin expires in about 3 days. Opening the app renews it; if it expires, it can only leave on-chain with fees.")
    }

    // ---------------------------------------------------------------- screenshots

    /** CI screenshots: a made-up state, no engine. */
    fun setDemo(view: View, figures: Figures = Figures()) {
        demo = true
        _view.value = view
        _figures.value = figures
    }

    companion object {
        /** Renew when the app opens if a coin expires within about a week. */
        const val RENEW_WITHIN_BLOCKS = 1008
        private const val REMIND_BEFORE_S = 3 * 86_400L
    }
}
