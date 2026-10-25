package com.kilombino.pyblockwatch.data

/** Stand-in for the Android app's scanner: CoinjoinPayment only names its coin type. */
class Scanner {
    data class SpendableUtxo(val txid: String, val vout: Int, val value: Long)
}
