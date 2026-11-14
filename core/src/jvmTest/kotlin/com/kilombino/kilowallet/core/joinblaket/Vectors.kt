package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.crypto.Hashes
import org.json.JSONArray
import org.json.JSONObject

/**
 * `xbt-taker-vectors.json`, produced by JoinBlaket's own Python (`scripts/xbt/taker_vectors.py`,
 * v0.43.0-xbt4), copied verbatim from Kilowallet for Android's test resources (1.6.0).
 * Keys are 32-byte secrets here (Android: BigInteger).
 */
object Vectors {
    val all: JSONObject by lazy { JSONObject(text("/joinblaket/xbt-taker-vectors.json")) }

    fun text(path: String): String = Vectors::class.java.getResourceAsStream(path)!!.bufferedReader().readText()

    fun section(name: String): JSONObject = all.getJSONObject(name)

    /** The generator's fixed 32 bytes for a label: `sha256("xbt-taker-vectors/<label>")`. */
    fun h(label: String): ByteArray = Hashes.sha256("xbt-taker-vectors/$label".toByteArray())

    fun key(label: String): ByteArray = h(label)

    fun hex(s: String): ByteArray = Hashes.hexToBytes(s)

    /** A private key written as a hex number (maybe without leading zeros) as 32 bytes. */
    fun big(hex: String): ByteArray = Hashes.hexToBytes(hex.padStart(64, '0'))

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** The inner `line` of a frame string. */
    fun line(frame: String): String = Wire.parse(frame).line
}
