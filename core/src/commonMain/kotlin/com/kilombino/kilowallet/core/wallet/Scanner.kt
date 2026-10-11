package com.kilombino.kilowallet.core.wallet

import com.kilombino.kilowallet.core.chain.CertificateChangedException
import com.kilombino.kilowallet.core.chain.ElectrumClient
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.chain.ScriptHashBalance
import com.kilombino.kilowallet.core.chain.TransportFactory
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.BlockHeader
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** One derived address and what the chain says about it. */
data class AddressRow(
    val chainIndex: Int,       // 0 = receive, 1 = change
    val index: Int,
    val address: String,
    val path: String,
    val scriptHash: String,
    val confirmed: Long = 0,
    val unconfirmed: Long = 0,
    val txCount: Int = 0,
) {
    val total: Long get() = confirmed + unconfirmed
    val isUsed: Boolean get() = txCount > 0 || total > 0
}

sealed interface ScanEvent {
    data class Connecting(val endpoint: NodeEndpoint) : ScanEvent
    data class Connected(val server: String, val height: Int, val fingerprint: String?) : ScanEvent
    data class Deriving(val chainIndex: Int, val index: Int, val path: String) : ScanEvent
    data class Found(val row: AddressRow) : ScanEvent
    data class GapProgress(val chainIndex: Int, val consecutiveEmpty: Int, val gapLimit: Int) : ScanEvent
    data class Done(val rows: List<AddressRow>, val height: Int, val txs: List<TxConf>, val fingerprint: String?) : ScanEvent
    data class Failed(val message: String) : ScanEvent
    /** The server's certificate is not the pinned one: nothing was asked; the user must check [fingerprint]. */
    data class CertificateChanged(val fingerprint: String, val message: String) : ScanEvent
}

/**
 * A wallet transaction: confirmations and what it did to this wallet (+ in, − out incl. fee).
 * [spv]: for a confirmed incoming payment, whether its merkle proof checked out against a header
 * with real proof of work (true), failed (false), or was not checked (null).
 */
data class TxConf(val txid: String, val confirmations: Int, val pending: Boolean, val amount: Long? = null,
                  val spv: Boolean? = null, val height: Int = 0)

/** A spendable output tagged with the derivation that unlocks it. */
data class SpendableUtxo(
    val txid: String, val vout: Int, val value: Long,
    val chainIndex: Int, val index: Int, val height: Int,
)

/**
 * Walks an xpub with a gap limit, as the Android app's `data/Scanner.kt` does (same calls in the
 * same order, same caches, same SPV check and coinbase-maturity rule).
 */
