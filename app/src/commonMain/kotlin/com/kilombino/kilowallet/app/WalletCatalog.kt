package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import kotlinx.serialization.json.*

data class WalletInfo(val id: String, val name: String, val kind: WalletKind, val zpub: String)
internal data class CatalogState(val wallets: List<WalletInfo> = emptyList(), val active: String? = null,
    val pending: WalletInfo? = null)

/** Public metadata only. A single versioned value commits the list and selection together. */
internal class WalletCatalog(private val prefs: Prefs) {
    var value: CatalogState = read()
        private set

    private fun read(): CatalogState {
        val raw = prefs.get(KEY)
        if (raw == null) {
            val kind = prefs.get("wallet.kind")
            val zpub = prefs.get("wallet.zpub")
            if (kind == null && zpub == null) return CatalogState()
            require(kind != null && zpub != null) { "Existing wallet metadata is incomplete." }
            Bip32.parseExtendedPubKey(zpub)
            // Keep the original Keychain account; migration never reads or rewrites words.
            val legacy = WalletInfo(LEGACY_ID, "Wallet 1", WalletKind.valueOf(kind), zpub)
            return CatalogState(listOf(legacy), legacy.id).also { prefs.put(KEY, encode(it)) }
        }
        val json = Json.parseToJsonElement(raw).jsonObject
        require(json["version"]!!.jsonPrimitive.int == 1) { "Unsupported wallet list version." }
        fun entry(element: JsonElement): WalletInfo {
            val o = element.jsonObject
            return WalletInfo(o["id"]!!.jsonPrimitive.content, cleanName(o["name"]!!.jsonPrimitive.content),
                WalletKind.valueOf(o["kind"]!!.jsonPrimitive.content), o["zpub"]!!.jsonPrimitive.content).also {
                Bip32.parseExtendedPubKey(it.zpub)
                require(it.id == LEGACY_ID || it.id == idFor(it.zpub)) { "Invalid wallet identity." }
            }
        }
        val wallets = json["wallets"]!!.jsonArray.map(::entry)
        val active = json["active"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        val pending = json["pending"]?.takeUnless { it is JsonNull }?.let(::entry)
        require(wallets.size <= LIMIT && wallets.map { it.id }.distinct().size == wallets.size &&
            wallets.map { idFor(it.zpub) }.distinct().size == wallets.size) { "Invalid wallet list." }
        require(if (wallets.isEmpty()) active == null else wallets.any { it.id == active }) { "Invalid selected wallet." }
        require(pending == null || (pending.kind == WalletKind.HOT && wallets.size < LIMIT &&
            wallets.none { it.id == pending.id || idFor(it.zpub) == idFor(pending.zpub) })) { "Invalid pending wallet." }
        return CatalogState(wallets, active, pending)
    }

    private fun encode(state: CatalogState): String {
        fun entry(w: WalletInfo) = buildJsonObject {
            put("id", w.id); put("name", w.name); put("kind", w.kind.name); put("zpub", w.zpub)
        }
        return buildJsonObject {
            put("version", 1)
            put("active", state.active?.let(::JsonPrimitive) ?: JsonNull)
            put("wallets", buildJsonArray { state.wallets.forEach { add(entry(it)) } })
            put("pending", state.pending?.let(::entry) ?: JsonNull)
        }.toString()
    }

    private fun save(next: CatalogState) { prefs.put(KEY, encode(next)); value = next }

    fun candidate(name: String, kind: WalletKind, zpub: String): WalletInfo {
        require(value.pending == null) { "Wallet setup is unfinished. Restart the app to recover it." }
        require(value.wallets.size < LIMIT) { "This device supports up to $LIMIT wallets." }
        require(value.wallets.none { idFor(it.zpub) == idFor(zpub) }) { "This wallet is already added. Select it from Wallets." }
        return WalletInfo(idFor(zpub), cleanName(name), kind, zpub)
    }
    fun begin(wallet: WalletInfo) { save(value.copy(pending = wallet)) }
    fun finish(select: Boolean = true) {
        val wallet = value.pending ?: return
        save(CatalogState(value.wallets + wallet, if (select) wallet.id else value.active ?: wallet.id))
    }
    fun abort() { save(value.copy(pending = null)) }
    fun addWatch(wallet: WalletInfo) { save(CatalogState(value.wallets + wallet, wallet.id)) }
    fun select(id: String) {
        require(value.wallets.any { it.id == id }) { "Wallet not found." }
        save(value.copy(active = id))
    }
    fun rename(id: String, name: String) {
        require(value.wallets.any { it.id == id }) { "Wallet not found." }
        val clean = cleanName(name)
        save(value.copy(wallets = value.wallets.map { if (it.id == id) it.copy(name = clean) else it }))
    }
    fun remove(id: String) {
        val remaining = value.wallets.filterNot { it.id == id }
        save(value.copy(wallets = remaining, active = if (value.active == id) remaining.firstOrNull()?.id else value.active))
    }
    companion object {
        const val KEY = "wallets.v1"
        const val LEGACY_ID = "wallet-0"
        const val LIMIT = 20
        fun nextName(wallets: List<WalletInfo>): String = (1..(LIMIT + 1)).map { "Wallet $it" }
            .first { candidate -> wallets.none { it.name.equals(candidate, ignoreCase = true) } }
        fun idFor(zpub: String): String {
            val key = Bip32.parseExtendedPubKey(zpub)
            // Fingerprint/depth metadata must not create aliases for the same addresses.
            return "wallet-" + Hashes.sha256(key.pubkey + key.chainCode + key.scriptType.name.encodeToByteArray()).toHex()
        }
        fun cleanName(name: String): String = name.trim().also {
            require(it.isNotEmpty() && it.length <= 40 && it.none { c -> c.code < 32 || c.code == 127 }) {
                "Use a wallet name of 1–40 characters."
            }
        }
    }
}
