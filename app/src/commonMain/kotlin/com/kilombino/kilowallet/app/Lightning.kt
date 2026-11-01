package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.ln.Kilombino
import com.kilombino.kilowallet.core.ln.LnSeed
import com.kilombino.kilowallet.core.ln.Lnd
import com.kilombino.kilowallet.core.ln.LndError
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.platform.secureRandomBytes
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
 * iPhone differences: no Tor (LND reaches peers over clearnet, so the onion watchtower can't be
 * reached yet: see [towerNote]); the node only runs while the app is open (iOS gives a payment
 * about 30 s more after leaving, and payments still on their way are followed again on return);
 * no notifications with the app closed.
 */
class LightningModel(private val platform: AppPlatform) {
    private val ln = platform.lightning
    private val prefs = platform.prefs

    /** False when this build has no Lightning engine (desktop, screenshot demos). */
    val available: Boolean get() = ln != null

    init {
        ln?.let { Lnd.engine = it.engine; Lnd.host = it.host }
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
                    Lnd.initWallet(password(), LnSeed.masterXprv(bip39Seed), birthday, null)
                    setPref("birthday", birthday.toString())
                }
            }
            setPref("expected_pubkey", LnSeed.nodePubkey(bip39Seed))
            setPref("enabled", "1")
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: "Lightning did not start"); throw e
        } finally { bip39Seed.fill(0) }
    }

    /** Brings an enabled node up (once per process); a no-op when it already runs. */
    fun resume() {
        if (ln == null || !enabled()) { _status.value = Status.Off; return }
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
            Lnd.writeConfig(peers(), null, alias())
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
        linked = false
        _status.value = Status.Starting("catching up with the chain")
        refreshHere()
    }

    @Volatile private var linked = false

    /**
     * The watchtower: Kilombino's (and the other towers this app knows) are onion services, and
     * this iPhone build has no Tor, so LND can't reach them yet. Shown on the screen so nobody
     * thinks the channels are watched while the app is closed.
     */
    val towerNote: String = "Watchtower: not reachable on iPhone yet. Kilombino's tower is an onion service and this " +
        "build has no Tor. Until it is, keep the app opening every few days while you have channels."

    /** With no peer at all, reach Kilombino's node again every half minute. */
    private suspend fun link(peers: Int) {
        if (peers > 0) { linked = true; return }
        val now = Lnd.host.nowSeconds()
        if (now - lastLink < 30) return
        lastLink = now
        connectKilombino()
    }

    /** Connects to Kilombino's node; true when connected. */
    suspend fun connectKilombino(): Boolean {
        val errors = ArrayList<String>()
        for (t in Lnd.resolved(Kilombino.HOSTS)) {
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

    /** Stops and deletes an empty node (the screen checks it holds nothing). */
    suspend fun deleteNode() {
        watch(false)
        Lnd.stop()
        delay(6_000)
        Lnd.host.delete(Lnd.host.dir)
        setPref("enabled", null)
        _status.value = Status.Off
    }

    /** The end of LND's log, to share when something fails (it names peers and channels, never keys). */
    fun logTail(): String? = if (ln == null) null else Lnd.logTail()

    /** Screenshot demos: a made-up status, to show each state of the screen. */
    fun setDemo(s: Status) { _status.value = s }

    companion object {
        /** The highest "at least" a user can set: a larger minimum would take the cap away for small payments. */
        const val MAX_FEE_MIN = 1_000L
    }
}