class Scanner(
    private val transports: TransportFactory,
    private val gapLimit: Int = 20,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = ioDispatcher,
) {
    private fun client(endpoint: NodeEndpoint, pin: String?) = ElectrumClient(endpoint, pin, transports)

    /**
     * Every server conversation runs here, one at a time: the caches below are plain maps (the
     * Android app uses ConcurrentHashMap, which Kotlin/Native lacks), and serialising also keeps
     * the wallet to a single connection at a time.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = dispatcher.limitedParallelism(1)

    fun scan(xpub: String, endpoint: NodeEndpoint, pinnedFingerprint: String?, gap: Int = gapLimit): Flow<ScanEvent> = flow {
        val txHeights = HashMap<String, Int>()
        val parsed = try {
            Bip32.parseExtendedPubKey(xpub)
        } catch (e: IllegalArgumentException) {
            emit(ScanEvent.Failed(e.message ?: "Invalid extended key")); return@flow
        }
        val scriptType = parsed.scriptType
        val purpose = purposeFor(scriptType)

        emit(ScanEvent.Connecting(endpoint))
        val client = client(endpoint, pinnedFingerprint)
        try {
            client.connect()
        } catch (e: CertificateChangedException) {
            emit(ScanEvent.CertificateChanged(e.fingerprint, e.message ?: "certificate changed")); return@flow
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ScanEvent.Failed(e.message ?: "Could not connect")); return@flow
        }

        val rows = mutableListOf<AddressRow>()
        try {
            val height = client.blockHeight()
            emit(ScanEvent.Connected(client.serverVersion ?: "unknown", height, client.serverFingerprint))
            for (chainIndex in 0..1) {
                val branch = Bip32.deriveChild(parsed, chainIndex)
                var index = 0
                var consecutiveEmpty = 0
                while (consecutiveEmpty < gap) {
                    require(index < 10_000) { "Address scan limit reached; use a trusted server" }
                    val path = "m/$purpose'/0'/0'/$chainIndex/$index"
                    emit(ScanEvent.Deriving(chainIndex, index, path))
                    val child = Bip32.deriveChild(branch, index)
                    val scriptHash = Address.scriptHashFor(child.pubkey(), scriptType)
                    val address = Address.encode(child.pubkey(), scriptType)
                    val hist = client.history(scriptHash)
                    val txs = hist.size
                    val bal = if (txs > 0) client.balance(scriptHash) else ScriptHashBalance(0, 0)
                    val row = AddressRow(chainIndex, index, address, path, scriptHash, bal.confirmed, bal.unconfirmed, txs)
                    if (row.isUsed) {
                        rows += row
                        hist.forEach { txHeights[it.txid] = it.height }
                        consecutiveEmpty = 0
                        emit(ScanEvent.Found(row))
                    } else {
                        consecutiveEmpty++
                        emit(ScanEvent.GapProgress(chainIndex, consecutiveEmpty, gap))
                    }
                    index++
                }
            }
            val txs = confirmations(client, txHeights, rows, height)
            emit(ScanEvent.Done(rows, height, txs, client.serverFingerprint))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ScanEvent.Failed(e.message ?: "The scan failed"))
        } finally {
            client.close()
        }
    }.flowOn(worker)

    private fun confirmations(client: ElectrumClient, txHeights: Map<String, Int>, rows: List<AddressRow>, tip: Int): List<TxConf> {
        val amounts = netAmounts(client, txHeights.keys, rows.map { it.scriptHash }.toSet())
        return txHeights.map { (id, h) ->
            val a = amounts[id]
            TxConf(id, if (h <= 0) 0 else tip - h + 1, pending = h <= 0, amount = a,
                spv = if (h > 0 && a != null && a > 0 && tip - h < SPV_WINDOW) spvCheck(client, id, h) else null,
                height = h)
        }.sortedWith(compareBy({ !it.pending }, { it.confirmations }))
    }

    /** Silent refresh of already-discovered addresses: status first, details only when it changed. */
    suspend fun refreshDetails(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): Triple<List<AddressRow>, List<TxConf>, Int> = withContext(worker) {
        val client = client(endpoint, pinnedFingerprint)
        try {
            client.connect()
            val tip = client.blockHeight()
            val txHeights = HashMap<String, Int>()
            val updated = rows.map { r ->
                val key = endpoint.toString() + "|" + r.scriptHash
                val status = client.status(r.scriptHash)
                val known = statusCache[key]?.takeIf { it.status == status }
                val entry = known ?: if (status == null) CachedAddress(null, emptyList(), ScriptHashBalance(0, 0))
                    else CachedAddress(status, client.history(r.scriptHash), client.balance(r.scriptHash))
                statusCache[key] = entry
                entry.history.forEach { txHeights[it.txid] = it.height }
                r.copy(confirmed = entry.balance.confirmed, unconfirmed = entry.balance.unconfirmed, txCount = entry.history.size)
            }
            Triple(updated, confirmations(client, txHeights, rows, tip), tip)
        } finally {
            client.close()
        }
    }

    /** What each transaction did to the wallet: outputs to our scripts minus our outputs it spends. */
    private fun netAmounts(client: ElectrumClient, txids: Collection<String>, ours: Set<String>): Map<String, Long> {
        val txs = txids.mapNotNull { id ->
            txCache[id]?.let { id to it }
                ?: runCatching { TxParse.parse(client.transaction(id)) }.getOrNull()?.also { txCache[id] = it }?.let { id to it }
        }.toMap()
        fun mine(o: TxParse.Out?) = o != null && Address.electrumScriptHash(o.scriptPubKey) in ours
        return txs.mapValues { (_, tx) ->
            tx.outputs.filter { mine(it) }.sumOf { it.value } -
                tx.inputs.sumOf { i -> txs[i.txid]?.outputs?.getOrNull(i.vout)?.takeIf { mine(it) }?.value ?: 0L }
        }
    }

    /** Every unspent output the wallet's addresses hold that a send can draw on (immature rewards left out). */
    suspend fun gatherUtxos(
        rows: List<AddressRow>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ): List<SpendableUtxo> = withContext(worker) {
        val client = client(endpoint, pinnedFingerprint)
        try {
            client.connect()
            val all = rows.flatMap { r ->
                client.listUnspent(r.scriptHash).map { SpendableUtxo(it.txid, it.vout, it.value, r.chainIndex, r.index, it.height) }
            }
            SendPlanner.validateCoins(all)
            val tip = client.blockHeight()
            all.filter { u ->
                if (u.height <= 0 || tip - u.height + 1 >= LONG_MATURITY) return@filter true
                val tx = txCache[u.txid] ?: runCatching { TxParse.parse(client.transaction(u.txid)) }.getOrNull()
                    ?.also { txCache[u.txid] = it } ?: return@filter false
                val coinbase = tx.inputs.size == 1 && tx.inputs[0].txid.all { it == '0' } && tx.inputs[0].vout == -1
                !coinbase || tip - u.height + 1 >= maturityFor(u.height)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Check every coin about to be spent against the transaction that created it (Android app:
     * `WalletViewModel.verifyCoins`): amount and script must be what the server listed, before
     * anything is signed.
     */
    suspend fun verifyCoins(
        xpub: String, coins: List<SpendableUtxo>, endpoint: NodeEndpoint, pinnedFingerprint: String?,
    ) = withContext(worker) {
        val parsed = Bip32.parseExtendedPubKey(xpub)
        val c = client(endpoint, pinnedFingerprint)
        try {
            c.connect()
            val txs = HashMap<String, TxParse.Tx>()
            for (u in coins) {
                val tx = txs.getOrPut(u.txid) { TxParse.parse(c.transaction(u.txid)) }
                val out = tx.outputs.getOrNull(u.vout)
                    ?: error("The server listed a coin that does not exist (${u.txid.take(8)}…:${u.vout}). Nothing was signed.")
                val script = Address.scriptPubKey(Bip32.derivePath(parsed, u.chainIndex, u.index).pubkey(), parsed.scriptType)
                require(out.value == u.value && out.scriptPubKey.contentEquals(script)) {
                    "The server's data about a coin (${u.txid.take(8)}…:${u.vout}) does not match the transaction itself. " +
                        "Nothing was signed; try another server."
                }
            }
        } finally { c.close() }
    }

    suspend fun broadcast(rawTxHex: String, endpoint: NodeEndpoint, pinnedFingerprint: String?): String =
        withContext(worker) {
            val client = client(endpoint, pinnedFingerprint)
            try { client.connect(); client.broadcast(rawTxHex) } finally { client.close() }
        }

    suspend fun resolveBroadcast(raw: String, endpoint: NodeEndpoint, pin: String?): String = withContext(worker) {
        val expected = TxParse.txid(TxParse.parse(raw))
        val client = client(endpoint, pin)
        try {
            client.connect()
            if (runCatching { client.transaction(expected) }.isSuccess) expected
            else client.broadcast(raw)
        } finally { client.close() }
    }

    /** A rough sats/vByte estimate from the server, floored by the relay minimum. Null if it fails. */
    suspend fun suggestedFeeRate(endpoint: NodeEndpoint, pinnedFingerprint: String?): Double? = withContext(worker) {
        val client = client(endpoint, pinnedFingerprint)
        try {
            client.connect()
            val rate = maxOf(client.estimateFeePerKb(3), client.relayFeePerKb())
            if (!rate.isFinite() || rate <= 0) null else (rate * 100_000.0).takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }

    /** Connect once and report the server's fingerprint and tip, for the server settings screen. */
    suspend fun probe(endpoint: NodeEndpoint, pinnedFingerprint: String?): Pair<String?, Int> = withContext(worker) {
        val client = client(endpoint, pinnedFingerprint)
        try { client.connect(); client.serverFingerprint to client.blockHeight() } finally { client.close() }
    }

    /**
     * SPV for one incoming payment: the merkle branch must lead from [txid] to the merkle root of
     * block [height]'s header, and that header must carry real proof of work at no less than
     * [SPV_FLOOR_BITS].
     */
    private fun spvCheck(client: ElectrumClient, txid: String, height: Int): Boolean? {
        val ok = runCatching {
            val header = BlockHeader.parse(client.blockHeader(height))
            val (branch, pos) = client.merkle(txid, height)
            BlockHeader.inBlock(txid, branch, pos, header) &&
                BlockHeader.meetsItsTarget(header) &&
                BlockHeader.compare(BlockHeader.target(header.bits), BlockHeader.target(SPV_FLOOR_BITS)) <= 0
        }.getOrNull() ?: return null
        return ok
    }

    private class CachedAddress(val status: String?, val history: List<ElectrumClient.HistoryItem>, val balance: ScriptHashBalance)
    private val statusCache = HashMap<String, CachedAddress>()
    private val txCache = HashMap<String, TxParse.Tx>()

    companion object {
        /** The easiest block a payment proof is accepted from (Android app value). */
        const val SPV_FLOOR_BITS = 0x191b0540L
        const val SPV_WINDOW = 144
        /** Coinbase maturity on the BLAKE2b chain: blocks 973 440–979 919 wait 6 480 confirmations. */
        const val LONG_MATURITY = 6480
        fun maturityFor(height: Int): Int = if (height in 973_440..979_919) LONG_MATURITY else 100

        fun purposeFor(type: ScriptType): Int = when (type) {
            ScriptType.P2PKH -> 44
            ScriptType.P2SH_P2WPKH -> 49
            ScriptType.P2WPKH -> 84
        }
    }
}
