package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.ln.Kilombino
import com.kilombino.kilowallet.core.ln.LnSeed
import com.kilombino.kilowallet.core.ln.Lnd
import com.kilombino.kilowallet.core.ln.LndError
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import com.kilombino.kilowallet.core.tor.Tor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile

/**
 * The LIGHTNING tab's side of the node, ported from the Android app's `ln/Lightning.kt`: whether
 * Lightning is on, bringing [Lnd] up (start, create or unlock its wallet, add the watchtower,
 * reach Kilombino's node), and a status the screen draws, refreshed while it is shown.
 *
 * Tor as Android 1.4.4: Arti starts before LND, which gets its SOCKS port (`tor.socks`, clearnet
 * peers still direct as on Android), so Kilombino's onion watchtower and node are reached. One
 * iPhone difference: with "Only over Tor" on (the default), the node waits for Tor and says so if
 * it doesn't come up, instead of starting without it after a minute.
 *
 * iPhone differences: the node only runs while the app is open (iOS gives a payment about 30 s
 * more after leaving, and payments still on their way are followed again on return); no
 * notifications with the app closed.
 */
class LightningModel(private val platform: AppPlatform) {
    private val ln = platform.lightning
    private val prefs = platform.prefs

    /** False when this build has no Lightning engine (desktop, screenshot demos). */
    val available: Boolean get() = ln != null

    init {
        ln?.let {
            Lnd.engine = it.engine; Lnd.host = it.host
            // Kilombino's bridge over https, as its web page. Android tries the onion first while Tor
            // runs; on iPhone that is plain http, which App Transport Security refuses without an
            // exception App Review asks about: https it is (Android falls back to it too).
            com.kilombino.kilowallet.core.ln.Bridge.http = { m, u, b, socks -> it.request(m, u, b, socks) }
            com.kilombino.kilowallet.core.ln.Bridge.torPort = { null }
            com.kilombino.kilowallet.core.ln.Bridge.nowMs = { it.host.nowSeconds() * 1000 }
            com.kilombino.kilowallet.core.ln.Bridge.sleepMs = { ms -> delay(ms) }
        }
    }

    sealed class Status {
        object Off : Status()
        /** [height] is how far the chain sync has got, when known (shown against the tip). */
        data class Starting(val step: String, val height: Int? = null) : Status()
        data class Running(
            val info: Lnd.Info, val balances: Lnd.Balances?, val channels: List<Lnd.Channel>,
            val pending: List<Lnd.Pending>, val tip: Int,
            val closing: List<Lnd.Closing> = emptyList(),
            val invoices: List<Lnd.Invoice> = emptyList(),
            val payments: List<Lnd.Payment> = emptyList(),
        ) : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Mutex()
    private var poller: Job? = null
    /** The chain tip the wallet's own server reports, to show how far the node has synced. */
    @Volatile var chainTip: Int = 0

    private fun pref(k: String) = prefs.get("ln.$k")
    private fun setPref(k: String, v: String?) = prefs.put("ln.$k", v)

    fun enabled() = pref("enabled") == "1"

    /** 2026-09-01 00:00 UTC, around the BLAKE2b activation (block 961640): no node of these words is older. */
    private val activationTime = 1788220800L

    /**
     * The password LND encrypts its wallet file with: random, made once, kept in the platform's
     * secure store (iOS Keychain, no Face ID: the node starts by itself).
     */
    private fun password(): ByteArray {
        val p = ln ?: throw LndError("Lightning is not available in this build")
        p.loadPassword()?.let { return it.encodeToByteArray() }
        val hex = secureRandomBytes(32).toHex()
        p.savePassword(hex)?.let { throw LndError("Could not keep the node's password: $it") }
        return hex.encodeToByteArray()
    }

    // ---------------------------------------------------------------- backup file (Android's exportBackup / stageRestore)

    private fun channelBackupPath(): String? = ln?.host?.dir?.let { "$it/data/chain/bitcoin/mainnet/channel.backup" }

