package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.AddressRow

/** Public address high-water marks only. Never stores seeds or private keys. */
internal class AddressBook(private val prefs: Prefs) {
    internal data class Progress(val receive: Int = 0, val change: Int = 0,
        val usedReceive: Int = 0, val usedChange: Int = 0)
    private fun key(zpub: String) = "addresses.v1.$zpub"
    fun read(zpub: String): Progress {
        val raw = prefs.get(key(zpub))
        val progress = if (raw == null) {
            // The old value was an address which may already have been displayed.
            val legacy = prefs.get("receiveIndex.$zpub")
            val next = if (legacy == null) 0 else (legacy.toIntOrNull()
                ?.takeIf { it in 0 until LIMIT } ?: error("Saved address progress is invalid.")) + 1
            Progress(receive = next)
        } else {
            val values = raw.split(":").map { it.toIntOrNull() ?: error("Saved address progress is invalid.") }
            require(values.size == 4) { "Saved address progress is invalid." }
            Progress(values[0], values[1], values[2], values[3])
        }
        require(listOf(progress.receive, progress.change, progress.usedReceive, progress.usedChange).all { it in 0..LIMIT }
            && progress.usedReceive <= progress.receive && progress.usedChange <= progress.change) {
            "Saved address progress is invalid."
        }
        return progress
    }
    private fun save(zpub: String, p: Progress) = prefs.put(key(zpub),
        listOf(p.receive, p.change, p.usedReceive, p.usedChange).joinToString(":"))

    fun observe(zpub: String, rows: List<AddressRow>) {
        val previous = read(zpub)
        rows.forEach { require(it.chainIndex in 0..1 && it.index in 0 until LIMIT) }
        val receive = maxOf(previous.usedReceive, rows.filter { it.chainIndex == 0 && it.isUsed }.maxOfOrNull { it.index + 1 } ?: 0)
        val change = maxOf(previous.usedChange, rows.filter { it.chainIndex == 1 && it.isUsed }.maxOfOrNull { it.index + 1 } ?: 0)
        val next = Progress(maxOf(previous.receive, receive), maxOf(previous.change, change), receive, change)
        if (next != previous) save(zpub, next)
    }
    fun next(zpub: String, chain: Int): Int {
        require(chain in 0..1)
        val p = read(zpub)
        return if (chain == 0) p.receive else p.change
    }
    fun checkAvailable(zpub: String, chain: Int, expected: Int) {
        require(chain in 0..1)
        val p = read(zpub)
        val next = if (chain == 0) p.receive else p.change
        val used = if (chain == 0) p.usedReceive else p.usedChange
        require(expected == next && next < LIMIT) { "Address progress changed. Refresh and try again." }
        require(next - used < GAP) {
            "20 unused ${if (chain == 0) "receive" else "change"} addresses have already been reserved. Refresh after a payment uses one before creating more."
        }
    }
    /** Durable reservation precedes display or broadcast; failed writes never return an address. */
    fun reserve(zpub: String, chain: Int, expected: Int) {
        checkAvailable(zpub, chain, expected)
        val p = read(zpub)
        save(zpub, if (chain == 0) p.copy(receive = expected + 1) else p.copy(change = expected + 1))
    }
    companion object { const val GAP = 20; const val LIMIT = 10_000 }
}
