package com.kilombino.pyblockwatch.chain

/** Stand-in for the Android app's Tor: off, so its relay client connects directly. */
object Tor {
    val running: Boolean = false
    fun proxy(@Suppress("UNUSED_PARAMETER") i: Int): java.net.Proxy? = null
}