    /**
     * What the backup file keeps of Lightning (Android's `Lightning.exportBackup`): the wallet's
     * birthday and LND's static channel backup (kept current by the node, encrypted with its keys).
     * Null when no node was ever made here.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun exportBackup(): JsonObject? {
        val birthday = pref("birthday")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val scb = channelBackupPath()?.let { p -> runCatching { ln?.host?.readBytes(p) }.getOrNull() }
        return kotlinx.serialization.json.buildJsonObject {
            put("birthday", kotlinx.serialization.json.JsonPrimitive(birthday))
            scb?.takeIf { it.isNotEmpty() }?.let { put("scb", kotlinx.serialization.json.JsonPrimitive(kotlin.io.encoding.Base64.Default.encode(it))) }
        }
    }

    /**
     * A backup file's Lightning part (Android's `stageRestore`): its birthday, and its channel backup
     * handed to the node when its wallet is made (turning Lightning on), so each peer is asked to close.
     */
    fun stageRestore(o: JsonObject) {
        (o["birthday"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { setPref("birthday", it.toString()) }
        (o["scb"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { setPref("restore_scb", it) }
    }

    /** A channel backup from a restored backup file waits for the node's wallet. */
    fun hasStagedChannels(): Boolean = pref("restore_scb") != null

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun stagedScb(): ByteArray? = pref("restore_scb")?.let { runCatching { kotlin.io.encoding.Base64.Default.decode(it) }.getOrNull() }

    // ---------------------------------------------------------------- name, fees, peers

    /** The node's name, which peers see: a random tag until one is chosen; takes effect on the next start. */
    fun alias(): String {
        pref("alias")?.let { return it }
        val a = "Kilowallet-" + secureRandomBytes(2).toHex()
        setPref("alias", a); return a
    }
    fun setAlias(a: String) = setPref("alias", a.trim().take(32).ifBlank { alias() })

    /** The routing fee a payment may cost at most: [feePercent] of it, never under [feeMinSats]. */
    fun feeLimit(sats: Long): Long = maxOf(feeMinSats(), (sats * feePercent() / 100.0).toLong())
    fun feePercent(): Double = pref("fee_pct")?.toDoubleOrNull() ?: 1.0
    fun feeMinSats(): Long = (pref("fee_min")?.toLongOrNull() ?: 10L).coerceIn(0, MAX_FEE_MIN)
    fun setFeeLimit(pct: Double, min: Long) { setPref("fee_pct", pct.toString()); setPref("fee_min", min.coerceIn(0, MAX_FEE_MIN).toString()) }

    fun peers(): List<String> = pref("peers")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: Lnd.DEFAULT_PEERS
    fun setPeers(peers: List<String>) = setPref("peers", peers.joinToString("\n"))

    // ---------------------------------------------------------------- payments

    /** A payment this app is waiting on: the PAY dialog may be gone, the app in the background. */
    data class Paying(val key: String, val sats: Long)
    private val _paying = MutableStateFlow<List<Paying>>(emptyList())
    val paying: StateFlow<List<Paying>> = _paying.asStateFlow()
    /** How the last payment started here ended, for the PAY card: (text, sats, paid). */
    private val _payResult = MutableStateFlow<Triple<String, Long, Boolean>?>(null)
    val payResult: StateFlow<Triple<String, Long, Boolean>?> = _payResult.asStateFlow()
    fun clearPayResult() { _payResult.value = null }
    private val payLock = Mutex()

    /**
     * Runs a payment outside the screen, so closing the dialog doesn't lose it, and asks iOS for
     * the little background time it gives when the app is left mid-payment. [key] is the payment
     * hash (or the offer): one payment per key at a time.
     */
    fun startPayment(key: String, sats: Long, pay: suspend () -> Unit) {
        scope.launch {
            val fresh = payLock.withLock {
                if (_paying.value.any { it.key == key }) false else { _paying.value = _paying.value + Paying(key, sats); true }
            }
            if (!fresh) return@launch
            val token = ln?.beginBackgroundWork("Lightning payment")
            val r = runCatching { pay() }
            payLock.withLock { _paying.value = _paying.value.filterNot { it.key == key } }
            ln?.endBackgroundWork(token)
            r.onSuccess { _payResult.value = Triple("", sats, true) }
                .onFailure { e -> _payResult.value = Triple(Lnd.payError(e), sats, false) }
            runCatching { refreshHere() }
        }
    }

    /**
     * Payments still on their way from an earlier run (LND keeps them across restarts, and on iPhone
     * the app may have been left mid-payment): followed to their end here, like one started now.
     */
    private fun followInFlight(payments: List<Lnd.Payment>) {
        if (_paying.value.isNotEmpty()) return
        for (p in payments.filter { it.status == 1 && it.hash.isNotBlank() })
            startPayment(p.hash, p.sats) { Lnd.track(p.hash) }
    }

    /** The last invoice paid to this node while the app was open: (memo, sats), for the screen. */
    private val _received = MutableStateFlow<Pair<String, Long>?>(null)
    val received: StateFlow<Pair<String, Long>?> = _received.asStateFlow()
    fun clearReceived() { _received.value = null }

    @Volatile private var invoiceWatch = false

    private fun watchInvoices() {
        if (invoiceWatch) return
        invoiceWatch = true
        Lnd.subscribeSettled(pref("settle_index")?.toLongOrNull() ?: 0L, onSettled = { memo, sats, index, _ ->
            if (index > (pref("settle_index")?.toLongOrNull() ?: 0L)) {
                setPref("settle_index", index.toString())
                _received.value = memo to sats
                scope.launch { runCatching { refreshHere() } }
            }
        }, onEnd = { invoiceWatch = false })
    }

    // ---------------------------------------------------------------- incoming on-chain (FUND)
    // The light node only learns of a payment once it is in a block (neutrino sees no mempool),
    // so for its FUND addresses the explorer is asked as well: an incoming payment shows at 0 confirmations.

    fun rememberFundAddress(a: String) {
        val list = (pref("fund_addrs") ?: "").lines().filter { it.isNotBlank() && it != a } + a
        setPref("fund_addrs", list.takeLast(30).joinToString("\n"))
    }

    /** Payments to the FUND addresses the explorer sees and the node does not yet. */
    @Volatile var incoming: List<Lnd.ChainTx> = emptyList()
        private set
    @Volatile private var lastIncoming = 0L

    private fun checkIncoming() {
        val now = Lnd.host.nowSeconds()
        if (now - lastIncoming < 60) return
        lastIncoming = now
        val addrs = (pref("fund_addrs") ?: "").lines().filter { it.isNotBlank() }
        if (addrs.isEmpty()) return
        val pending = ArrayList<Lnd.ChainTx>()
        for (a in addrs) {
            val arr = runCatching {
                val (code, body) = Lnd.host.http("GET", "https://mempool.kilombino.com/api/address/$a/txs", null)
                if (code != 200) null else Json.parseToJsonElement(body) as JsonArray
            }.getOrNull() ?: continue
            for (t in arr.map { it.jsonObject }) {
                val txid = t["txid"]?.jsonPrimitive?.contentOrNull ?: continue
                val amount = (t["vout"] as? JsonArray).orEmpty().map { it.jsonObject }
                    .filter { it["scriptpubkey_address"]?.jsonPrimitive?.contentOrNull == a }
                    .sumOf { it["value"]?.jsonPrimitive?.longOrNull ?: 0L }
                if (amount <= 0) continue
                val confirmed = (t["status"] as? JsonObject)?.get("confirmed")?.jsonPrimitive?.booleanOrNull == true
                if (!confirmed) pending += Lnd.ChainTx(txid, amount, 0, now, 0, "incoming")
            }
        }
        incoming = pending
    }

    // ---------------------------------------------------------------- on / off

    /** Turns Lightning on for the first time: the node's wallet is made from [bip39Seed] (the words, unlocked once). */
    suspend fun enable(bip39Seed: ByteArray) = lock.withLock {
        try {
            bringUp { state ->
                if (state == Lnd.WalletState.NON_EXISTING) {
                    _status.value = Status.Starting("creating the Lightning wallet")
                    // The same words may have run a node before: without a birthday from a backup
                    // file, the wallet looks from the chain's activation, so no coin of that node is missed.
                    val birthday = pref("birthday")?.toLongOrNull()?.takeIf { it > 0 } ?: activationTime
                    Lnd.initWallet(password(), LnSeed.masterXprv(bip39Seed), birthday, stagedScb())
                    setPref("birthday", birthday.toString()); setPref("restore_scb", null)
                }
            }
            setPref("expected_pubkey", LnSeed.nodePubkey(bip39Seed))
            setPref("enabled", "1"); setPref("moving", null)
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "Lightning did not start"); throw e
        } finally { bip39Seed.fill(0) }
    }

    /** Brings an enabled node up (once per process); a no-op when it already runs. */
    fun resume() {
        if (ln == null || !enabled()) { _status.value = Status.Off; return }
        // A move to another phone was under way when the app last stopped: this node must not come
        // up by itself (it may already run there). Turning Lightning on by hand clears it.
        if (pref("moving") == "1") { _status.value = Status.Off; return }
        scope.launch {
            lock.withLock {
                if (Lnd.running && _status.value is Status.Running) return@withLock
                runCatching { bringUp {} }.onFailure { _status.value = Status.Failed(it.message ?: "Lightning did not start") }
            }
        }
    }

    private suspend fun bringUp(onState: suspend (Lnd.WalletState) -> Unit) {
        _status.value = Status.Starting("starting the node")
        if (!Lnd.running) {
            if (Lnd.stoppedThisRun) throw LndError("The node was stopped: it starts again the next time the app opens.")
            val tor = torPort()
            torOn = tor != null
            Lnd.writeConfig(peers(), tor, alias())
            Lnd.start()
        }
        // The wallet RPC comes first; the main RPC once the wallet is open.
        var state = Lnd.state()
        repeat(90) { if (state == Lnd.WalletState.UNKNOWN || state == Lnd.WalletState.WAITING_TO_START) { delay(1000); state = Lnd.state() } }
        if (state == Lnd.WalletState.UNKNOWN) {
            Lnd.markStopped()
            throw LndError("The node stopped: " + (Lnd.lastError() ?: "no answer from it"))
        }
        onState(state)
        if (state == Lnd.WalletState.LOCKED) {
            _status.value = Status.Starting("opening the Lightning wallet")
            Lnd.unlock(password())
        }
        linked = false; towerAdded = false
        _status.value = Status.Starting("catching up with the chain")
        refreshHere()
    }

    /**
     * Tor before the node (Android's bringUp): its SOCKS port for LND, or null to start without
     * it. With "Only over Tor" on, it waits up to 3 minutes and then fails, saying why, rather
     * than start the node without Tor; with it off, as Android: a minute, then without Tor.
     */
    private suspend fun torPort(): Int? {
        if (!Tor.available) return null
        _status.value = Status.Starting("connecting to Tor")
        val err = Tor.start()
        if (err != null) {
            if (Tor.only) throw LndError(torFailed(err))
            return null
        }
        val until = Lnd.host.nowSeconds() + if (Tor.only) 180 else 60
        while (true) {
            if (Tor.running) return Tor.port
            if (!Tor.alive || Lnd.host.nowSeconds() >= until) break
            delay(500)
        }
        if (Tor.only) throw LndError(torFailed(Tor.lastLine.ifBlank { if (Tor.alive) "still connecting after 3 minutes" else "it stopped" }))
        return null
    }

    private fun torFailed(why: String) = "Tor did not start ($why). Lightning waits for Tor because \"Only over Tor\" is on: " +
        "nothing was sent without it. Try again, look at Tor's log in Settings, or turn \"Only over Tor\" off there to start without Tor."

    /** LND has Tor this run (its SOCKS port in the settings it started with). */
    @Volatile var torOn = false
        private set
    @Volatile private var towerAdded = false
    @Volatile private var linked = false

    /**
     * The watchtower line on the screen: Kilombino's, through Tor (Android's text), or why it isn't
     * reachable when the node runs without Tor.
     */
    val towerNote: String get() = when {
        torOn -> "A watchtower (Kilombino's, through Tor) watches your channels while the phone is off."
        Tor.available && Lnd.running -> "Watchtower: not reachable. The node started without Tor, and Kilombino's tower is an " +
            "onion service. Turn \"Only over Tor\" on in Settings (it starts with Tor next time), and keep the app opening every few days while you have channels."
        Tor.available -> "A watchtower (Kilombino's, through Tor) watches your channels while the phone is off."
        else -> "Watchtower: not reachable on iPhone yet. Kilombino's tower is an onion service and this " +
            "build has no Tor. Until it is, keep the app opening every few days while you have channels."
    }

    /**
     * Once the node answers, best effort (Android's link): the watchtower, once and only once the
     * node is fully up (AddTower holds the tower client's lock while it reaches the tower over Tor,
     * and LND's start waits on that same lock), then a link to Kilombino's node.
     */
    private suspend fun link(peers: Int) {
        if (torOn && !towerAdded && (peers > 0 || linked)) addTowerOnce()
        if (peers > 0) { linked = true; return }
        val now = Lnd.host.nowSeconds()
        if (now - lastLink < 30) return
        lastLink = now
        connectKilombino()
    }

    private suspend fun addTowerOnce() {
        if (pref("tower_added") == "1") { towerAdded = true; return }
        runCatching { Lnd.addTower(Kilombino.TOWER, Kilombino.TOWER_HOST) }.onSuccess {
            towerAdded = true; setPref("tower_added", "1")
        }
    }

    /** Connects to Kilombino's node (its name, then its onion when LND has Tor); true when connected. */
    suspend fun connectKilombino(): Boolean {
        val errors = ArrayList<String>()
        for (t in Lnd.resolved(Kilombino.HOSTS) + (if (torOn) listOf(Kilombino.ONION) else emptyList())) {
            val r = runCatching { Lnd.connect(Kilombino.NODE, t) }
            if (r.isSuccess) { linked = true; linkError = null; return true }
            errors += "$t: ${r.exceptionOrNull()?.message}"
        }
        linkError = errors.joinToString("\n")
        return false
    }

    /** Why the last attempt to reach Kilombino's node failed, to show under the channel. */
    @Volatile var linkError: String? = null
        private set
    @Volatile private var lastLink = 0L

    /** Re-reads everything the screen shows. */
    suspend fun refresh(): Unit = withContext(ioDispatcher) { refreshHere() }

    private suspend fun refreshHere() {
        if (!Lnd.running) return
        runCatching { checkIncoming() }
        runCatching {
            val info = Lnd.info()
            link(info.peers)
            val b = runCatching { Lnd.balances() }.getOrNull()
            val ch = runCatching { Lnd.channels() }.getOrDefault(emptyList())
            val p = runCatching { Lnd.pendingOpen() }.getOrDefault(emptyList())
            val cl = runCatching { Lnd.pendingClose() }.getOrDefault(emptyList())
            val inv = runCatching { Lnd.invoices() }.getOrDefault(emptyList())
            val pay = runCatching { Lnd.payments() }.getOrDefault(emptyList())
            _status.value = Status.Running(info, b, ch, p, chainTip, cl, inv, pay)
            watchInvoices(); followInFlight(pay)
        }.onFailure {
            // The wallet RPC can answer before LND has looked at its database: a locked wallet found here is opened here.
            if (Lnd.state() == Lnd.WalletState.LOCKED) runCatching { Lnd.unlock(password()) }
            if (_status.value !is Status.Running) _status.value = Status.Starting("catching up with the chain", Lnd.syncHeight())
        }
    }

    /** Refreshes every few seconds while the LIGHTNING tab is on screen. */
    fun watch(on: Boolean) {
        poller?.cancel(); poller = null
        if (!on) return
        poller = scope.launch {
            while (isActive) { runCatching { refresh() }; delay(5_000) }
        }
    }

    // ---------------------------------------------------------------- moving the node (Android's LnMove)

    /** This phone's node was moved to another phone: turn Lightning on here only if that one no longer runs it. */
    fun movedAway(): Boolean = pref("moved_away") == "1"

    /**
     * Stops the node and packs it (channels open) sealed with [password], checking the file opens
     * again. Marked "moving" first: if the app dies before the node is forgotten here, it won't
     * come up again by itself. The caller saves the file, then [forgetHere] or [cancelMove].
     */
    suspend fun moveOut(filePassword: String): ByteArray = withContext(ioDispatcher) {
        val p = ln ?: throw LndError("Lightning is not available in this build")
        require(filePassword.length >= com.kilombino.kilowallet.core.ln.LnMove.MIN_PASSWORD) { "The password needs at least ${com.kilombino.kilowallet.core.ln.LnMove.MIN_PASSWORD} characters." }
        setPref("moving", "1")
        watch(false)
        Lnd.stop()
        delay(6_000)    // LND lets go of its database files a little after StopDaemon answers
        val dir = p.host.dir
        val files = p.host.files(dir).map { it.removePrefix("$dir/") }
            .filterNot { com.kilombino.kilowallet.core.ln.LnMove.skip(it) }
            .map { it to (p.host.readBytes("$dir/$it") ?: ByteArray(0)) }
        val walletPassword = password()
        val pack = com.kilombino.kilowallet.core.ln.LnMove.pack(files, walletPassword, pref("birthday")?.toLongOrNull() ?: 0L, filePassword)
        check(com.kilombino.kilowallet.core.ln.LnMove.hasWallet(com.kilombino.kilowallet.core.ln.LnMove.entries(pack, filePassword))) { "The pack did not read back" }
        _status.value = Status.Off
        pack
    }

    /** The move did not happen (not saved): the node stays here, and starts again with the app. */
    fun cancelMove() = setPref("moving", null)

    /** After the move file is saved: the node no longer lives on this phone. */
    fun forgetHere() {
        ln?.let { it.host.delete(it.host.dir) }
        setPref("enabled", null); setPref("moving", null); setPref("tower_added", null); setPref("moved_away", "1")
        _status.value = Status.Off
    }

    /** Puts a node moved from another phone (Android or iPhone) in place and starts it. */
    suspend fun moveIn(pack: ByteArray, filePassword: String) = withContext(ioDispatcher) {
        val p = ln ?: throw LndError("Lightning is not available in this build")
        check(!Lnd.running) { "Lightning is running on this phone already" }
        if (Lnd.stoppedThisRun) throw LndError("Close the app and open it again first: the old node has to let go of its files.")
        val M = com.kilombino.kilowallet.core.ln.LnMove
        val files = M.entries(pack, filePassword)
        require(M.hasWallet(files)) { "This pack has no Lightning wallet" }
        val dir = p.host.dir
        p.host.delete(dir)
        for ((name, bytes) in files) {
            when (name) {
                M.PASSWORD_ENTRY -> p.savePassword(bytes.decodeToString())?.let { throw LndError("Could not keep the node's password: $it") }
                M.BIRTHDAY_ENTRY -> bytes.decodeToString().toLongOrNull()?.takeIf { it > 0 }?.let { setPref("birthday", it.toString()) }
                else -> { require(M.safePath(name)) { "bad entry $name" }; p.host.writeBytes("$dir/$name", bytes) }
            }
        }
        setPref("enabled", "1"); setPref("moved_away", null); setPref("moving", null); setPref("tower_added", null)
        resume()
    }

    /** Stops and deletes an empty node (the screen checks it holds nothing). */
    suspend fun deleteNode() {
        watch(false)
        Lnd.stop()
        delay(6_000)
        Lnd.host.delete(Lnd.host.dir)
        setPref("enabled", null); setPref("tower_added", null)
        _status.value = Status.Off
    }

    /** The end of LND's log, to share when something fails (it names peers and channels, never keys). */
    fun logTail(): String? = if (ln == null) null else Lnd.logTail()

    /** Screenshot demos: a SHA-256 invoice already priced by the bridge, to show its dialog. */
    var demoBridge: Pair<Lnd.PayReq, com.kilombino.kilowallet.core.ln.Bridge.Estimate>? = null

    /** Screenshot demos: a made-up status, to show each state of the screen. */
    fun setDemo(s: Status) { _status.value = s }

    companion object {
        /** The highest "at least" a user can set: a larger minimum would take the cap away for small payments. */
        const val MAX_FEE_MIN = 1_000L
    }
}
