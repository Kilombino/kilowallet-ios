package com.kilombino.kilowallet.core.ark

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * Ark for Kilowallet, through the Paperclip Ark server, with chain data from the Esplora API of
 * mempool.kilombino.com (a phone cannot run a Knots node). Ported from the Android app's
 * `ark/Ark.kt` (1.4.4): the same REST calls to the same engine, the same quotes and checks.
 *
 * iPhone differences: the engine only runs while the app is open (and in the short background
 * time iOS gives), so coins are renewed when the app opens; no contacts; the backup file carries
 * the words (the server's recovery mailbox brings the coins back), not the engine's database.
 */
object Ark {
    const val SERVER = "https://ark.paperclippool.xyz"
    const val ESPLORA = "https://mempool.kilombino.com/api"

    // Limits advertised by the Paperclip Ark server (GetArkInfo, Oct 2026), for text shown before
    // the engine answers.
    const val VTXO_LIFETIME_BLOCKS = 4320
    const val MIN_BOARD_SAT = 20_000L
    /** Confirmations the server needs on a move into Ark before the coin is spendable. */
    const val BOARD_CONFIRMATIONS = 3
    const val MAX_VTXO_SAT = 1_000_000L
    const val MAX_LIGHTNING_SAT = 250_000L
    // Reserve per recovery transaction of an ordinary transfer (Paperclip 0.9.0 economical
    // transfers: 330-sat anchor + 198-sat miner fee), and the smallest output (330 dust + 1,000
    // claim allowance). See FUNDED-EXITS.md and docs/fees-0.9.0.md of the engine.
    const val RESERVE_SAT = 528L
    const val MIN_OUTPUT_SAT = 1_330L
    /** Blocks deep a withdrawal must be before the engine calls it done (bark's mainnet default). */
    const val OFFBOARD_CONFIRMATIONS = 2

    lateinit var engine: ArkEngine
    lateinit var host: ArkHost

    @Volatile private var port = 0
    @Volatile private var token: String? = null
    private val lock = Mutex()

    val running: Boolean get() = token != null

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    // ---------------------------------------------------------------- engine

    /** Starts the engine once. Blocking work inside: call from a background dispatcher. */
    suspend fun ensureStarted() = lock.withLock { startLocked() }

    private fun startLocked() {
        if (token != null) return
        val words = loadWords()?.joinToString(" ")
        val passphrase = passphrase()
        var lastErr = "no port"
        repeat(5) {
            // A random high port; the token, not the port, is what keeps other apps out.
            val p = Random.nextInt(20_000, 60_000)
            val r = engine.start(host.dir, p, words, passphrase)
            if (!r.startsWith("ERR:")) { port = p; token = r; return }
            lastErr = r.removePrefix("ERR:")
            if (!lastErr.contains("bind", ignoreCase = true)) throw IllegalStateException(lastErr)
        }
        throw IllegalStateException(lastErr)
    }

    suspend fun stop() = lock.withLock { stopLocked() }

    private fun stopLocked() {
        if (token == null) return
        engine.stop()
        token = null
    }

    /**
     * Removes the Ark wallet from this phone: the engine stops and its database and words go. The
     * coins stay with the server and come back from the words.
     */
    suspend fun wipe() = lock.withLock {
        stopLocked()
        host.delete(host.dir)
        host.deleteWords()
        for (k in PREFS) host.setPref(k, null)
    }

    /**
     * Restarts the engine with a recovery-mailbox scan first: the server's record of every coin
     * these words were given. It brings in coins this phone never recorded and marks as spent the
     * ones the server says are gone.
     */
    suspend fun rescanRecovery() {
        lock.withLock {
            host.writeText("${host.dir}/recovery-rescan", "1")
            stopLocked()
            startLocked()
        }
        setRejected(emptySet())
    }

    /** What the backup file (.kab) keeps of Ark: config.toml, the database and its WAL (Android's `Ark.snapshot`). */
    class Files(val config: String, val db: ByteArray, val dbWal: ByteArray?)

    /** The engine's files as they are with it stopped (it starts again after). Null when there is no Ark wallet here. */
    suspend fun snapshotFiles(): Files? = lock.withLock {
        if (!hasWords() || !host.exists("${host.dir}/db.sqlite")) return@withLock null
        val wasRunning = token != null
        stopLocked()
        try {
            val config = host.readText("${host.dir}/config.toml") ?: return@withLock null
            val db = host.readBytes("${host.dir}/db.sqlite") ?: return@withLock null
            val wal = host.readBytes("${host.dir}/db.sqlite-wal")?.takeIf { it.isNotEmpty() }
            Files(config, db, wal)
        } finally {
            if (wasRunning) runCatching { startLocked() }
        }
    }

    /**
     * Puts back an Ark wallet from a backup file (Android's `Ark.restore`): the engine's old files
     * go (its lock files stay), the file's config with this app's own server and chain source (a
     * crafted file must not point the engine elsewhere), its database, and Ark's words.
     */
    suspend fun restoreFiles(f: Files, arkWords: List<String>, arkPassphrase: String, derived: Boolean) = lock.withLock {
        stopLocked()
        host.list(host.dir).filter { it != "LOCK" && it != "barkd.lock" }.forEach { host.delete("${host.dir}/$it") }
        host.writeText("${host.dir}/config.toml", withOurServers(f.config))
        host.writeBytes("${host.dir}/db.sqlite", f.db)
        f.dbWal?.let { host.writeBytes("${host.dir}/db.sqlite-wal", it) }
        host.saveWords(arkWords.joinToString(" ") + if (arkPassphrase.isEmpty()) "" else "\n$arkPassphrase")
            ?.let { throw IllegalStateException("Could not keep the Ark words: $it") }
        setWordsDerived(derived)
        setEnabled(true)
    }

    /** [config] with this app's Ark server and chain source, and no other chain source (Android's `withOurServers`). */
    fun withOurServers(config: String): String = config.lines()
        .filterNot { it.trimStart().startsWith("bitcoind") }
        .joinToString("\n") { l ->
            when {
                l.trimStart().startsWith("server_address") -> "server_address = \"$SERVER\""
                l.trimStart().startsWith("esplora_address") -> "esplora_address = \"$ESPLORA\""
                else -> l
            }
        }

    class ArkError(val status: Int, message: String) : Exception(message)

    /** The engine words amounts as "0.00009000 BTC"; the wallet speaks in sats. */
    internal fun inSats(msg: String): String {
        val withSats = Regex("(\\d+\\.\\d{8}) BTC").replace(msg) { m ->
            val sats = m.groupValues[1].replace(".", "").trimStart('0').ifEmpty { "0" }.toLong()
            groupSats(sats) + " sats"
        }
        return withSats.trimEnd().let { if (it.endsWith(".")) it else "$it." }
    }

    private fun call(method: String, path: String, body: String? = null, timeoutMs: Int = 60_000): String {
        val t = token ?: throw IllegalStateException("Ark engine not started")
        val headers = mutableMapOf("Authorization" to "Bearer $t", "Accept" to "application/json")
        val payload = if (body != null || method == "POST") (body ?: "{}") else null
        if (payload != null) headers["Content-Type"] = "application/json"
        val (code, text) = host.http(method, "http://127.0.0.1:$port/api/v1$path", payload, headers, timeoutMs)
        if (code !in 200..299) {
            val msg = runCatching { obj(text).optString("message", text) }.getOrDefault(text)
            throw ArkError(code, inSats(msg).ifBlank { "HTTP $code" })
        }
        return text
    }

    private fun call(method: String, path: String, body: JBody, timeoutMs: Int = 60_000) = call(method, path, body.toString(), timeoutMs)

    // ---------------------------------------------------------------- wallet and words

    fun hasWallet(): Boolean = runCatching { call("GET", "/wallet/balance"); true }
        .getOrElse { e -> if (e is ArkError && e.status == 422) false else throw e }

    /**
     * Creates the Ark wallet from [words] ([ArkDerive.words] of the BTC wallet's, or new ones).
     * Words that already held Ark coins get them back from the server's recovery mailbox. The words
     * go to the platform's store first and to the engine in memory only.
     */
    fun createWallet(words: List<String>, passphrase: String = "") {
        require('\n' !in passphrase) { "the passphrase cannot contain a line break" }
        host.saveWords(words.joinToString(" ") + if (passphrase.isEmpty()) "" else "\n$passphrase")
            ?.let { throw IllegalStateException("Could not keep the Ark words: $it") }
        val req = JBody()
            .put("ark_server", SERVER)
            .put("chain_source", JBody().put("esplora", JBody().put("url", ESPLORA)))
            .put("network", "mainnet")
            .put("mnemonic", words.joinToString(" "))
            .apply { if (passphrase.isNotEmpty()) put("passphrase", passphrase) }
        try {
            call("POST", "/wallet/create", req, timeoutMs = 300_000)
        } catch (e: Exception) {
            host.deleteWords()
            throw e
        }
    }

    private fun loadText(): String? = host.loadWords()

    /** The Ark wallet's words, or null when there is no Ark wallet. */
    fun loadWords(): List<String>? = loadText()?.substringBefore('\n')?.trim()?.split(" ")?.filter { it.isNotEmpty() }

    fun hasWords(): Boolean = loadText() != null

    /** The Ark wallet's BIP-39 passphrase, "" for none. */
    fun passphrase(): String = loadText()?.let { if ('\n' in it) it.substringAfter('\n') else "" } ?: ""

    /** True when the Ark words are [ArkDerive.words] of the BTC wallet's. */
    fun wordsDerived(): Boolean = host.pref("ark.words_derived") == "1"
    fun setWordsDerived(on: Boolean) = host.setPref("ark.words_derived", if (on) "1" else null)

    /** Ark is turned on on this phone (the tab shows the wallet instead of the introduction). */
    fun enabled(): Boolean = host.pref("ark.enabled") == "1"
    fun setEnabled(on: Boolean) = host.setPref("ark.enabled", if (on) "1" else null)

    // ---------------------------------------------------------------- balance and addresses

    data class Balance(
        val spendable: Long,
        val pendingBoard: Long,
        val pendingRound: Long,
        val needsRefresh: Long,
        val pendingExit: Long,
        val onchainConfirmed: Long,
        val onchainPending: Long,
        /** Lightning payments received but not yet settled into Ark coins, and sends in flight. */
        val lightningPending: Long = 0,
        /** Coins the wallet still lists but the server says were spent already (stale state). */
        val refused: Long = 0,
    ) {
        val arkTotal: Long get() = spendable + pendingBoard + pendingRound
    }

    /** The balance without coins the server refused as already spent (leftovers of an older state). */
    fun balanceChecked(): Balance {
        val b = balance()
        val bad = rejected()
        if (bad.isEmpty()) return b
        val sum = runCatching { arkCoins().filter { it.id in bad }.sumOf { it.amount } }.getOrDefault(0L)
        return b.copy(spendable = (b.spendable - sum).coerceAtLeast(0), refused = sum)
    }

    fun balance(): Balance {
        val a = obj(call("GET", "/wallet/balance"))
        val o = runCatching { obj(call("GET", "/onchain/balance")) }.getOrNull()
        return Balance(
            spendable = a.optLong("spendable_sat"),
            pendingBoard = a.optLong("pending_board_sat"),
            pendingRound = a.optLong("pending_in_round_sat"),
            needsRefresh = a.optLong("needs_refresh_sat"),
            pendingExit = a.optLong("pending_exit_sat"),
            onchainConfirmed = o?.optLong("confirmed_sat") ?: 0,
            onchainPending = (o?.optLong("trusted_pending_sat") ?: 0) + (o?.optLong("untrusted_pending_sat") ?: 0),
            lightningPending = a.optLong("claimable_lightning_receive_sat") + a.optLong("pending_lightning_send_sat"),
        )
    }

    fun arkAddress(): String = obj(call("POST", "/wallet/addresses/next")).optString("address")

    /** On-chain BTC address to fund the wallet before moving the funds into Ark. */
    fun onchainAddress(): String = obj(call("POST", "/onchain/addresses/next")).optString("address")

    // ---------------------------------------------------------------- moving into Ark

    /** Moves [sats] of on-chain BTC into Ark at [feeRate] sat/vB (null: the regular estimate). */
    fun board(sats: Long, feeRate: Double? = null) {
        val req = JBody().put("amount_sat", sats)
        if (feeRate != null) req.put("fee_rate_sat_per_vb", feeRate)
        call("POST", "/boards/board-amount", req, 180_000)
    }

    /** Moves all the on-chain BTC into Ark, at [feeRate] sat/vB or the regular estimate. */
    fun boardAll(feeRate: Double? = null) {
        val req = JBody()
        if (feeRate != null) req.put("fee_rate_sat_per_vb", feeRate)
        call("POST", "/boards/board-all", req, 180_000)
    }

    /**
     * Network fee of a move into Ark funded by exactly [coins] (coin control), at [rate] sat/vB:
     * [sats] into Ark with change back to the deposit, or all of them when null. Exact: the engine
     * builds the funding transaction without signing it.
     */
    fun estimateBoardCoinsFee(coins: List<String>, sats: Long?, rate: Double): Long {
        val req = JBody().put("outpoints", jsonArray(coins)).put("fee_rate_sat_per_vb", rate)
        if (sats != null) req.put("amount_sat", sats)
        return obj(call("POST", "/boards/board-selected/estimate", req)).optLong("fee_sat")
    }

    /** Moves [sats] (null: all) of exactly [coins] into Ark, the funding transaction at [rate] sat/vB. */
    fun boardCoins(coins: List<String>, sats: Long?, rate: Double?) {
        val req = JBody().put("outpoints", jsonArray(coins))
        if (sats != null) req.put("amount_sat", sats)
        if (rate != null) req.put("fee_rate_sat_per_vb", rate)
        call("POST", "/boards/board-selected", req, 180_000)
    }

    /** The chain source's current fee rates in sat/vB: fast (~1 block), regular (~3), slow (~6). */
    data class FeeRates(val fast: Long, val regular: Long, val slow: Long)

    fun onchainFeeRates(): FeeRates? = runCatching {
        val r = obj(call("GET", "/fees/onchain"))
        FeeRates(r.optLong("fast_sat_per_vb"), r.optLong("regular_sat_per_vb"), r.optLong("slow_sat_per_vb"))
    }.getOrNull()

    /**
     * Network fee of a move into Ark at [rate] sat/vB: a taproot transaction spending every deposit
     * coin into the board output and change, or no change when [all] of it moves. An estimate.
     */
    fun estimateBoardNetworkFee(rate: Double, all: Boolean): Long {
        val inputs = runCatching { arr(call("GET", "/onchain/utxos")).size }.getOrDefault(1).coerceAtLeast(1)
        val vbytes = 10.5 + 57.5 * inputs + 43.0 * (if (all) 1 else 2)
        return ceil(vbytes * rate).toLong()
    }

    /**
     * Moving [sats] into Ark. The server quote leaves out what Paperclip documents: boarding costs
     * the greater of the quote or 1,000 sats (the anchor) plus a separate 1,000-sat miner fee.
     */
    fun estimateBoard(sats: Long): Estimate? = feeQuery("/fees/board?amount_sat=$sats")?.let {
        val fee = max(it.fee, 1_000L) + 1_000L
        Estimate(sats - fee, fee, sats, false, "Anchor and miner fee of the recovery transaction.")
    }

    // ---------------------------------------------------------------- paying

    /**
     * What an operation will really cost, shown BEFORE the user confirms. Paperclip runs a "funded
     * recovery profile": each Ark transfer reserves sats to pre-pay its on-chain emergency exit, and
     * they are not refunded. In sats it can be most of a small payment, so it must be shown.
     */
    data class Estimate(
        val amount: Long, val fee: Long, val total: Long, val exact: Boolean, val note: String,
        /** Set when the engine would refuse this payment as it stands; the UI then offers no send. */
        val problem: String? = null,
    )

    /** Sats encoded in a BOLT11 invoice's human-readable part (lnbc2500u…), or null. */
    fun invoiceSats(invoice: String): Long? {
        val m = Regex("^ln(?:bc|tb|bcrt)(\\d+)([munp]?)1", RegexOption.IGNORE_CASE).find(invoice.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "" -> n * 100_000_000
            "m" -> n * 100_000
            "u" -> n * 100
            "n" -> n / 10
            "p" -> n / 10_000
            else -> null
        }
    }

    private fun feeQuery(path: String): Estimate? = runCatching {
        val r = obj(call("GET", path))
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true, "")
    }.getOrNull()

    fun estimateSend(destination: String, sats: Long?): Estimate? {
        val d = destination.trim()
        return when {
            d.startsWith("lno", ignoreCase = true) && sats == null ->
                Estimate(0, 0, 0, false, "", "This is a reusable BOLT12 offer: enter the amount to pay.")
            d.startsWith("ln", ignoreCase = true) -> {
                val amt = sats ?: invoiceSats(d) ?: return null
                feeQuery("/fees/lightning/pay?amount_sat=$amt")?.let {
                    it.copy(note = "Lightning: the server's fee plus the recovery reserve of the transfer. If the " +
                        "payment fails, the amount comes back but the recovery reserve is NOT refunded, so " +
                        "each failed attempt costs about ${max(it.fee, 0)} sats.")
                }
            }
            d.startsWith("ark1", ignoreCase = true) -> estimateArkPayment(d, sats ?: return null, null)
            else -> {
                val addr = urlEncode(d)
                if (sats == null) {
                    feeQuery("/fees/offboard-all?address=$addr")?.let {
                        Estimate(it.amount, it.fee, it.total, true,
                            "Everything in Ark leaves to this address in the next round, minus the on-chain fee.")
                    }
                } else feeQuery("/fees/send-onchain?amount_sat=$sats&address=$addr")
                    ?.copy(note = "Leaves Ark through the next round and pays the on-chain fee.")
            }
        }
    }

    /**
     * The engine's own planner (Paperclip 0.7.7): the coins it will use and their recovery
     * reserves. With [coins] (coin control) it spends exactly those. Refused payments come back as
     * a problem, never a zero quote.
     */
    private fun estimateArkPayment(dest: String, amt: Long, coins: List<String>?): Estimate = try {
        val req = JBody().put("destination", dest).put("amount_sat", amt)
        if (coins != null) req.put("vtxos", jsonArray(coins))
        val r = obj(call("POST", "/fees/ark/send", req))
        val n = r.optInt("input_count", 1)
        Estimate(r.optLong("recipient_amount_sat"), r.optLong("recovery_reserve_sat") + r.optLong("service_fee_sat"),
            r.optLong("total_debit_sat"), true,
            "Recovery reserve pre-paid for the emergency exit of " +
                (if (n == 1) "the coin used" else "each of the $n coins used") + "; not refunded." +
                (if (n > 1) " RENEW first joins your coins into one and makes payments cheaper." else ""))
    } catch (e: ArkError) {
        Estimate(amt, 0, amt, false, "", (e.message ?: "The engine refused this payment.") +
            if (coins == null) arkPaymentHint() else "")
    }

    /** Ark payment spending exactly the chosen Ark [coins]: what it costs. */
    fun estimateArkCoins(dest: String, sats: Long, coins: List<String>): Estimate = estimateArkPayment(dest.trim(), sats, coins)

    /** Pays an Ark address spending exactly the chosen Ark [coins], capped at [maxTotal]. */
    fun sendArkCoins(dest: String, sats: Long, maxTotal: Long, coins: List<String>): String {
        val req = JBody().put("destination", dest.trim()).put("amount_sat", sats)
            .put("max_total_sat", maxTotal).put("vtxos", jsonArray(coins))
        return obj(call("POST", "/wallet/send", req, 180_000)).optString("message")
    }

    /** What to try when an Ark payment is refused: it must cover 3 reserves per coin and leave change of at least [MIN_OUTPUT_SAT]. */
    private fun arkPaymentHint(): String = runCatching {
        val biggest = arr(call("GET", "/wallet/vtxos")).objects()
            .filter { it.optObj("state")?.optString("type") == "spendable" }
            .maxOfOrNull { it.optLong("amount_sat") } ?: 0L
        val maxOne = biggest - 3 * RESERVE_SAT - MIN_OUTPUT_SAT
        if (maxOne >= MIN_OUTPUT_SAT) " The most you can pay from one coin is $maxOne sats: tick " +
            "Choose coins and pick that coin, or RENEW to join your coins into one."
        else " To empty Ark, send to a BTC address (on-chain) instead."
    }.getOrDefault("")

    /**
     * Pays an Ark address, a Lightning invoice / address / offer, a BTC address or a BIP-321 URI.
     * [sats] may be null when the destination carries the amount. [maxTotal] caps the debit at what
     * the user approved: the engine itself for Ark addresses, a fresh quote for everything else.
     */
    suspend fun send(destination: String, sats: Long?, maxTotal: Long? = null): String {
        val d = destination.trim()
        val req = JBody().put("destination", d)
        if (sats != null) req.put("amount_sat", sats)
        if (maxTotal != null && d.startsWith("ark1", ignoreCase = true)) req.put("max_total_sat", maxTotal)
        if (maxTotal != null && !d.startsWith("ark1", ignoreCase = true)) {
            val now = estimateSend(d, sats) ?: error("Could not confirm the cost right before paying; nothing was sent.")
            require(now.total <= maxTotal + max(20L, maxTotal / 100)) {
                "The cost went up since you reviewed it (${now.total} sats instead of at most $maxTotal); nothing was sent."
            }
        }
        if (!d.startsWith("ark1", ignoreCase = true) && !d.startsWith("ln", ignoreCase = true)) {
            // A BTC address leaves Ark on-chain: an amount, or everything when there is none.
            return if (sats == null) {
                call("POST", "/wallet/offboard/all", JBody().put("address", d), 180_000)
                "Withdrawal requested: everything leaves Ark in the next round."
            } else {
                call("POST", "/wallet/send-onchain", JBody().put("destination", d).put("amount_sat", sats), 180_000)
                "Withdrawal requested: it leaves Ark in the next round."
            }
        }
        val r = obj(call("POST", "/wallet/send", req, 180_000))
        val hash = r.optString("payment_hash").takeIf { it.length == 64 }
        // The engine answers "Payment sent successfully" as soon as a Lightning payment STARTS:
        // wait for its real outcome instead of telling the user it worked.
        if (hash == null || !d.startsWith("ln", ignoreCase = true)) return r.optString("message")
        return lightningOutcome(hash)
    }

    /** Follows a Lightning payment's movement until it settles or fails, for up to [waitMs]. */
    suspend fun lightningOutcome(hash: String, waitMs: Long = 90_000): String {
        val deadline = now() + waitMs
        while (now() < deadline) {
            val m = history().firstOrNull { it.paymentHash == hash }
            when (m?.status) {
                "successful" -> return "Lightning payment completed."
                "failed", "canceled", "cancelled" -> return "Error: the Lightning payment failed. The coins came back to " +
                    "Ark, but the recovery reserve it pre-paid (${abs(m.amount)} sats) is not refunded."
            }
            delay(3_000)
        }
        return "The Lightning payment is still in progress. Its result will show in Ark activity."
    }

    // ---------------------------------------------------------------- deposit (on-chain)

    /** A transaction of the on-chain deposit wallet; [height] is null while in the mempool. */
    data class DepositTx(val txid: String, val change: Long, val fee: Long?, val height: Int?)

    fun depositTxs(): List<DepositTx> = runCatching {
        arr(call("GET", "/onchain/transactions")).objects().map { t ->
            DepositTx(t.optString("txid"), t.optLong("balance_change_sat"),
                t.optLong("onchain_fee_sat", -1).takeIf { it >= 0 },
                t.optObj("confirmation")?.optInt("height"))
        }.reversed()
    }.getOrDefault(emptyList())

    /**
     * Sending straight from the deposit: plain on-chain BTC, no Ark reserves. The fee is an estimate
     * from the current rate and the size of a transaction spending every deposit coin.
     */
    fun estimateDepositSend(dest: String, sats: Long?, feeRate: Double? = null): Estimate? = runCatching {
        val d = dest.trim()
        if (d.startsWith("ark1", ignoreCase = true) || d.startsWith("ln", ignoreCase = true)) {
            return@runCatching Estimate(0, 0, 0, false, "",
                "From the deposit you can only send to a BTC address. Choose ARK to pay an Ark address or Lightning.")
        }
        val available = balance().onchainConfirmed
        val rate = (feeRate ?: obj(call("GET", "/fees/onchain")).let { r ->
            listOf("regular_sat_per_vb", "slow_sat_per_vb", "fast_sat_per_vb")
                .firstNotNullOfOrNull { k -> r.optDouble(k).takeIf { !it.isNaN() && it > 0 } } ?: 1.0
        }).coerceAtLeast(1.0)
        val inputs = runCatching { arr(call("GET", "/onchain/utxos")).size }.getOrDefault(1).coerceAtLeast(1)
        val outputs = if (sats == null) 1 else 2
        val fee = ceil(rate * (11 + 58 * inputs + 43 * outputs)).toLong()
        val amount = sats ?: (available - fee)
        val problem = when {
            amount < 330 -> "Not enough confirmed in the deposit to send."
            sats != null && sats + fee > available ->
                "The deposit has $available sats confirmed; this needs ${sats + fee} with the fee."
            else -> null
        }
        Estimate(amount, fee, amount + fee, false,
            "Plain on-chain BTC from your deposit: only the network fee (about ${oneDecimal(rate)} sat/vB), no Ark reserves.", problem)
    }.getOrNull()

    /** Asks for the cost again right before acting and refuses if it grew past what the user approved. */
    private fun recheck(maxTotal: Long?, now: () -> Estimate?) {
        if (maxTotal == null) return
        val e = now() ?: error("Could not confirm the cost right before sending; nothing was sent.")
        require(e.problem == null) { e.problem ?: "" }
        require(e.total <= maxTotal + max(20L, maxTotal / 100)) {
            "The cost went up since you reviewed it (${e.total} sats instead of at most $maxTotal); nothing was sent."
        }
    }

    fun sendFromDeposit(dest: String, sats: Long?, maxTotal: Long? = null, feeRate: Double? = null): String {
        recheck(maxTotal) { estimateDepositSend(dest, sats, feeRate) }
        val req = JBody().put("destination", dest.trim()).apply { if (feeRate != null) put("fee_rate_sat_per_vb", feeRate) }
        val r = if (sats == null) obj(call("POST", "/onchain/drain", req, 120_000))
        else obj(call("POST", "/onchain/send", req.put("amount_sat", sats), 120_000))
        return "Sent from the deposit: " + r.optString("txid").take(16) + "…"
    }

    /**
     * Speeds up an unconfirmed transaction of the deposit with a child paying for both to reach
     * [rate] sat/vB. Returns the child's fee in sats.
     */
    fun speedUp(txid: String, rate: Double): Long {
        val r = obj(call("POST", "/onchain/cpfp", JBody().put("txid", txid).put("fee_rate_sat_per_vb", rate), 120_000))
        syncOnchain()
        return r.optLong("fee_sat")
    }

    // ---------------------------------------------------------------- activity

    data class Movement(
        val id: String, val status: String, val kind: String, val amount: Long, val time: String,
        val fee: Long = 0,
        val destinations: List<String> = emptyList(),
        /** The on-chain transaction behind this movement, when there is one. */
        val onchainTxid: String? = null,
        /** Ark coins this movement created, or spent when it created none. */
        val vtxos: List<String> = emptyList(),
        val paymentHash: String? = null,
        val preimage: String? = null,
        /** For a move into Ark still waiting, or a deposit in the mempool: blocks deep so far. */
        val confirmations: Int? = null,
    )

    /** Plain names for the engine's movement subsystems. */
    private fun kindLabel(subsystem: String): String = when (subsystem) {
        "bark.board" -> "move into Ark"
        "bark.arkoor" -> "Ark payment"
        "bark.round" -> "renewal"
        "bark.offboard" -> "withdrawal"
        "bark.lightning_send" -> "Lightning payment"
        "bark.lightning_receive" -> "Lightning received"
        "bark.exit" -> "emergency exit"
        else -> subsystem.removePrefix("bark.")
    }

    fun history(): List<Movement> {
        val list = runCatching { arr(call("GET", "/history")) }.getOrNull() ?: return emptyList()
        return list.objects().map { m ->
            val meta = m.optObj("metadata")
            Movement(
                id = m.optString("id"),
                status = m.optString("status"),
                kind = kindLabel(m.optObj("subsystem")?.optString("name").orEmpty()).let { k ->
                    if (k == "Ark payment" && m.optLong("effective_balance_sat") > 0) "Ark received" else k
                },
                amount = m.optLong("effective_balance_sat", m.optLong("intended_balance_sat")),
                time = m.optObj("time")?.optString("created_at") ?: "",
                fee = m.optLong("offchain_fee_sat") + (meta?.optLong("onchain_fee_sat") ?: 0),
                destinations = m.optArr("sent_to")?.objects()?.mapNotNull { it.optObj("destination")?.optString("value") }.orEmpty(),
                onchainTxid = onchainTxid(m),
                vtxos = (m.optArr("output_vtxos")?.strings().orEmpty()).ifEmpty { m.optArr("input_vtxos")?.strings().orEmpty() },
                paymentHash = meta?.optString("payment_hash")?.takeIf { it.isNotEmpty() },
                preimage = meta?.optString("payment_preimage")?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * The blockchain transaction behind a movement: the deposit moved into Ark, the round of a
     * renewal or withdrawal, or the coin's own transaction for an emergency exit.
     */
    private fun onchainTxid(m: JsonObject): String? {
        val meta = m.optObj("metadata")
        meta?.optString("chain_anchor")?.takeIf { it.isNotEmpty() }?.let { return it.substringBefore(':') }
        for (k in listOf("funding_txid", "offboard_txid", "txid")) {
            meta?.optString(k)?.takeIf { it.length == 64 }?.let { return it }
        }
        if (m.optObj("subsystem")?.optString("name") == "bark.exit") {
            return m.optArr("input_vtxos")?.strings()?.firstOrNull()?.substringBefore(':')
        }
        return null
    }

    /** Confirmations of any transaction, from the chain source: 0 in the mempool, null if unknown. */
    private fun chainConfs(txid: String, tip: Int): Int? = runCatching {
        val (code, text) = host.http("GET", "$ESPLORA/tx/$txid/status", null, emptyMap(), 10_000)
        if (code != 200) return@runCatching null
        val o = obj(text)
        if (!o.optBoolean("confirmed")) 0 else if (tip < 0) null else (tip - o.optInt("block_height") + 1).coerceAtLeast(1)
    }.getOrNull()

    /**
     * Ark movements and the deposit's on-chain transactions in one list, newest first. A deposit
     * transaction that funded a move into Ark is left out: the move already shows. On-chain entries
     * have no clock time, only a block, so their time is estimated from it.
     */
    fun activity(): List<Movement> {
        val txs = depositTxs()
        val tip = runCatching { obj(call("GET", "/bitcoin/tip")).optInt("tip_height", -1) }.getOrDefault(-1)
        fun confs(txid: String?): Int? {
            val t = txs.firstOrNull { it.txid == txid } ?: return null
            return if (t.height == null) 0 else if (tip < 0) null else (tip - t.height + 1).coerceAtLeast(1)
        }
        val lost = lostExits()
        val moves = history().map { m ->
            when {
                m.kind == "move into Ark" && m.status != "successful" -> m.copy(confirmations = confs(m.onchainTxid))
                m.kind == "withdrawal" && m.status == "pending" && m.onchainTxid != null ->
                    m.copy(confirmations = chainConfs(m.onchainTxid, tip))
                m.kind == "emergency exit" && m.status == "pending" && m.vtxos.any { it in lost } -> m.copy(status = "lost")
                else -> m
            }
        }
        val anchored = moves.mapNotNull { it.onchainTxid }.toSet()
        val nowMs = now()
        val deposits = txs.filter { it.txid !in anchored && it.change != 0L }.map { t ->
            val ms = if (t.height == null || tip < 0) nowMs else nowMs - (tip - t.height).coerceAtLeast(0) * 600_000L
            Movement(
                id = "tx:" + t.txid,
                status = if (t.height == null) "unconfirmed" else "confirmed",
                kind = if (t.change > 0) "deposit received" else "sent from deposit",
                amount = t.change, time = isoUtc(ms),
                fee = t.fee ?: 0, onchainTxid = t.txid,
                confirmations = if (t.height == null) 0 else null,
            )
        }
        return (moves + deposits).sortedByDescending { it.time }
    }

    /**
     * Coins in an emergency exit that can no longer finish: the coin expired more than 144 blocks
     * ago and the exit never became claimable, so the server has swept that branch of its tree.
     */
    fun lostExits(): Set<String> = runCatching {
        val tip = obj(call("GET", "/bitcoin/tip")).optInt("tip_height", -1)
        if (tip < 0) return@runCatching emptySet<String>()
        val open = exits().filter { it.type != "claimable" && it.type != "claimed" && it.type != "claim-in-progress" }.map { it.vtxo }.toSet()
        arr(call("GET", "/wallet/vtxos")).objects()
            .filter { it.optString("id") in open && it.optInt("expiry_height", Int.MAX_VALUE) + 144 < tip }
            .map { it.optString("id") }.toSet()
    }.getOrDefault(emptySet())

    // ---------------------------------------------------------------- renewal

    /** Coins the server refused in a renewal ("input vtxo(s) not spendable"), remembered. */
    fun rejected(): Set<String> = host.pref("ark.server_rejected")?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    private fun setRejected(ids: Set<String>) = host.setPref("ark.server_rejected", ids.joinToString("\n").ifEmpty { null })

    /** The coins the last failed round says the server refused: the engine only reports it in its log. */
    private fun rejectedSince(sinceMs: Long): Set<String> {
        val log = engineLogTail(200_000) ?: return emptySet()
        val out = HashSet<String>()
        for (line in log.lines()) {
            val i = line.indexOf("unusable inputs: [")
            if (i < 0 || line.length < 20) continue
            val at = parseIsoUtc(line.substring(1, 20)) ?: continue
            if (at + 1000 < sinceMs) continue
            line.substring(i + 18).substringBefore(']').split(',').map { it.trim() }.filter { it.contains(':') }.forEach { out += it }
        }
        return out
    }

    /** Waits for the renewal that started after [known] movements to finish: its status, or null on time out. */
    private suspend fun awaitRenewal(known: Set<String>, timeoutMs: Long = 240_000): String? {
        val end = now() + timeoutMs
        while (now() < end) {
            delay(5_000)
            val m = history().firstOrNull { it.kind == "renewal" && it.id !in known } ?: continue
            if (m.status != "pending") return m.status
        }
        return null
    }

    /**
     * Renew [coins], and if the server refuses some of them, set those aside (remembered) and renew
     * the rest: one bad coin must not sink the round for all the others.
     */
    suspend fun renewSkippingRejected(coins: List<String>): String {
        setRejected(emptySet())
        var todo = coins
        val refused = LinkedHashSet<String>()
        repeat(3) {
            val known = history().filter { it.kind == "renewal" }.map { it.id }.toSet()
            val start = now()
            renewCoins(todo)
            when (awaitRenewal(known)) {
                "successful" -> return "Renewed ${todo.size} coin(s)." +
                    (if (refused.isNotEmpty()) " The server says ${refused.size} other coin(s) were already spent earlier (an older wallet state); they no longer count in your balance." else "")
                null -> return "Renewal requested; it completes in a coming round."
                else -> {
                    val bad = rejectedSince(start).filter { it in todo }
                    if (bad.isEmpty()) error("The renewal round failed. SHARE ENGINE LOG (below) says why.")
                    refused += bad; setRejected(rejected() + bad)
                    todo = todo - bad.toSet()
                    if (todo.isEmpty()) {
                        rescanRecovery()
                        error("The server says the chosen coins were already spent: they were renewed in an " +
                            "earlier round that this phone did not record. The wallet has now looked for what " +
                            "replaced them in the server's records; check your balance.")
                    }
                }
            }
        }
        error("The renewal kept failing. SHARE ENGINE LOG (below) says why.")
    }

    /** What renewing every coin costs now: the round fee, and what the new coin holds. */
    fun estimateRenew(): Estimate? = runCatching {
        val r = obj(call("GET", "/fees/refresh-all"))
        val coins = r.optArr("vtxos_spent")?.size ?: 0
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true,
            "$coins coin" + (if (coins == 1) "" else "s") + " renewed into one")
    }.getOrElse { e ->
        if (e !is ArkError) null
        else Estimate(0, 0, 0, false, "",
            if (e.message.orEmpty().contains("No VTXOs")) "You have no Ark coins to renew." else e.message)
    }

    fun estimateRenewCoins(coins: List<String>): Estimate? = runCatching {
        val r = obj(call("POST", "/fees/refresh", JBody().put("vtxos", jsonArray(coins))))
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true,
            "${coins.size} coin" + (if (coins.size == 1) "" else "s") + " renewed into one")
    }.getOrElse { e -> if (e is ArkError) Estimate(0, 0, 0, false, "", e.message) else null }

    fun renewCoins(coins: List<String>) { call("POST", "/wallet/refresh/vtxos", JBody().put("vtxos", jsonArray(coins)), 180_000) }

    /** Renews every coin close to expiry. The engine also does this by itself while running. */
    fun refreshAll() { call("POST", "/wallet/refresh/all", JBody(), 180_000) }

    /** Ask the engine to look at the chain now instead of at its next minute or block. */
    fun syncOnchain() { runCatching { call("POST", "/onchain/sync", JBody(), 60_000) } }

    // ---------------------------------------------------------------- coins

    /** One coin: an on-chain deposit output ("txid:vout") or an Ark coin (VTXO id). */
    data class Coin(val id: String, val amount: Long, val note: String, val expiresIn: Int? = null)

    fun depositCoins(): List<Coin> = runCatching {
        arr(call("GET", "/onchain/utxos")).objects().map { u ->
            val h = u.optInt("confirmation_height", -1)
            Coin(u.optString("outpoint"), u.optLong("amount_sat"), if (h > 0) "block $h" else "unconfirmed")
        }.sortedByDescending { it.amount }
    }.getOrDefault(emptyList())

    fun arkCoins(): List<Coin> = runCatching {
        val tip = obj(call("GET", "/bitcoin/tip")).optInt("tip_height", -1)
        arr(call("GET", "/wallet/vtxos")).objects()
            .filter { it.optObj("state")?.optString("type") == "spendable" }
            .map { v ->
                val left = v.optInt("expiry_height") - tip
                Coin(v.optString("id"), v.optLong("amount_sat"),
                    if (tip > 0) "expires in $left blocks (≈ ${oneDecimal(left * 10 / 1440.0)} d)" else "",
                    if (tip > 0) left else null)
            }.sortedByDescending { it.amount }
    }.getOrDefault(emptyList())

    /** Deposit send from exactly [coins]: [sats] to [dest], or all of them with no amount. */
    fun estimateDepositCoins(dest: String, sats: Long?, coins: List<String>, feeRate: Double? = null): Estimate? = runCatching {
        val req = JBody().put("destination", dest.trim()).put("outpoints", jsonArray(coins))
        if (sats != null) req.put("amount_sat", sats)
        if (feeRate != null) req.put("fee_rate_sat_per_vb", feeRate)
        val r = obj(call("POST", "/onchain/send-selected/estimate", req))
        val amount = r.optLong("amount_sat"); val fee = r.optLong("fee_sat"); val change = r.optLong("change_sat")
        Estimate(amount, fee, amount + fee, true,
            (if (coins.size == 1) "From 1 chosen coin; network fee only" else "From ${coins.size} chosen coins; network fee only") +
                (if (change > 0) "; $change sats of change back to the deposit." else "."))
    }.getOrElse { e -> if (e is ArkError) Estimate(0, 0, 0, false, "", e.message) else null }

    fun sendDepositCoins(dest: String, sats: Long?, coins: List<String>, maxTotal: Long? = null, feeRate: Double? = null): String {
        recheck(maxTotal) { estimateDepositCoins(dest, sats, coins, feeRate) }
        val req = JBody().put("destination", dest.trim()).put("outpoints", jsonArray(coins))
        if (sats != null) req.put("amount_sat", sats)
        if (feeRate != null) req.put("fee_rate_sat_per_vb", feeRate)
        val r = obj(call("POST", "/onchain/send-selected", req, 120_000))
        return "Sent from the chosen coins: " + r.optString("txid").take(16) + "…"
    }

    /** Withdrawing chosen Ark coins whole to a BTC address. */
    fun estimateWithdrawCoins(dest: String, coins: List<String>): Estimate? = runCatching {
        val r = obj(call("POST", "/fees/offboard", JBody().put("address", dest.trim()).put("vtxos", jsonArray(coins))))
        Estimate(r.optLong("net_amount_sat"), r.optLong("fee_sat"), r.optLong("gross_amount_sat"), true,
            "${coins.size} chosen Ark coin" + (if (coins.size == 1) "" else "s") + " leave Ark whole in the next round.")
    }.getOrElse { e -> if (e is ArkError) Estimate(0, 0, 0, false, "", e.message) else null }

    fun withdrawCoins(dest: String, coins: List<String>, maxTotal: Long? = null): String {
        recheck(maxTotal) { estimateWithdrawCoins(dest, coins) }
        call("POST", "/wallet/offboard/vtxos", JBody().put("address", dest.trim()).put("vtxos", jsonArray(coins)), 180_000)
        return "Withdrawal requested: the chosen coins leave Ark in the next round."
    }

    // ---------------------------------------------------------------- emergency exit

    data class ExitState(val vtxo: String, val type: String, val claimableHeight: Int?, val tip: Int?)

    fun exits(): List<ExitState> = runCatching {
        arr(call("GET", "/exits/status/all")).objects().map { e ->
            val st = e.optObj("state") ?: JsonObject(emptyMap())
            ExitState(e.optString("vtxo_id"), st.optString("type"),
                st.optInt("claimable_height").takeIf { it > 0 }, st.optInt("tip_height").takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())

    /** Sats of a field the engine words as BTC (a decimal) or as sats (an integer). */
    private fun sats(o: JsonObject, k: String): Long {
        val raw = o[k]?.toString() ?: return 0
        return if (raw.contains('.')) kotlin.math.round(o.optDouble(k) * 1e8).toLong() else o.optLong(k)
    }

    /** What leaving Ark without the server costs: broadcasting the exit chain, then the claim. */
    fun estimateExit(): Estimate? = runCatching {
        val b = balance()
        if (b.spendable == 0L) return@runCatching Estimate(0, 0, 0, false, "", "You have no Ark coins to exit.")
        val r = obj(call("GET", "/exits/fee"))
        val total = sats(r, "total_fee")
        val broadcast = sats(r, "exit_broadcast_fee")
        val deposit = b.onchainConfirmed
        Estimate(b.spendable - total, total, b.spendable, false,
            "broadcast $broadcast sats for " + r.optInt("txs_to_broadcast") + " transactions + claim " +
                sats(r, "claim_fee") + " sats",
            if (broadcast > deposit) "Broadcasting needs $broadcast sats confirmed in your on-chain deposit and " +
                "it has $deposit. Send that much to your DEPOSIT address first." else null)
    }.getOrElse { e -> if (e is ArkError) Estimate(0, 0, 0, false, "", e.message) else null }

    fun startExitAll(): String = obj(call("POST", "/exits/start/all", JBody(), 120_000)).optString("message", "Exit started.")

    fun claimExits(dest: String): String {
        call("POST", "/exits/claim/all", JBody().put("destination", dest.trim()), 120_000)
        return "Claimed: the recovered BTC is on its way to the address."
    }

    // ---------------------------------------------------------------- Lightning through the Ark server

    /** What arrives from a Lightning invoice of [sats] (receive fee + reserve deducted). */
    fun estimateLightningReceive(sats: Long): Estimate? =
        feeQuery("/fees/lightning/receive?amount_sat=$sats")?.copy(
            note = "The server's receive fee plus the recovery reserve are taken from what arrives.")

    /**
     * This wallet's reusable BOLT12 offer (lno1…), creating one if there is none. Payers ask the
     * wallet for a fresh invoice through the Ark server, so it is only paid while the engine runs.
     */
    fun reusableOffer(description: String, sats: Long?): String {
        runCatching { obj(call("GET", "/lightning/offers")) }.getOrNull()
            ?.takeIf { it.optBoolean("active") && it.optString("offer").isNotEmpty() }
            ?.let { if (sats == null || it.optLong("amount_sat") == sats) return it.optString("offer") }
        val req = JBody().put("description", description)
        if (sats != null) req.put("amount_sat", sats)
        return obj(call("POST", "/lightning/offers", req, 60_000)).optString("offer")
    }

    fun disableOffer() { call("DELETE", "/lightning/offers") }

    /** A Lightning invoice paid into this Ark wallet. */
    fun lightningInvoice(sats: Long, description: String?): String {
        val req = JBody().put("amount_sat", sats)
        if (!description.isNullOrBlank()) req.put("description", description)
        val text = call("POST", "/lightning/receives/invoice", req, 120_000)
        return obj(text).optString("invoice").ifEmpty { text }
    }

    /** Where a Lightning invoice made by this wallet stands, or null if the engine does not know it. */
    fun lightningReceiveState(invoice: String): String? = runCatching {
        obj(call("GET", "/lightning/receives/$invoice")).optString("state").ifBlank { null }
    }.getOrNull()

    // ---------------------------------------------------------------- chain and log

    /** The chain tip the engine sees (through its Esplora server), or null. */
    fun tip(): Int? = runCatching { obj(call("GET", "/bitcoin/tip")).optInt("tip_height", -1) }.getOrNull()?.takeIf { it > 0 }

    /** Blocks until the next spendable Ark coin expires, from the server's tip; null if unknown. */
    fun blocksToNearestExpiry(): Int? = runCatching {
        val vtxos = arr(call("GET", "/wallet/vtxos"))
        val tip = obj(call("GET", "/bitcoin/tip")).optInt("tip_height", -1)
        if (tip < 0 || vtxos.isEmpty()) return@runCatching null
        vtxos.objects()
            .filter { it.optObj("state")?.optString("type") == "spendable" }
            .mapNotNull { it.optInt("expiry_height", -1).takeIf { h -> h > 0 } }
            .minOrNull()?.let { it - tip }
    }.getOrNull()

    /**
     * The end of the Ark engine's own log (debug.log in its folder), to share when something fails.
     * It names coins and amounts, never the words; any line that mentions them is dropped anyway.
     */
    fun engineLogTail(maxBytes: Int = 48_000): String? = host.tail("${host.dir}/debug.log", maxBytes)
        ?.lines()?.drop(1)
        ?.filterNot { it.contains("mnemonic", true) || it.contains("seed", true) || it.contains("passphrase", true) }
        ?.joinToString("\n")

    /** The app-side settings [wipe] clears. */
    private val PREFS = listOf("ark.words_derived", "ark.enabled", "ark.server_rejected", "ark.renewed_at")

    /**
     * What to pay from a scanned or pasted code: an Ark address, Lightning invoice or offer, or BTC
     * address, also inside a `lightning:`/`bitcoin:` link. A BIP-21 link carrying an Ark address or a
     * Lightning code pays that (cheaper and instant) before its on-chain one.
     */
    fun scannedDestination(raw: String): String {
        var v = raw.trim()
        val scheme = v.substringBefore(':', "").lowercase()
        if (scheme == "lightning") return v.substringAfter(':').trim()
        if (scheme == "bitcoin") {
            val params = v.substringAfter('?', "").split('&').mapNotNull {
                val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0].lowercase() to kv[1] else null
            }.toMap()
            params["ark"]?.let { return it }
            params["lno"]?.let { return it }
            params["lightning"]?.let { return it }
            v = v.substringAfter(':').substringBefore('?')
        }
        return v.trim()
    }

    // ---------------------------------------------------------------- text helpers

    /** "12 345 678": thousands separated by spaces, as the Android app shows sats. */
    fun groupSats(n: Long): String {
        val s = abs(n).toString().reversed().chunked(3).joinToString(" ").reversed()
        return if (n < 0) "-$s" else s
    }

    /** Days that [blocks] take (10 minutes each), with one decimal: "26.9". */
    fun oneDecimalDays(blocks: Int): String = oneDecimal(blocks * 10 / 1440.0)

    internal fun oneDecimal(x: Double): String {
        val r = kotlin.math.round(x * 10).toLong()
        val sign = if (r < 0) "-" else ""
        return "$sign${abs(r) / 10}.${abs(r) % 10}"
    }

    internal fun urlEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '*'.code) append(c.toChar())
            else if (c == ' '.code) append('+')
            else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }

    /** "yyyy-MM-ddTHH:mm:ss" in UTC for [ms] after the epoch. */
    internal fun isoUtc(ms: Long): String {
        val secs = ms.floorDiv(1000L)
        val days = secs.floorDiv(86_400L)
        val rem = secs - days * 86_400L
        val (y, m, d) = civil(days)
        fun p(n: Long) = n.toString().padStart(2, '0')
        return "$y-${p(m.toLong())}-${p(d.toLong())}T${p(rem / 3600)}:${p(rem % 3600 / 60)}:${p(rem % 60)}"
    }

    /** Milliseconds for "yyyy-MM-ddTHH:mm:ss" (UTC), or null. */
    internal fun parseIsoUtc(s: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})").find(s) ?: return null
        val (y, mo, d, h, mi, se) = m.destructured
        return (daysFromCivil(y.toLong(), mo.toInt(), d.toInt()) * 86_400L + h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000
    }

    // H. Hinnant's civil_from_days / days_from_civil.
    private fun civil(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
    }

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
