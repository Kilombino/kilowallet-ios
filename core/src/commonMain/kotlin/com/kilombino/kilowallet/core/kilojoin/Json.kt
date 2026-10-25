package com.kilombino.kilowallet.core.kilojoin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The few JSON calls the protocol makes, over kotlinx.serialization, read as leniently as the
 * Android app's org.json reads them (a number written as 2 or 2.0 is the same; a missing
 * optional field gives its default). Field names and shapes are the protocol's, unchanged.
 */
internal object J {
    fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    fun parseArray(s: String): JsonArray = Json.parseToJsonElement(s).jsonArray

    /** An object from pairs; a null value leaves the field out, as org.json does. */
    fun obj(vararg kv: Pair<String, Any?>): JsonObject = JsonObject(kv.filter { it.second != null }.associate { (k, v) -> k to el(v) })

    fun arr(items: List<Any?>): JsonArray = JsonArray(items.map { el(it) })

    fun el(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { el(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to el(x) })
        else -> JsonPrimitive(v.toString())
    }

    /** [o] with [kv] added (or replaced). */
    fun with(o: JsonObject, vararg kv: Pair<String, Any?>): JsonObject =
        JsonObject(o + kv.filter { it.second != null }.associate { (k, v) -> k to el(v) })
}

internal fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.str(k: String): String = prim(k)?.content ?: error("missing $k")
internal fun JsonObject.optStr(k: String, def: String = ""): String = prim(k)?.content ?: def
internal fun JsonObject.long(k: String): Long = prim(k)?.let { p -> p.content.toLongOrNull() ?: p.content.toDouble().toLong() } ?: error("missing $k")
internal fun JsonObject.optLong(k: String, def: Long = 0): Long = runCatching { long(k) }.getOrDefault(def)
internal fun JsonObject.int(k: String): Int = long(k).toInt()
internal fun JsonObject.optInt(k: String, def: Int = 0): Int = runCatching { int(k) }.getOrDefault(def)
internal fun JsonObject.double(k: String): Double = prim(k)?.content?.toDouble() ?: error("missing $k")
internal fun JsonObject.optDouble(k: String, def: Double = Double.NaN): Double = runCatching { double(k) }.getOrDefault(def)
internal fun JsonObject.bool(k: String): Boolean = prim(k)?.content?.let { it == "true" || (it != "false" && error("not a boolean")) } ?: error("missing $k")
internal fun JsonObject.optBool(k: String, def: Boolean = false): Boolean = runCatching { bool(k) }.getOrDefault(def)
internal fun JsonObject.obj(k: String): JsonObject = this[k]?.jsonObject ?: error("missing $k")
internal fun JsonObject.optObj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: error("missing $k")
internal fun JsonObject.optArr(k: String): JsonArray? = this[k] as? JsonArray
internal fun JsonObject.strings(k: String): List<String>? = optArr(k)?.map { it.jsonPrimitive.content }
internal fun JsonObject.map(k: String): MutableMap<String, String> =
    optObj(k)?.entries?.associate { (a, b) -> a to b.jsonPrimitive.content }?.toMutableMap() ?: mutableMapOf()
