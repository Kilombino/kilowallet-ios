package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.kilojoin.BigNat
import com.kilombino.kilowallet.core.platform.secureRandomBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * What the Android app takes from the JVM and Kotlin/Native lacks, kept to what the taker uses:
 * a CSPRNG as a [Random], base64 as `java.util.Base64`, decimals as the bits of
 * `java.math.BigDecimal` the fee maths needs, org.json-style lenient reads, and clocks.
 */
internal object SecureRng : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        val b = secureRandomBytes(4)
        val v = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
        return v ushr (32 - bitCount)
    }
}

/** The CSPRNG, for callers that pass a [Random] (tests pass a seeded one). */
val JbSecureRandom: Random get() = SecureRng

@OptIn(ExperimentalEncodingApi::class)
internal object B64 {
    private val enc = Base64.Default
    /** java.util.Base64's basic decoder: standard alphabet, `=` padding accepted but not required. */
    private val dec = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    fun encode(b: ByteArray): String = enc.encode(b)
    /** Throws IllegalArgumentException on anything that is not base64. */
    fun decode(s: String): ByteArray = try { dec.decode(s) } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) {
        throw IllegalArgumentException(e.message ?: "not base64", e)
    }
}

/** Monotonic milliseconds, for timeouts and quiet periods. */
private val ORIGIN = TimeSource.Monotonic.markNow()
internal fun monoMillis(): Long = ORIGIN.elapsedNow().inWholeMilliseconds

/** Wall-clock seconds since the epoch (bond values). */
@OptIn(kotlin.time.ExperimentalTime::class)
fun jbNowSeconds(): Long = kotlin.time.Clock.System.now().epochSeconds

/** org.json-like reads over kotlinx.serialization (numbers and strings coerced as org.json does). */
internal object JbJson {
    fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    fun prim(o: JsonObject, k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    fun has(o: JsonObject, k: String) = o.containsKey(k)
    fun getString(o: JsonObject, k: String): String = prim(o, k)?.content ?: throw IllegalArgumentException("missing $k")
    fun optString(o: JsonObject, k: String): String = prim(o, k)?.content ?: ""
    fun getInt(o: JsonObject, k: String): Int {
        val c = prim(o, k)?.content ?: throw IllegalArgumentException("missing $k")
        return c.toIntOrNull() ?: c.toDouble().toInt()
    }
    fun optInt(o: JsonObject, k: String, def: Int): Int = runCatching { getInt(o, k) }.getOrDefault(def)
    fun getBoolean(o: JsonObject, k: String): Boolean = when (prim(o, k)?.content?.lowercase()) {
        "true" -> true; "false" -> false; else -> throw IllegalArgumentException("not a boolean: $k")
    }
    fun optBoolean(o: JsonObject, k: String, def: Boolean): Boolean = runCatching { getBoolean(o, k) }.getOrDefault(def)
    /** True only for a JSON boolean `true` (org.json's `opt(k) == true`). */
    fun isTrue(e: JsonElement?): Boolean = e is JsonPrimitive && !e.isString && e.content == "true"
}

/** Constant-time comparison (MessageDigest.isEqual). */
internal fun ctEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
    return d == 0
}

/**
 * A non-negative decimal (unscaled value, scale), as much of java.math.BigDecimal as the fee
 * maths uses: parsing (also "1E-5"), comparison by value, plain formatting, multiplication by an
 * integer with round-half-even to an integer, and sums.
 */
internal class Dec private constructor(val unscaled: BigNat, val scale: Int) : Comparable<Dec> {
    override fun compareTo(other: Dec): Int {
        val s = maxOf(scale, other.scale)
        return (unscaled * pow10(s - scale)).compareTo(other.unscaled * pow10(s - other.scale))
    }
    override fun equals(other: Any?): Boolean = other is Dec && compareTo(other) == 0
    override fun hashCode(): Int = toPlainString().trimEnd('0').hashCode()

    operator fun plus(o: Dec): Dec {
        val s = maxOf(scale, o.scale)
        return Dec(unscaled * pow10(s - scale) + o.unscaled * pow10(s - o.scale), s)
    }

    /** Number of digits of the unscaled value ("0" has one). */
    val precision: Int get() = decimal(unscaled).length

    /** `setScale(0, HALF_EVEN)` of this × [n], as a Long (throws when it does not fit). */
    fun timesRoundHalfEven(n: Long): Long {
        require(n >= 0) { "amount cannot be negative" }
        val p = unscaled * BigNat.of(n)
        val r = if (scale <= 0) p * pow10(-scale) else {
            val d = pow10(scale)
            val (q, rem) = p.divRem(d)
            val twice = rem + rem
            val c = twice.compareTo(d)
            if (c > 0 || (c == 0 && q.isOdd)) q + BigNat.ONE else q
        }
        require(r.bitLength() <= 63) { "overflow" }
        return r.toLong()
    }

    /** `toPlainString()`. */
    fun toPlainString(): String {
        val digits = decimal(unscaled)
        if (scale <= 0) return if (unscaled.isZero) "0" else digits + "0".repeat(-scale)
        val padded = digits.padStart(scale + 1, '0')
        return padded.substring(0, padded.length - scale) + "." + padded.substring(padded.length - scale)
    }

    override fun toString(): String = toPlainString()

    companion object {
        private val TEN = BigNat.of(10)
        val ZERO = Dec(BigNat.ZERO, 0)

        fun pow10(n: Int): BigNat {
            require(n >= 0)
            var r = BigNat.ONE
            repeat(n) { r = r * TEN }
            return r
        }

        fun decimal(v: BigNat): String {
            if (v.isZero) return "0"
            val parts = ArrayList<String>()
            var x = v
            val chunk = BigNat.of(1_000_000_000)
            while (!x.isZero) { val (q, r) = x.divRem(chunk); parts.add(r.toLong().toString()); x = q }
            val sb = StringBuilder(parts.last())
            for (i in parts.size - 2 downTo 0) sb.append(parts[i].padStart(9, '0'))
            return sb.toString()
        }

        fun of(n: Long): Dec = Dec(BigNat.of(n), 0)

        /** `new BigDecimal(s)` for s ≥ 0 (a leading '+' allowed); throws NumberFormatException otherwise. */
        fun parse(s: String): Dec {
            var t = s
            if (t.startsWith("+")) t = t.substring(1)
            if (t.isEmpty() || t.startsWith("-")) throw NumberFormatException("not a non-negative decimal: $s")
            var exp = 0
            val e = t.indexOfFirst { it == 'e' || it == 'E' }
            if (e >= 0) {
                val es = t.substring(e + 1)
                exp = es.removePrefix("+").toIntOrNull()?.takeIf { es.isNotEmpty() && !es.startsWith("+-") && !es.startsWith("++") }
                    ?: throw NumberFormatException("bad exponent: $s")
                t = t.substring(0, e)
            }
            val dot = t.indexOf('.')
            val intPart = if (dot >= 0) t.substring(0, dot) else t
            val frac = if (dot >= 0) t.substring(dot + 1) else ""
            val digits = intPart + frac
            if (digits.isEmpty() || !digits.all { it in '0'..'9' } || t.count { it == '.' } > 1) throw NumberFormatException("not a decimal: $s")
            var v = BigNat.ZERO
            for (c in digits) v = v * TEN + BigNat.of((c - '0').toLong())
            val scale = frac.length.toLong() - exp
            if (scale !in Int.MIN_VALUE..Int.MAX_VALUE) throw NumberFormatException("scale out of range: $s")
            return Dec(v, scale.toInt())
        }
    }
}
