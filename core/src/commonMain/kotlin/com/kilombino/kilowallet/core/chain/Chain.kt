package com.kilombino.kilowallet.core.chain

/**
 * The chain this wallet spends on. The Android app (`chain/Chain.kt`) also watches the SHA-256
 * chain; this iOS version 0.1 handles only the BLAKE2b proof-of-work chain, whose coin the app
 * calls BTC (XBT). Same addresses and genesis as Bitcoin: the fork changed the proof of work.
 */
object Chain {
    const val DISPLAY = "BLAKE2b"
    const val COIN = "BTC"
    const val TICKER = "XBT"
    /** The Android app's default Electrum server for this chain (Fulcrum with 164-byte headers, TLS). */
    const val DEFAULT_HOST = "fulcrum.kilombino.com"
    const val DEFAULT_PORT = 17717
    /** BLAKE2b proof of work from this block on. */
    const val BLAKE2B_FORK_HEIGHT = 961_640
    const val EXPLORER = "https://mempool.kilombino.com"
}

/** Where to reach the chain: the bundled default, or a server the user typed in. */
data class NodeEndpoint(val host: String, val port: Int, val isCustom: Boolean = false) {
    override fun toString() = "$host:$port"
    companion object {
        val DEFAULT = NodeEndpoint(Chain.DEFAULT_HOST, Chain.DEFAULT_PORT, false)
    }
}
