package com.kilombino.kilowallet.core.wallet

/** Strict BIP21 parsing; same-looking addresses alone do not identify the chain. */
object PaymentRequest {
    const val MAX_REQUEST_SATS = 2_100_000_000_000_000L

    /** Optional integer-satoshi amount; use integer arithmetic so the QR never rounds a payment. */
    fun create(address: String, sats: Long? = null): String {
        require(address == address.trim() && com.kilombino.kilowallet.core.crypto.Address.isValid(address)) { "Invalid receive address" }
        if (sats == null) return address
        require(sats in 1..MAX_REQUEST_SATS) { "Enter a positive amount up to 21 million XBT" }
        val decimal = (sats / 100_000_000).toString() + "." + (sats % 100_000_000).toString().padStart(8, '0')
        return "bitcoin:$address?chain=xbt&amount=$decimal"
    }

    fun parse(text: String): Pair<String, Long?> {
        val t = text.trim()
        require(t.length <= 4096) { "Payment request is too long" }
        if (!t.startsWith("bitcoin:", ignoreCase = true)) {
            require(':' !in t && '?' !in t) { "Unsupported payment scheme" }
            return t to null
        }
        val rest = t.substring(8)
        require(!rest.startsWith("//")) { "Invalid bitcoin payment URI" }
        val params = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
            val pair = it.split('=', limit = 2)
            require(pair.size == 2 && '%' !in pair[0]) { "Invalid payment parameter" }
            pair[0].lowercase() to pair[1]
        }
        require(params.map { it.first }.distinct().size == params.size) { "Duplicate payment parameter" }
        require(params.none { it.first.startsWith("req-") }) { "Unsupported required payment parameter" }
        params.firstOrNull { it.first == "chain" }?.let {
            require(it.second.lowercase() == "xbt") { "This payment request is for another chain" }
        }
        val amount = params.firstOrNull { it.first == "amount" }?.let {
            toSats(it.second) ?: error("Invalid payment amount")
        }
        return rest.substringBefore('?') to amount
    }

    fun toSats(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty() || t == "." || t.count { it == '.' } > 1 || t.any { it !in "0123456789." }) return null
        val whole = t.substringBefore('.').ifEmpty { "0" }
        val frac = t.substringAfter('.', "")
        if (whole.length > 8 || frac.length > 8) return null
        return whole.toLong() * 100_000_000 + frac.padEnd(8, '0').toLong()
    }
}
