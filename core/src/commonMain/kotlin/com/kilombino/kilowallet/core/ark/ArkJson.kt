package com.kilombino.kilowallet.core.ark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// What org.json's opt* do in the Android app's Ark.kt, so the port reads the same: a missing or
// mistyped field gives the default.

internal fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
internal fun arr(text: String): JsonArray = Json.parseToJsonElement(text) as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.optString(k: String, def: String = ""): String = prim(k)?.content ?: def
internal fun JsonObject.optLong(k: String, def: Long = 0): Long = prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: def
internal fun JsonObject.optInt(k: String, def: Int = 0): Int = prim(k)?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() } ?: def
internal fun JsonObject.optDouble(k: String): Double = prim(k)?.doubleOrNull ?: Double.NaN
internal fun JsonObject.optBoolean(k: String): Boolean = prim(k)?.booleanOrNull ?: false
internal fun JsonObject.optObj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.optArr(k: String): JsonArray? = this[k] as? JsonArray
internal fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.filter { it.isNotEmpty() }

/** A JSON object built like org.json's put chain. */
internal class JBody {
    private val m = LinkedHashMap<String, JsonElement>()
    fun put(k: String, v: String) = apply { m[k] = JsonPrimitive(v) }
    fun put(k: String, v: Long) = apply { m[k] = JsonPrimitive(v) }
    fun put(k: String, v: Double) = apply { m[k] = JsonPrimitive(v) }
    fun put(k: String, v: JsonElement) = apply { m[k] = v }
    fun put(k: String, v: JBody) = apply { m[k] = v.json() }
    fun json() = JsonObject(m)
    override fun toString() = json().toString()
}

internal fun jsonArray(items: List<String>) = JsonArray(items.map { JsonPrimitive(it) })
